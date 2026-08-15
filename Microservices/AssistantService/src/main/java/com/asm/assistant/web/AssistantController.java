package com.asm.assistant.web;

import com.asm.tenant.TenantContext;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Assistant HTTP surface. Phase 1 exposes only a wiring probe that proves the request arrived
 * authenticated and tenant-resolved; the grounded {@code POST /api/assistant/query} endpoint is
 * added in Phase 4 once retrieval and the LLM layer exist.
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    /** Confirms auth + tenant propagation end-to-end (gateway → tenant filter → controller). */
    @GetMapping("/ping")
    public Map<String, Object> ping(Authentication auth) {
        UUID tenant = TenantContext.get();
        return Map.of(
                "status", "ok",
                "user", auth != null ? auth.getName() : "anonymous",
                "tenant", tenant != null ? tenant.toString() : "none"
        );
    }
}
