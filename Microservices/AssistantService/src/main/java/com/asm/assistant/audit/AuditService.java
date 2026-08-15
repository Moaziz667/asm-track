package com.asm.assistant.audit;

import com.asm.assistant.security.PiiRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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

    private String json(Object o) {
        try {
            return o == null ? "[]" : mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}
