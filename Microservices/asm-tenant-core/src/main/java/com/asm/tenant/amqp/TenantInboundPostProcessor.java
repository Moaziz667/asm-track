package com.asm.tenant.amqp;

import com.asm.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;

import java.util.UUID;

/**
 * Inbound counterpart of {@link TenantMessagePostProcessor}: restores the tenant on the consumer
 * thread before a {@code @RabbitListener} runs, so the listener's database work reaches the right
 * schema.
 *
 * <p>Wired as an {@code afterReceivePostProcessor} on the listener container factory. It coexists
 * with retry advice, which re-invokes the listener in memory without re-receiving — the context set
 * here survives those retries.
 *
 * <p><b>Clear then set.</b> The previous message's tenant is cleared first, so a message arriving
 * without the header never inherits a stale one: it falls through to the default schema, never to
 * another tenant's. A missing header is fail-safe, not a cross-tenant leak.
 */
@Slf4j
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
