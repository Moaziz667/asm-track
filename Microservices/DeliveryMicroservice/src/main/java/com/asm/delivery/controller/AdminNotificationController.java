package com.asm.delivery.controller;

import com.asm.delivery.dto.response.NotificationResponse;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Persistent admin notifications: history (paginated + filtered), unread count, and server-side
 * read/acknowledge state shared across all admins. Live updates still arrive over STOMP.
 */
@RestController
@RequestMapping("/api/v1/admin/notifications")
@Tag(name = "Admin Notifications", description = "Persistent operational notifications")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class AdminNotificationController {

    private final NotificationService notificationService;

    @GetMapping
    @Operation(summary = "List notifications (paginated, filterable by severity/unread/search)")
    public ResponseEntity<Page<NotificationResponse>> list(
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) Boolean unread,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(notificationService.list(severity, unread, search, page, size));
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Mark a notification read")
    public ResponseEntity<Void> markRead(@PathVariable UUID id) {
        notificationService.markRead(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark all notifications read")
    public ResponseEntity<Map<String, Integer>> markAllRead() {
        return ResponseEntity.ok(Map.of("updated", notificationService.markAllRead()));
    }

    @PostMapping("/{id}/acknowledge")
    @Operation(summary = "Acknowledge (and read) a notification")
    public ResponseEntity<Void> acknowledge(@PathVariable UUID id,
                                            @AuthenticationPrincipal UserPrincipal principal) {
        String actor = principal != null ? (principal.getDisplayName() != null
                ? principal.getDisplayName() : principal.getUserId()) : "ADMIN";
        notificationService.acknowledge(id, actor);
        return ResponseEntity.ok().build();
    }
}
