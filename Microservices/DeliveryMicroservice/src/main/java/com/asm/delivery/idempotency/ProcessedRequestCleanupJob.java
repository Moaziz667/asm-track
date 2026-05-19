package com.asm.delivery.idempotency;

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

    @Value("${idempotency.cleanup.ttl-hours:24}")
    private int ttlHours;

    @Scheduled(cron = "0 0 * * * *")
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(ttlHours);
        int deleted = repo.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("ProcessedRequestCleanup: deleted {} entries older than {}h", deleted, ttlHours);
        }
    }
}
