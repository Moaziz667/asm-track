package com.asm.erpadapter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // V2 — for the Odoo→ASM change-polling fallback (ErpChangePoller)
public class ErpAdapterApplication {
    public static void main(String[] args) {
        SpringApplication.run(ErpAdapterApplication.class, args);
    }
}
