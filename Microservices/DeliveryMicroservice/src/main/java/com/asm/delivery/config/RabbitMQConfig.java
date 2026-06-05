package com.asm.delivery.config;

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

    public static final String DRIVER_EVENTS_EXCHANGE  = "driver.events";
    public static final String DRIVER_STATUS_QUEUE     = "driver.status.changed";
    public static final String DRIVER_STATUS_ROUTING   = "driver.status.changed";
    public static final String DLX_EXCHANGE            = "driver.events.dlx";
    public static final String DLQ_DRIVER_STATUS       = "driver.status.changed.dlq";

    @Bean
    public TopicExchange driverEventsExchange() {
        return new TopicExchange(DRIVER_EVENTS_EXCHANGE, true, false);
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

    /** Main queue routes failed messages to the DLX instead of dropping them. */
    @Bean
    public Queue driverStatusQueue() {
        return QueueBuilder.durable(DRIVER_STATUS_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLQ_DRIVER_STATUS)
                .build();
    }

    @Bean
    public Binding driverStatusBinding(Queue driverStatusQueue, TopicExchange driverEventsExchange) {
        return BindingBuilder.bind(driverStatusQueue)
                .to(driverEventsExchange)
                .with(DRIVER_STATUS_ROUTING);
    }

    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * Explicit RabbitTemplate wired with the JSON converter (so payloads are JSON, never
     * Java-serialized) plus publisher confirms + returns. A NACK or an unroutable message
     * is logged instead of silently lost. Requires {@code spring.rabbitmq.publisher-confirm-type=correlated}
     * and {@code publisher-returns=true} (set in application.yml).
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setMandatory(true);
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
     * Listener container factory wired with the JSON converter, so {@code @RabbitListener}
     * methods receive deserialized payloads instead of crashing with MessageConversionException.
     * Boot's configurer is applied first so application.yml retry / requeue settings still take effect.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter);
        // Poison messages are dead-lettered (to the DLX configured on each queue) rather than
        // requeued in a hot loop once retries are exhausted.
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
