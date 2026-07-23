package com.asm.delivery.controller;

import com.asm.delivery.service.DlqReplayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Dead-letter queue operator tooling: inspect parked-message depths and replay them to their
 * original exchange after the root cause is fixed.
 */
@RestController
@RequestMapping("/api/v1/admin/dlq")
@Tag(name = "Admin DLQ", description = "Dead-letter queue monitoring + replay")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class AdminDlqController {

    private final DlqReplayService dlqReplayService;

    @GetMapping
    @Operation(summary = "Current depth of each dead-letter queue")
    public ResponseEntity<Map<String, Object>> depths() {
        return ResponseEntity.ok(dlqReplayService.depths());
    }

    @PostMapping("/{queue}/replay")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Replay up to {max} parked messages to their original exchange")
    public ResponseEntity<Map<String, Object>> replay(@PathVariable String queue,
                                                       @RequestParam(defaultValue = "100") int max) {
        int replayed = dlqReplayService.replay(queue, Math.min(Math.max(max, 1), 1000));
        return ResponseEntity.ok(Map.of("queue", queue, "replayed", replayed));
    }
}
