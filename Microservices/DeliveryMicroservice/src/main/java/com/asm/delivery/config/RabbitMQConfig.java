package com.asm.delivery.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String DRIVER_EVENTS_EXCHANGE  = "driver.events";
    public static final String DRIVER_STATUS_QUEUE     = "driver.status.changed";
    public static final String DRIVER_STATUS_ROUTING   = "driver.status.changed";

    @Bean
    public TopicExchange driverEventsExchange() {
        return new TopicExchange(DRIVER_EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public Queue driverStatusQueue() {
        return new Queue(DRIVER_STATUS_QUEUE, true);
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
