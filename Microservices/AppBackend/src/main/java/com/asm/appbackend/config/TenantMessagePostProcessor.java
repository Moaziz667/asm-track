package com.asm.appbackend.config;

import com.asm.appbackend.security.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * RabbitTemplate post-processor that automatically adds the X-Company-Id header
 * to every outgoing message based on the current TenantContext.
 */
@Slf4j
@Component
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
