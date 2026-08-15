package com.asm.assistant.web;

import com.asm.assistant.ingestion.IngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-triggered corpus ingestion. Read-model rebuild, not business data — restricted to ADMIN.
 * (Runs the same idempotent pipeline as startup: unchanged sources are skipped.)
 */
@RestController
@RequestMapping("/api/assistant/admin")
@RequiredArgsConstructor
public class IngestionController {

    private final IngestionService ingestionService;

    @PostMapping("/ingest")
    @PreAuthorize("hasRole('ADMIN')")
    public IngestionService.Report ingest() {
        return ingestionService.ingestAll();
    }
}
