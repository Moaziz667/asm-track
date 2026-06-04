package com.asm.delivery.controller;

import com.asm.delivery.dto.request.CreateOrderRequest;
import com.asm.delivery.dto.response.CancellableResponse;
import com.asm.delivery.dto.response.OrderResponse;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "Client Orders", description = "Order creation and management for client app")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @Operation(summary = "Create a new order (client)")
    public ResponseEntity<OrderResponse> create(
            @Valid @RequestBody CreateOrderRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        // Legacy endpoint kept temporarily for backward compatibility.
        // Strategic direction: confirmed orders should be imported from ERP.
        OrderResponse response = orderService.createFromApp(req, principal.getUserId(), principal.getDisplayName(), principal.getPhone());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @Operation(summary = "Get all orders for the authenticated client")
    public ResponseEntity<List<OrderResponse>> getAll(@AuthenticationPrincipal UserPrincipal principal) {
        // Legacy endpoint kept temporarily for backward compatibility.
        return ResponseEntity.ok(orderService.getOrdersByClient(principal.getUserId()));
    }

    @GetMapping("/active")
    @Operation(summary = "Get active (non-terminal) orders for the authenticated client")
    public ResponseEntity<List<OrderResponse>> getActive(@AuthenticationPrincipal UserPrincipal principal) {
        // Legacy endpoint kept temporarily for backward compatibility.
        return ResponseEntity.ok(orderService.getActiveOrdersByClient(principal.getUserId()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a specific order by ID")
    public ResponseEntity<OrderResponse> getById(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        // Legacy endpoint kept temporarily for backward compatibility.
        return ResponseEntity.ok(orderService.getOrderById(id, principal.getUserId()));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an order")
    public ResponseEntity<Void> cancel(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        // Legacy endpoint kept temporarily for backward compatibility.
        orderService.cancelOrder(id, principal.getUserId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/cancellable")
    @Operation(summary = "Check if an order can be cancelled")
    public ResponseEntity<CancellableResponse> isCancellable(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        // Legacy endpoint kept temporarily for backward compatibility.
        return ResponseEntity.ok(orderService.isCancellable(id, principal.getUserId()));
    }

    @PostMapping("/{id}/reorder")
    @Operation(summary = "Reorder — create a new order with the same details")
    public ResponseEntity<OrderResponse> reorder(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        // Legacy endpoint kept temporarily for backward compatibility.
        OrderResponse response = orderService.reorder(id, principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
