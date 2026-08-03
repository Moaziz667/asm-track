package com.asm.delivery.config;

import com.asm.delivery.security.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Inbound counterpart of {@link TenantMessagePostProcessor}: on every message received by a
 * {@code @RabbitListener}, reads the {@code X-Company-Id} AMQP header (stamped by the publisher) and
 * sets the {@link TenantContext} BEFORE the listener runs — so the Hibernate connection provider routes
 * the consumer's DB work to the right tenant schema.
 *
 * <p>Wired as an {@code afterReceivePostProcessor} on the listener container factory, it runs on the
 * consumer thread right before the listener, and coexists with the retry advice (which re-invokes the
 * listener in-memory without re-receiving, so the context set here survives retries).
 *
 * <p>Clear-then-set: the previous message's tenant is cleared first, so a message WITHOUT the header
 * never inherits a stale tenant — it falls through to the default (public) schema, never another tenant's.
 * A missing header is therefore fail-safe, not a cross-tenant leak.
 */
@Slf4j
@Component
public class TenantInboundPostProcessor implements MessagePostProcessor {

    @Override
    public Message postProcessMessage(Message message) {
        TenantContext.clear();
        org.slf4j.MDC.put("companyId", "-");
        Object header = message.getMessageProperties().getHeader("X-Company-Id");
        if (header != null) {
            try {
                UUID companyId = UUID.fromString(header.toString());
                TenantContext.set(companyId);
                org.slf4j.MDC.put("companyId", companyId.toString());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid X-Company-Id header on inbound message: {}", header);
            }
        }
        return message;
    }
}
