package com.asm.driver.security;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class KeycloakDriverRollbackEvent extends ApplicationEvent {
    private final String username;

    public KeycloakDriverRollbackEvent(Object source, String username) {
        super(source);
        this.username = username;
    }
}
