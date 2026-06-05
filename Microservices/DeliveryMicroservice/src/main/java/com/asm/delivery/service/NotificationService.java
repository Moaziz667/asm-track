package com.asm.delivery.service;

import com.asm.delivery.dto.response.NotificationResponse;
import com.asm.delivery.entity.Notification;
import com.asm.delivery.repository.NotificationRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persists admin notifications and serves them to the dashboard. Live push stays on STOMP
 * (EventPublisher); this is the durable, shared, queryable record so an offline admin doesn't miss
 * ERP failures / SLA breaches and read-state is server-side rather than per-browser.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository repo;

    /** Persists a notification. Best-effort — a persistence hiccup must never break the triggering flow. */
    public void record(Notification n) {
        try {
            repo.save(n);
        } catch (Exception e) {
            log.warn("Failed to persist notification eventType={}: {}", n.getEventType(), e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> list(String severity, Boolean unread, String search, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);

        Specification<Notification> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (severity != null && !severity.isBlank()) {
                p.add(cb.equal(cb.lower(root.get("severity")), severity.toLowerCase().trim()));
            }
            if (Boolean.TRUE.equals(unread)) {
                p.add(cb.isFalse(root.get("read")));
            }
            if (search != null && !search.isBlank()) {
                String like = "%" + search.toLowerCase().trim() + "%";
                p.add(cb.or(
                        cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("message")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("orderRef"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("clientName"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("driverName"), "")), like)));
            }
            return p.isEmpty() ? cb.conjunction() : cb.and(p.toArray(new Predicate[0]));
        };

        return repo.findAll(spec, PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return repo.countByReadFalse();
    }

    @Transactional
    public void markRead(UUID id) {
        repo.findById(id).ifPresent(n -> { n.setRead(true); repo.save(n); });
    }

    @Transactional
    public int markAllRead() {
        return repo.markAllRead();
    }

    @Transactional
    public void acknowledge(UUID id, String actor) {
        repo.findById(id).ifPresent(n -> {
            n.setAcknowledged(true);
            n.setRead(true);
            n.setAcknowledgedBy(actor);
            n.setAcknowledgedAt(LocalDateTime.now());
            repo.save(n);
        });
    }
}
