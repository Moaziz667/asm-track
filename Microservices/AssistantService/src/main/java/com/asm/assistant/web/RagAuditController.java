package com.asm.assistant.web;

import com.asm.assistant.audit.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reads back the assistant's own trail for the audit console.
 *
 * <p>Separate from the platform's {@code audit_logs} on purpose. That table answers "who changed
 * what"; this one answers "why did the assistant say that" — the question asked, the route that
 * served it, the sources cited, whether it refused. Different columns, different reading, so the
 * console shows them side by side rather than merging them into one list.
 *
 * <p>Gated by {@code perm:audit:view} at the gateway, above the broader rule that opens the rest of
 * {@code /api/assistant} to any operator: being allowed to ask the assistant a question does not
 * make one allowed to read everyone else's.
 */
@RestController
@RequestMapping("/api/assistant/audit")
@RequiredArgsConstructor
public class RagAuditController {

    private final AuditService auditService;

    @GetMapping
    public AuditService.Page list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String route,
            @RequestParam(required = false) Boolean refused) {
        return auditService.list(page, size, route, refused);
    }
}
