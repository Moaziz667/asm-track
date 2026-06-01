package com.asm.delivery.controller;

import com.asm.delivery.dto.response.HandoffResponse;
import com.asm.delivery.entity.HandoffState;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.HandoffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Admin/dispatcher visibility and control over handoffs. Live updates arrive on
 * the admin STOMP topics; the GET here is for initial load / filtering.
 */
@RestController
@RequestMapping("/api/admin/handoffs")
@Tag(name = "Admin Handoffs", description = "Custody transfer oversight")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class AdminHandoffController {

    private final HandoffService handoffService;

    @GetMapping
    @Operation(summary = "List handoffs, optionally filtered by state")
    public ResponseEntity<List<HandoffResponse>> list(@RequestParam(required = false) HandoffState state) {
        return ResponseEntity.ok(handoffService.listForAdmin(state));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an open handoff (e.g. before re-planning)")
    public ResponseEntity<Void> cancel(
            @PathVariable UUID id,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal principal) {
        handoffService.cancel(id, principal, reason);
        return ResponseEntity.ok().build();
    }
}
