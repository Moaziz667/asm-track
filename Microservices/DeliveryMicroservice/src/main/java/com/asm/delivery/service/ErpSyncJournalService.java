package com.asm.delivery.service;

import com.asm.delivery.entity.ErpSyncEvent;
import com.asm.delivery.repository.ErpSyncEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads the ERP sync journal for the operator console.
 *
 * <p>Answers the question the health snapshot cannot: not "what is broken now?" but "what has
 * happened?" — every attempt, successful or not, whichever ERP produced it.
 */
@Service
@RequiredArgsConstructor
public class ErpSyncJournalService {

    /** The console is a tail, not an archive; deep history belongs in a report, not a card. */
    private static final int MAX_LIMIT = 200;

    private final ErpSyncEventRepository repo;
    private final SystemSettingsService settingsService;

    /**
     * A page of attempts, newest first, with a 24h success/failure tally for the card header and the
     * total row count so the console can paginate.
     *
     * @param page       zero-based page index, clamped to {@code >= 0}
     * @param size       page size, clamped to {@value #MAX_LIMIT}
     * @param failedOnly when true, only the rejected attempts
     */
    @Transactional(readOnly = true)
    public Map<String, Object> recent(int page, int size, boolean failedOnly) {
        int capped = Math.min(Math.max(size, 1), MAX_LIMIT);
        int pageIndex = Math.max(page, 0);
        List<ErpSyncEvent> events = repo.recent(failedOnly, PageRequest.of(pageIndex, capped));

        LocalDateTime dayAgo = LocalDateTime.now().minusDays(1);
        Map<String, Object> out = new HashMap<>();
        out.put("events", events.stream().map(ErpSyncJournalService::toMap).toList());
        out.put("total24h", repo.countByOccurredAtAfter(dayAgo));
        out.put("failed24h", repo.countBySuccessFalseAndOccurredAtAfter(dayAgo));
        // The whole journal, not just the page, so the console knows how many pages there are.
        out.put("total", repo.countRecent(failedOnly));
        out.put("page", pageIndex);
        out.put("size", capped);
        // The console names the ERP rather than assuming Odoo — the journal is the only place that
        // knows which one this tenant actually talks to.
        out.put("provider", settingsService.get("erp.provider"));
        return out;
    }

    /** Everything one order has been through — the drill-down behind a single delivery. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forOrder(UUID orderId) {
        return repo.findByOrderIdOrderByOccurredAtDesc(orderId).stream()
                .map(ErpSyncJournalService::toMap)
                .toList();
    }

    private static Map<String, Object> toMap(ErpSyncEvent e) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", e.getId());
        m.put("occurredAt", e.getOccurredAt());
        m.put("provider", e.getProvider());
        m.put("op", e.getOp());
        m.put("success", e.isSuccess());
        m.put("errorReason", e.getErrorReason());
        m.put("orderId", e.getOrderId());
        m.put("rmaId", e.getRmaId());
        m.put("reference", e.getReference());
        return m;
    }
}
