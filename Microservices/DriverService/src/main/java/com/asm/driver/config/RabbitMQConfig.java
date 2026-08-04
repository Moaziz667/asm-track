package com.asm.driver.config;

import com.asm.tenant.amqp.TenantInboundPostProcessor;
import com.asm.tenant.amqp.TenantMessagePostProcessor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class RabbitMQConfig {

    public static final String DRIVER_EVENTS_EXCHANGE = "driver.events";
    public static final String DLX_EXCHANGE           = "driver.events.dlx";
    public static final String DLQ_DRIVER_STATUS      = "driver.status.changed.dlq";

    // ── Commands from Delivery -> Driver (e.g. live location) ─────────────────
    public static final String DRIVER_COMMANDS_EXCHANGE = "driver.commands";
    public static final String DRIVER_LOCATION_ROUTING  = "driver.location.update";
    public static final String DRIVER_LOCATION_QUEUE    = "driver.location.update.queue";
    public static final String DRIVER_COMMANDS_DLX      = "driver.commands.dlx";
    public static final String DRIVER_LOCATION_DLQ      = "driver.location.update.dlq";

    // ── IAM provisioning commands (DriverService → AppBackend, the sole Keycloak owner) ──────────
    public static final String IAM_EXCHANGE    = "iam.exchange";
    public static final String IAM_ROUTING_KEY = "iam.command";

    /** Producer-side declaration (idempotent with AppBackend's). The queue/DLQ are owned by AppBackend. */
    @Bean
    public TopicExchange iamExchange() {
        return new TopicExchange(IAM_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange driverEventsExchange() {
        return new TopicExchange(DRIVER_EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange driverCommandsExchange() {
        return new TopicExchange(DRIVER_COMMANDS_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange driverCommandsDlx() {
        return new DirectExchange(DRIVER_COMMANDS_DLX, true, false);
    }

    @Bean
    public Queue driverLocationDlq() {
        return QueueBuilder.durable(DRIVER_LOCATION_DLQ).build();
    }

    @Bean
    public Binding driverLocationDlqBinding() {
        return BindingBuilder.bind(driverLocationDlq())
                .to(driverCommandsDlx())
                .with(DRIVER_LOCATION_DLQ);
    }

    @Bean
    public Queue driverLocationQueue() {
        return QueueBuilder.durable(DRIVER_LOCATION_QUEUE)
                .withArgument("x-dead-letter-exchange", DRIVER_COMMANDS_DLX)
                .withArgument("x-dead-letter-routing-key", DRIVER_LOCATION_DLQ)
                .build();
    }

    @Bean
    public Binding driverLocationBinding(Queue driverLocationQueue, TopicExchange driverCommandsExchange) {
        return BindingBuilder.bind(driverLocationQueue).to(driverCommandsExchange).with(DRIVER_LOCATION_ROUTING);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ_DRIVER_STATUS).build();
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue())
                .to(deadLetterExchange())
                .with(DLQ_DRIVER_STATUS);
    }

    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * Explicit RabbitTemplate wired with the JSON converter plus publisher confirms + returns,
     * so a NACK or an unroutable message is logged instead of silently lost. Requires
     * {@code spring.rabbitmq.publisher-confirm-type=correlated} and {@code publisher-returns=true}.
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter,
                                         TenantMessagePostProcessor tenantPostProcessor) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setMandatory(true);
        template.setBeforePublishPostProcessors(tenantPostProcessor);
        template.setConfirmCallback((correlation, ack, cause) -> {
            if (!ack) {
                log.error("RabbitMQ publish NACK — correlation={} cause={}",
                        correlation != null ? correlation.getId() : "n/a", cause);
            }
        });
        template.setReturnsCallback(returned -> log.error(
                "RabbitMQ message returned (unroutable) — exchange={} routingKey={} replyCode={} replyText={}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode(), returned.getReplyText()));
        return template;
    }

    /**
     * Listener container factory wired with the JSON converter so {@code @RabbitListener}
     * methods receive deserialized payloads instead of crashing with MessageConversionException.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            MessageConverter messageConverter,
            TenantInboundPostProcessor tenantInboundPostProcessor) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter);
        // Set the tenant from the X-Company-Id header before every listener runs (routes consumer DB work).
        factory.setAfterReceivePostProcessors(tenantInboundPostProcessor);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
