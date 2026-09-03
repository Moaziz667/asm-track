package com.asm.driver.service;

import com.asm.driver.entity.DriverAuditLog;
import com.asm.driver.messaging.AuditEventPublisher;
import com.asm.driver.repository.DriverAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Records what this service does to drivers, locally and on the shared audit trail.
 *
 * <p>Write first, publish after. The local row is written in its own transaction, and only then does
 * the event leave for {@code audit.exchange}. Publishing inside the transaction would mean announcing
 * a fact that a rollback could still undo; publishing before writing would mean losing the trace if
 * the write failed. The order chosen is the only one where the two never contradict each other.
 *
 * <p>Publication is therefore allowed to fail. The row stays with {@code publishedAt} null, and
 * {@link DriverAuditBackfillRunner} replays it at the next startup.
 */
@Service
@RequiredArgsConstructor
public class DriverAuditLogService {

    private final DriverAuditLogRepository auditLogRepo;
    private final AuditEventPublisher auditEventPublisher;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String action, UUID resourceId, String actorName, String actorRole, String details) {
        DriverAuditLog row = auditLogRepo.save(DriverAuditLog.builder()
                .action(action)
                .resourceId(resourceId)
                .actorName(actorName)
                .actorRole(actorRole)
                .details(details)
                .build());

        boolean sent = auditEventPublisher.publish(
                action, actorName, actorRole,
                resourceId != null ? resourceId.toString() : null,
                details);
        if (sent) {
            row.setPublishedAt(LocalDateTime.now());
            auditLogRepo.save(row);
        }
    }
}
