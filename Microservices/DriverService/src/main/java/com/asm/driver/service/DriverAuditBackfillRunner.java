package com.asm.driver.service;

import com.asm.driver.entity.DriverAuditLog;
import com.asm.driver.messaging.AuditEventPublisher;
import com.asm.driver.repository.DriverAuditLogRepository;
import com.asm.tenant.jpa.TenantIterator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Sends to the shared audit trail every local row that never reached it.
 *
 * <p>Two populations, one mechanism. The rows written before this service joined the shared trail
 * have {@code publishedAt} null because nothing ever published them; the rows written since have it
 * null because the broker was unreachable at that moment. Neither case deserves its own code path:
 * the question is the same, "what does this service still owe the trail", and so is the answer.
 *
 * <p>Runs once the application is ready, then hourly. The startup pass is what makes a restart a
 * repair; the hourly pass is what keeps a broker outage from needing one.
 *
 * <p>Per tenant, because the table lives in each tenant schema — an unscoped pass would read the
 * empty {@code public} schema and report success having sent nothing. Publishing inside the tenant
 * context also lets {@code TenantMessagePostProcessor} stamp the company on each message, so the
 * consumer files a replayed row under the same company as a live one.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DriverAuditBackfillRunner {

    private final DriverAuditLogRepository auditLogRepo;
    private final AuditEventPublisher publisher;
    private final TenantIterator tenantIterator;

    /** Bounded per pass: a tenant with a long history must not hold the broker for minutes. */
    @Value("${driver.audit.backfill.batch-size:500}")
    private int batchSize;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        drainAllTenants("startup");
    }

    @Scheduled(cron = "${driver.audit.backfill.cron:0 20 * * * *}")
    public void onSchedule() {
        drainAllTenants("scheduled");
    }

    private void drainAllTenants(String trigger) {
        tenantIterator.forEachActive(companyId -> {
            long owed = auditLogRepo.countByPublishedAtIsNull();
            if (owed == 0) return;
            int sent = drainOneTenant();
            log.info("Driver audit backfill ({}): {} of {} pending event(s) published for company {}",
                    trigger, sent, owed, companyId);
        });
    }

    /**
     * Marks each row only once its own event has been accepted. A row whose publication fails keeps a
     * null {@code publishedAt} and is picked up again, so the worst case is a duplicate rather than a
     * hole — and the consumer is the right place to absorb a duplicate, not this loop to risk a loss.
     *
     * <p>The pass stops at the first refusal: if the broker has just declined one message it will
     * decline the next five hundred, and hammering it adds nothing but log noise.
     *
     * <p>Deliberately not {@code @Transactional}: each {@code save} commits on its own. One
     * transaction around the loop would undo the marks of five hundred events already gone out on the
     * broker if the last one failed, and every one of them would be sent a second time at the next
     * pass. It would also be a no-op here anyway, since this is called from a method of the same bean
     * and would never go through the Spring proxy.
     */
    private int drainOneTenant() {
        List<DriverAuditLog> pending =
                auditLogRepo.findByPublishedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, batchSize));
        int sent = 0;
        for (DriverAuditLog row : pending) {
            boolean ok = publisher.publish(
                    row.getAction(), row.getActorName(), row.getActorRole(),
                    row.getResourceId() != null ? row.getResourceId().toString() : null,
                    row.getDetails());
            if (!ok) break;
            row.setPublishedAt(LocalDateTime.now());
            auditLogRepo.save(row);
            sent++;
        }
        return sent;
    }
}
