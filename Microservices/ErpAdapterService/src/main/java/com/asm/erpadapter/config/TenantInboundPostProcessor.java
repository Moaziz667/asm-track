package com.asm.erpadapter.config;

import com.asm.erpadapter.security.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Inbound counterpart of {@link TenantMessagePostProcessor}: on every message received by a
 * {@code @RabbitListener}, reads the {@code X-Company-Id} AMQP header and sets the {@link TenantContext}
 * before the listener runs. ErpAdapter is stateless (no DB), but the tenant drives which company's ERP
 * config is used and is propagated on outbound Feign calls.
 *
 * <p>Clear-then-set: a message without the header never inherits a stale tenant. Fail-safe, not a leak.
 */
@Slf4j
@Component
public class TenantInboundPostProcessor implements MessagePostProcessor {

    @Override
    public Message postProcessMessage(Message message) {
        TenantContext.clear();
        Object header = message.getMessageProperties().getHeader("X-Company-Id");
        if (header != null) {
            try {
                TenantContext.set(UUID.fromString(header.toString()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid X-Company-Id header on inbound message: {}", header);
            }
        }
        return message;
    }
}
