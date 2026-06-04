package com.asm.appbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class AppBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(AppBackendApplication.class, args);
    }
}
