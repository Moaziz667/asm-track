package com.asm.appbackend.messaging;

import com.asm.appbackend.config.RabbitMQConfig;
import com.asm.appbackend.service.IamCommandApplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Consumes IAM provisioning commands published by DriverService (its outbox → {@code iam.exchange})
 * and applies them to Keycloak via the shared {@link IamCommandApplier}. AppBackend is the sole
 * Keycloak owner, so all driver provisioning funnels through here. A throw rejects the message to
 * the DLQ ({@code iam.commands.dlq}) instead of being lost.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IamCommandConsumer {

    private final IamCommandApplier applier;

    @RabbitListener(queues = RabbitMQConfig.IAM_COMMANDS_QUEUE)
    public void onCommand(Map<String, Object> msg) {
        String op = (String) msg.get("op");
        log.info("IAM command received op={} appUserId={}", op, msg.get("appUserId"));
        applier.apply(op, msg);
    }
}
