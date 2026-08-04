package com.asm.delivery.idempotency;

import com.asm.tenant.jpa.TenantIterator;
import com.asm.delivery.repository.ProcessedRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessedRequestCleanupJob {

    private final ProcessedRequestRepository repo;
    private final TenantIterator tenantIterator;

    @Value("${idempotency.cleanup.ttl-hours:24}")
    private int ttlHours;

    // Runs per provisioned tenant: the idempotency table lives in each tenant schema, so an unscoped
    // sweep would only ever prune the empty `public` schema, letting every tenant's table grow forever.
    // The derived delete carries its own transaction, so no explicit @Transactional is needed here.
    @Scheduled(cron = "0 0 * * * *")
    public void cleanup() {
        tenantIterator.forEachActive(companyId -> {
            LocalDateTime cutoff = LocalDateTime.now().minusHours(ttlHours);
            int deleted = repo.deleteByCreatedAtBefore(cutoff);
            if (deleted > 0) {
                log.info("ProcessedRequestCleanup: deleted {} entries older than {}h for tenant {}",
                        deleted, ttlHours, companyId);
            }
        });
    }
}
