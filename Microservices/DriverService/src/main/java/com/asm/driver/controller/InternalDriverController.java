package com.asm.driver.controller;

import com.asm.driver.dto.request.IncrementStatRequest;
import com.asm.driver.dto.request.LocationRequest;
import com.asm.driver.dto.response.InternalDriverResponse;
import com.asm.driver.service.InternalDriverService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Hidden
@RestController
@RequestMapping("/internal/drivers")
@RequiredArgsConstructor
public class InternalDriverController {

    private final InternalDriverService internalService;

    @GetMapping("/available")
    public ResponseEntity<List<InternalDriverResponse>> getAvailableDrivers(
            ) {
        return ResponseEntity.ok(internalService.getAvailableDrivers());
    }

    @GetMapping("/{id}")
    public ResponseEntity<InternalDriverResponse> getDriver(@PathVariable UUID id) {
        return ResponseEntity.ok(internalService.getDriver(id));
    }

    @GetMapping("/batch")
    public ResponseEntity<List<InternalDriverResponse>> getDriversBatch(@RequestParam List<UUID> ids) {
        return ResponseEntity.ok(internalService.getDriversBatch(ids));
    }

    @PutMapping("/{id}/location")
    public ResponseEntity<Map<String, String>> updateLocation(@PathVariable UUID id,
                                                              @Valid @RequestBody LocationRequest req) {
        internalService.updateLocation(id, req.getLat(), req.getLng());
        return ResponseEntity.ok(Map.of("message", "Location updated"));
    }

    @PostMapping("/{id}/stats/increment")
    public ResponseEntity<Map<String, String>> incrementStat(@PathVariable UUID id,
                                                             @Valid @RequestBody IncrementStatRequest req,
                                                             String deliveryId) {
        internalService.incrementStat(id, req.getField(), deliveryId);
        return ResponseEntity.ok(Map.of("message", "Stat incremented"));
    }
}
