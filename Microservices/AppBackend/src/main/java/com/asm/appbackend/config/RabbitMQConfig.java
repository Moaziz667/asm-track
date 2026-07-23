package com.asm.appbackend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publisher-side RabbitMQ wiring for AppBackend. AppBackend only emits audit events; the queue,
 * bindings and DLQ are declared by the consumer (DeliveryMicroservice). JSON serialization +
 * publisher confirms ensure an audit event is never silently lost or Java-serialized.
 */
@Configuration
@Slf4j
public class RabbitMQConfig {

    public static final String AUDIT_EXCHANGE = "audit.exchange";
    public static final String AUDIT_ROUTING_KEY = "audit.log";

    // ── IAM provisioning commands (DriverService → AppBackend, the sole Keycloak owner) ──────────
    public static final String IAM_EXCHANGE      = "iam.exchange";
    public static final String IAM_ROUTING_KEY   = "iam.command";
    public static final String IAM_COMMANDS_QUEUE = "iam.commands.q";
    public static final String IAM_DLX           = "iam.dlx";
    public static final String IAM_DLQ           = "iam.commands.dlq";

    @Bean
    public TopicExchange auditExchange() {
        return new TopicExchange(AUDIT_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange iamExchange() {
        return new TopicExchange(IAM_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange iamDlx() {
        return new TopicExchange(IAM_DLX, true, false);
    }

    @Bean
    public Queue iamCommandsQueue() {
        return QueueBuilder.durable(IAM_COMMANDS_QUEUE)
                .withArgument("x-dead-letter-exchange", IAM_DLX)
                .build();
    }

    @Bean
    public Queue iamDlq() {
        return QueueBuilder.durable(IAM_DLQ).build();
    }

    @Bean
    public Binding iamBinding() {
        return BindingBuilder.bind(iamCommandsQueue()).to(iamExchange()).with("iam.#");
    }

    @Bean
    public Binding iamDlqBinding() {
        return BindingBuilder.bind(iamDlq()).to(iamDlx()).with("#");
    }

    /** JSON-aware listener factory so {@code @RabbitListener} on the IAM queue gets a deserialized Map. */
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
        factory.setDefaultRequeueRejected(false); // failed command → DLQ, not infinite redelivery
        return factory;
    }

    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

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
                "RabbitMQ message returned (unroutable) — exchange={} routingKey={} replyText={}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
        return template;
    }
}
