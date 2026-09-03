package com.asm.assistant.audit;

import com.asm.assistant.security.PiiRedactor;
import com.asm.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Writes one row per assistant interaction to {@code rag_audit}: who asked what (PII-redacted), which
 * route answered, which chunks grounded it, which live tools ran, whether it refused, and how long it
 * took. Auditing must never break answering — a failure here is logged and swallowed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final JdbcTemplate jdbc;
    private final PiiRedactor redactor;
    private final ObjectMapper mapper = new ObjectMapper();

    public record Entry(
            UUID tenantId, String userId, String requestId, String question, String route,
            List<UUID> retrievedIds, Object citations, List<String> toolCalls,
            boolean refused, long latencyMs) {}

    public void record(Entry e) {
        try {
            jdbc.update("""
                    INSERT INTO rag_audit
                    (id, tenant_id, user_id, request_id, question, route,
                     retrieved_ids, citations, tool_calls, refused, latency_ms)
                    VALUES (?,?,?,?,?,?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)
                    """,
                    UUID.randomUUID(), e.tenantId(), e.userId(), e.requestId(),
                    redactor.redact(e.question()), e.route(),
                    json(e.retrievedIds()), json(e.citations()), json(e.toolCalls()),
                    e.refused(), (int) e.latencyMs());
        } catch (Exception ex) {
            log.warn("Audit write failed (answer still served): {}", ex.getMessage());
        }
    }

    /** One page of this tenant's interactions, newest first. */
    public record Page(List<Row> rows, long total, int page, int size) {}

    public record Row(String id, String userId, String question, String route,
                      boolean refused, Integer latencyMs, String citations,
                      java.time.OffsetDateTime createdAt) {}

    /**
     * Reads back what {@link #record} wrote, for the audit console.
     *
     * <p>The tenant comes from {@link TenantContext}, never from a parameter: a caller must not be
     * able to name the company whose interactions it reads. No context means no rows, rather than
     * every tenant's — this table is scoped by a column, so a forgotten predicate would return the
     * whole platform's history instead of nothing.
     *
     * <p>The ordering matches {@code ix_rag_audit_tenant_time} so the page is an index scan.
     */
    public Page list(int page, int size, String route, Boolean refused) {
        UUID tenant = TenantContext.get();
        if (tenant == null) {
            log.warn("Assistant audit read attempted with no tenant context — returning nothing");
            return new Page(List.of(), 0, page, size);
        }
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);

        StringBuilder where = new StringBuilder("WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        if (route != null && !route.isBlank()) {
            where.append(" AND route = ?");
            args.add(route.trim().toUpperCase());
        }
        if (refused != null) {
            where.append(" AND refused = ?");
            args.add(refused);
        }

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM rag_audit " + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add(safePage * safeSize);
        List<Row> rows = jdbc.query(
                "SELECT id, user_id, question, route, refused, latency_ms, citations, created_at "
                        + "FROM rag_audit " + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, i) -> new Row(
                        rs.getString("id"),
                        rs.getString("user_id"),
                        rs.getString("question"),
                        rs.getString("route"),
                        rs.getBoolean("refused"),
                        (Integer) rs.getObject("latency_ms"),
                        rs.getString("citations"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class)),
                pageArgs.toArray());

        return new Page(rows, total != null ? total : 0, safePage, safeSize);
    }

    private String json(Object o) {
        try {
            return o == null ? "[]" : mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}
