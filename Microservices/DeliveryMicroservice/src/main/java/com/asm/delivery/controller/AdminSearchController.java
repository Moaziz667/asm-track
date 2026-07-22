package com.asm.delivery.controller;

import com.asm.delivery.dto.response.GlobalSearchResponse;
import com.asm.delivery.service.GlobalSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/search")
@RequiredArgsConstructor
@Tag(name = "Admin · Global Search", description = "Cross-entity quick search for the back-office command bar "
        + "(deliveries, routes, drivers…). Scoped to the caller's company.")
@SecurityRequirement(name = "bearerAuth")
public class AdminSearchController {

    private final GlobalSearchService searchService;

    @GetMapping
    @Operation(summary = "Global search",
            description = "Searches across entities for the term 'q' (min 2 chars; shorter returns empty). "
                    + "'limit' caps results per group (max 10).")
    @ApiResponse(responseCode = "200", description = "Grouped search results")
    public ResponseEntity<GlobalSearchResponse> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "6") int limit) {

        String term = q == null ? "" : q.trim();
        if (term.length() < 2) return ResponseEntity.ok(GlobalSearchResponse.empty());

        return ResponseEntity.ok(searchService.search(term, Math.min(limit, 10)));
    }
}
