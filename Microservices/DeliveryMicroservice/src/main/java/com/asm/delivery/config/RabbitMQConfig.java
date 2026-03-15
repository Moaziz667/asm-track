package com.asm.delivery.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${app.rabbitmq.incoming-exchange}")
    private String incomingExchange;

    @Value("${app.rabbitmq.outgoing-exchange}")
    private String outgoingExchange;

    @Value("${app.rabbitmq.queue-orders-created}")
    private String ordersCreatedQueue;

    @Value("${app.rabbitmq.queue-workflow-commands}")
    private String workflowCommandsQueue;

    @Value("${app.rabbitmq.queue-delivery-events-debug}")
    private String deliveryEventsDebugQueue;

    // ── Exchanges ─────────────────────────────────────────────────────────────

    /** Incoming exchange: published by Mapper Service (delivery.events). */
    @Bean
    public TopicExchange incomingExchange() {
        return ExchangeBuilder.topicExchange(incomingExchange).durable(true).build();
    }

    /** Outgoing exchange: delivery service publishes its own lifecycle events. */
    @Bean
    public TopicExchange outgoingExchange() {
        return ExchangeBuilder.topicExchange(outgoingExchange).durable(true).build();
    }

    // ── Queues ────────────────────────────────────────────────────────────────

    /** Queue this service consumes: canonical delivery orders from Mapper. */
    @Bean
    public Queue ordersCreatedQueue() {
        return QueueBuilder.durable(ordersCreatedQueue).build();
    }

    /** Queue for workflow commands sent back to this service. */
    @Bean
    public Queue workflowCommandsQueue() {
        return QueueBuilder.durable(workflowCommandsQueue).build();
    }

    /** Debug queue to observe delivery-service outgoing events. */
    @Bean
    public Queue deliveryEventsDebugQueue() {
        return QueueBuilder.durable(deliveryEventsDebugQueue).build();
    }

    // ── Bindings ──────────────────────────────────────────────────────────────

    /** Bind orders.created queue to incoming exchange with routing key delivery.created */
    @Bean
    public Binding ordersCreatedBinding(Queue ordersCreatedQueue, TopicExchange incomingExchange) {
        return BindingBuilder.bind(ordersCreatedQueue)
                .to(incomingExchange)
                .with("delivery.created");
    }

    /** Bind debug queue to all delivery-service outgoing events. */
    @Bean
    public Binding deliveryEventsDebugBinding(Queue deliveryEventsDebugQueue, TopicExchange outgoingExchange) {
        return BindingBuilder.bind(deliveryEventsDebugQueue)
                .to(outgoingExchange)
                .with("delivery.*");
    }

    // ── Message Converter ─────────────────────────────────────────────────────

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter());
        return factory;
    }
}
