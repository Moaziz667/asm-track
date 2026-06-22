package com.asm.delivery.controller;

import com.asm.delivery.dto.request.CreateRmaRequest;
import com.asm.delivery.dto.response.RmaResponse;
import com.asm.delivery.entity.RmaStatus;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.RmaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/returns")
@Tag(name = "Admin Returns (RMA)", description = "Customer returns / RMA management")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class AdminRmaController {

    private final RmaService rmaService;

    @GetMapping
    @Operation(summary = "List returns (paginated), optionally filtered by status and search query")
    public ResponseEntity<org.springframework.data.domain.Page<RmaResponse>> list(
            @RequestParam(required = false) RmaStatus status,
            @RequestParam(required = false) String q,
            @org.springdoc.core.annotations.ParameterObject
            @org.springframework.data.web.PageableDefault(size = 25) org.springframework.data.domain.Pageable pageable) {
        return ResponseEntity.ok(rmaService.list(status, q, pageable));
    }

    @GetMapping("/kpi")
    @Operation(summary = "Returns KPI tallies (by status, open, restocked)")
    public ResponseEntity<Map<String, Object>> kpi() {
        return ResponseEntity.ok(rmaService.kpi());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a return")
    public ResponseEntity<RmaResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(rmaService.get(id));
    }

    @PostMapping
    @Operation(summary = "Create a return (RMA) against a delivered shipment")
    public ResponseEntity<RmaResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateRmaRequest request) {
        return ResponseEntity.ok(rmaService.create(request, principal));
    }

    @PostMapping("/{id}/transition")
    @Operation(summary = "Move a return to a new status",
               description = "target ∈ APPROVED | RECEIVED | RESTOCKED | REJECTED | CANCELLED. RESTOCKED pushes a reverse stock move to the ERP.")
    public ResponseEntity<RmaResponse> transition(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @RequestParam RmaStatus target,
            @RequestParam(required = false) String note) {
        return ResponseEntity.ok(rmaService.transition(id, target, note, principal));
    }

    @PostMapping("/{id}/resync")
    @Operation(summary = "Re-run the ERP reverse-move for a return whose sync failed",
               description = "Allowed only for a RESTOCKED return in SYNC_FAILED; re-enqueues the reverse stock move via the outbox.")
    public ResponseEntity<RmaResponse> resync(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        return ResponseEntity.ok(rmaService.resync(id, principal));
    }
}
