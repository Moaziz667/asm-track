package com.asm.appbackend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
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

    @Bean
    public TopicExchange auditExchange() {
        return new TopicExchange(AUDIT_EXCHANGE, true, false);
    }

    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

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
                "RabbitMQ message returned (unroutable) — exchange={} routingKey={} replyText={}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
        return template;
    }
}
