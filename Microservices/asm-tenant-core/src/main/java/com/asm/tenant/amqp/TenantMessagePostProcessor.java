package com.asm.tenant.amqp;

import com.asm.tenant.TenantContext;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;

import java.util.UUID;

/**
 * Stamps every outgoing AMQP message with the current tenant, so the consumer can restore it.
 *
 * <p>Wired on the RabbitTemplate. Without it, an event published inside a tenant's transaction would
 * arrive with no way of telling whose data it concerns.
 */
public class TenantMessagePostProcessor implements MessagePostProcessor {

    @Override
    public Message postProcessMessage(Message message) {
        UUID companyId = TenantContext.get();
        if (companyId != null) {
            message.getMessageProperties().setHeader("X-Company-Id", companyId.toString());
        }
        return message;
    }
}
