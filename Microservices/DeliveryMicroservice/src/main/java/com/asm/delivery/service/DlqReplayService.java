package com.asm.delivery.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operator tooling to inspect and replay dead-lettered messages. After fixing the root cause (e.g.
 * Odoo back online), an admin can requeue parked messages to their original exchange instead of
 * losing them. Replays are idempotent downstream (consumers dedupe on txId / eventId).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DlqReplayService {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitAdmin rabbitAdmin;

    /** DLQ -> [original exchange, routing key] to replay to. */
    private static final Map<String, String[]> ROUTES = Map.of(
            "erp.sync.command.dlq",        new String[]{"erp.sync.exchange", "erp.sync.command"},
            "erp.sync.result.dlq",         new String[]{"erp.sync.result.exchange", "erp.sync.result"},
            "audit.log.dlq",               new String[]{"audit.exchange", "audit.log"},
            "driver.location.update.dlq",  new String[]{"driver.commands", "driver.location.update"}
    );

    /** Current depth of each known DLQ (messages parked awaiting replay). */
    public Map<String, Object> depths() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String dlq : ROUTES.keySet()) {
            QueueInformation info = rabbitAdmin.getQueueInfo(dlq);
            out.put(dlq, info != null ? info.getMessageCount() : -1);
        }
        return out;
    }

    /** Drains up to {@code max} messages from a DLQ and republishes them to the original exchange. */
    public int replay(String dlq, int max) {
        String[] route = ROUTES.get(dlq);
        if (route == null) {
            throw new IllegalArgumentException("Unknown or non-replayable DLQ: " + dlq);
        }
        int replayed = 0;
        for (int i = 0; i < max; i++) {
            Message msg = rabbitTemplate.receive(dlq);
            if (msg == null) break; // queue empty
            rabbitTemplate.send(route[0], route[1], msg);
            replayed++;
        }
        log.info("DLQ replay — queue={} replayed={} -> exchange={} routingKey={}", dlq, replayed, route[0], route[1]);
        return replayed;
    }
}
