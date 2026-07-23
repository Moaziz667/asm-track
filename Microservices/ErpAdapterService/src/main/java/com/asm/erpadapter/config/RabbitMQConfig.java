package com.asm.erpadapter.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
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
 * ERP sync messaging for ErpAdapter: consumes delivery-outcome commands from {@code erp.sync.exchange},
 * applies them to Odoo, and publishes outcomes to {@code erp.sync.result.exchange}. Command processing
 * uses container retry; exhausted/poison messages dead-letter to {@code erp.sync.command.dlq}.
 */
@Configuration
@Slf4j
public class RabbitMQConfig {

    public static final String SYNC_EXCHANGE        = "erp.sync.exchange";
    public static final String SYNC_ROUTING_KEY     = "erp.sync.command";
    public static final String SYNC_QUEUE           = "erp.sync.command.queue";
    public static final String SYNC_DLX             = "erp.sync.dlx";
    public static final String SYNC_DLQ             = "erp.sync.command.dlq";

    public static final String RESULT_EXCHANGE      = "erp.sync.result.exchange";
    public static final String RESULT_ROUTING_KEY   = "erp.sync.result";

    @Bean
    public TopicExchange erpSyncExchange() {
        return new TopicExchange(SYNC_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange erpSyncResultExchange() {
        return new TopicExchange(RESULT_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange erpSyncDlx() {
        return new DirectExchange(SYNC_DLX, true, false);
    }

    @Bean
    public Queue erpSyncDlq() {
        return QueueBuilder.durable(SYNC_DLQ).build();
    }

    @Bean
    public Binding erpSyncDlqBinding() {
        return BindingBuilder.bind(erpSyncDlq()).to(erpSyncDlx()).with(SYNC_DLQ);
    }

    @Bean
    public Queue erpSyncQueue() {
        return QueueBuilder.durable(SYNC_QUEUE)
                .withArgument("x-dead-letter-exchange", SYNC_DLX)
                .withArgument("x-dead-letter-routing-key", SYNC_DLQ)
                .build();
    }

    @Bean
    public Binding erpSyncBinding(Queue erpSyncQueue, TopicExchange erpSyncExchange) {
        return BindingBuilder.bind(erpSyncQueue).to(erpSyncExchange).with(SYNC_ROUTING_KEY);
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

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            MessageConverter messageConverter,
            TenantInboundPostProcessor tenantInboundPostProcessor) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter);
        // Set the tenant from the X-Company-Id header before every listener runs (propagated on Feign).
        factory.setAfterReceivePostProcessors(tenantInboundPostProcessor);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
