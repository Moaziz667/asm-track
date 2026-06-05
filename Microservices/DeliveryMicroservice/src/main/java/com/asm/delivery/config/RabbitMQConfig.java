package com.asm.delivery.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
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
}
