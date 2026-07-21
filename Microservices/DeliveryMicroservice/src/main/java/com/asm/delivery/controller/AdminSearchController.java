package com.asm.delivery.controller;

import com.asm.delivery.dto.response.GlobalSearchResponse;
import com.asm.delivery.service.GlobalSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/search")
@RequiredArgsConstructor
public class AdminSearchController {

    private final GlobalSearchService searchService;

    @GetMapping
    public ResponseEntity<GlobalSearchResponse> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "6") int limit) {

        String term = q == null ? "" : q.trim();
        if (term.length() < 2) return ResponseEntity.ok(GlobalSearchResponse.empty());

        return ResponseEntity.ok(searchService.search(term, Math.min(limit, 10)));
    }
}
