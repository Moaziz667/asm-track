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

    // ── Commands to Driver (Delivery → Driver, e.g. live location) ────────────
    public static final String DRIVER_COMMANDS_EXCHANGE = "driver.commands";
    public static final String DRIVER_LOCATION_ROUTING  = "driver.location.update";

    // ── ERP sync (Delivery → ErpAdapter command; ErpAdapter → Delivery result) ─
    public static final String ERP_SYNC_EXCHANGE        = "erp.sync.exchange";
    public static final String ERP_SYNC_ROUTING         = "erp.sync.command";
    public static final String ERP_SYNC_RESULT_EXCHANGE = "erp.sync.result.exchange";
    public static final String ERP_SYNC_RESULT_ROUTING  = "erp.sync.result";
    public static final String ERP_SYNC_RESULT_QUEUE    = "erp.sync.result.queue";
    public static final String ERP_SYNC_RESULT_DLX      = "erp.sync.result.dlx";
    public static final String ERP_SYNC_RESULT_DLQ      = "erp.sync.result.dlq";

    // ── Audit events (AppBackend → Delivery) ──────────────────────────────────
    public static final String AUDIT_EXCHANGE     = "audit.exchange";
    public static final String AUDIT_ROUTING_KEY  = "audit.log";
    public static final String AUDIT_QUEUE        = "audit.log.queue";
    public static final String AUDIT_DLX_EXCHANGE = "audit.exchange.dlx";
    public static final String AUDIT_DLQ          = "audit.log.dlq";

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

    /** Exchange for Delivery → Driver commands (queue/bindings owned by DriverService). */
    @Bean
    public TopicExchange driverCommandsExchange() {
        return new TopicExchange(DRIVER_COMMANDS_EXCHANGE, true, false);
    }

    // ── ERP sync command exchange (publish) + result queue (consume) ──────────

    /** Command exchange — Delivery publishes; the queue/binding are owned by ErpAdapter. */
    @Bean
    public TopicExchange erpSyncExchange() {
        return new TopicExchange(ERP_SYNC_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange erpSyncResultExchange() {
        return new TopicExchange(ERP_SYNC_RESULT_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange erpSyncResultDlx() {
        return new DirectExchange(ERP_SYNC_RESULT_DLX, true, false);
    }

    @Bean
    public Queue erpSyncResultDlq() {
        return QueueBuilder.durable(ERP_SYNC_RESULT_DLQ).build();
    }

    @Bean
    public Binding erpSyncResultDlqBinding() {
        return BindingBuilder.bind(erpSyncResultDlq()).to(erpSyncResultDlx()).with(ERP_SYNC_RESULT_DLQ);
    }

    @Bean
    public Queue erpSyncResultQueue() {
        return QueueBuilder.durable(ERP_SYNC_RESULT_QUEUE)
                .withArgument("x-dead-letter-exchange", ERP_SYNC_RESULT_DLX)
                .withArgument("x-dead-letter-routing-key", ERP_SYNC_RESULT_DLQ)
                .build();
    }

    @Bean
    public Binding erpSyncResultBinding(Queue erpSyncResultQueue, TopicExchange erpSyncResultExchange) {
        return BindingBuilder.bind(erpSyncResultQueue).to(erpSyncResultExchange).with(ERP_SYNC_RESULT_ROUTING);
    }

    // ── Audit topology ────────────────────────────────────────────────────────

    @Bean
    public TopicExchange auditExchange() {
        return new TopicExchange(AUDIT_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange auditDeadLetterExchange() {
        return new DirectExchange(AUDIT_DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue auditDeadLetterQueue() {
        return QueueBuilder.durable(AUDIT_DLQ).build();
    }

    @Bean
    public Binding auditDeadLetterBinding() {
        return BindingBuilder.bind(auditDeadLetterQueue())
                .to(auditDeadLetterExchange())
                .with(AUDIT_DLQ);
    }

    @Bean
    public Queue auditQueue() {
        return QueueBuilder.durable(AUDIT_QUEUE)
                .withArgument("x-dead-letter-exchange", AUDIT_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", AUDIT_DLQ)
                .build();
    }

    @Bean
    public Binding auditBinding(Queue auditQueue, TopicExchange auditExchange) {
        return BindingBuilder.bind(auditQueue).to(auditExchange).with(AUDIT_ROUTING_KEY);
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
    /** Explicit RabbitAdmin so it is injectable (DLQ inspection/replay) and declares the topology. */
    @Bean
    public org.springframework.amqp.rabbit.core.RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new org.springframework.amqp.rabbit.core.RabbitAdmin(connectionFactory);
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
