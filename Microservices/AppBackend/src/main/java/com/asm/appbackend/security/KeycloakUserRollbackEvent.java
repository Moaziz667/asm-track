package com.asm.appbackend.security;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class KeycloakUserRollbackEvent extends ApplicationEvent {
    private final String email;

    public KeycloakUserRollbackEvent(Object source, String email) {
        super(source);
        this.email = email;
    }
}
