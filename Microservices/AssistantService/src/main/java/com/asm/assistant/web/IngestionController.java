package com.asm.assistant.web;

import com.asm.assistant.ingestion.IngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-triggered corpus ingestion. Read-model rebuild, not business data.
 *
 * <p>Guarded by a permission rather than a role, like the rest of the platform: a role check cannot
 * be granted to a custom role however many permissions it is given, and would silently escape the
 * RBAC model. {@code settings:manage} is the same permission that opens the other referential
 * screens, and only ADMIN holds it.
 *
 * <p>The gateway carries the same rule on {@code /api/assistant/admin}: everything else under
 * {@code /api/assistant} only requires an authenticated caller, which is right for asking a question
 * and far too wide for rebuilding the corpus.
 *
 * <p>(Runs the same idempotent pipeline as startup: unchanged sources are skipped.)
 */
@RestController
@RequestMapping("/api/assistant/admin")
@RequiredArgsConstructor
public class IngestionController {

    private final IngestionService ingestionService;

    @PostMapping("/ingest")
    @PreAuthorize("hasAuthority('perm:settings:manage')")
    public IngestionService.Report ingest() {
        return ingestionService.ingestAll();
    }
}
