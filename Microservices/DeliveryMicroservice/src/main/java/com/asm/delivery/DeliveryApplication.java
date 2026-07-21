package com.asm.delivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

// @EnableScheduling lives on SchedulingConfig (conditional on app.scheduling.enabled) so tests can turn
// the background jobs off — their external calls would otherwise leave non-daemon threads stuck.
@SpringBootApplication
@EnableAsync
@EnableAspectJAutoProxy
@EnableTransactionManagement(order = 0)
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class DeliveryApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeliveryApplication.class, args);
    }
}
