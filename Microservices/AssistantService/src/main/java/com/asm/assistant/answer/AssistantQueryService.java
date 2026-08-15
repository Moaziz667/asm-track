package com.asm.assistant.answer;

import com.asm.assistant.audit.AuditService;
import com.asm.assistant.context.AssembledContext.Citation;
import com.asm.assistant.observability.RagMetrics;
import com.asm.assistant.security.AssistantRateLimiter;
import com.asm.assistant.security.PromptInjectionGuard;
import com.asm.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Cross-cutting entry point around {@link AnswerService}: rate-limit → injection scan → timed answer
 * → audit → metrics. Kept separate so the orchestrator stays focused on answering and remains simple
 * to unit-test; this is where the enterprise guardrails wrap every request.
 */
@Service
@RequiredArgsConstructor
public class AssistantQueryService {

    private final AnswerService answerService;
    private final AssistantRateLimiter rateLimiter;
    private final PromptInjectionGuard injectionGuard;
    private final AuditService audit;
    private final RagMetrics metrics;

    public AnswerResponse handle(String question) {
        UUID tenant = TenantContext.get();
        String userId = currentUserId();
        String requestId = MDC.get("requestId");

        if (!rateLimiter.allow(tenant + ":" + userId)) {
            metrics.rateLimited();
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Trop de requêtes, réessayez plus tard.");
        }

        if (injectionGuard.inspect(question).flagged()) {
            metrics.injectionFlagged();   // flag + count; the grounding prompt neutralizes it
        }

        long start = System.nanoTime();
        AnswerResponse resp = metrics.record(metrics.answerTimer, () -> answerService.answer(question));
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        metrics.recordAnswer(resp.route(), resp.grounded(), resp.refused(), resp.degraded());
        audit.record(new AuditService.Entry(
                tenant, userId, requestId, question, resp.route(),
                resp.citations().stream().map(Citation::chunkId).toList(),
                resp.citations(), resp.liveSources(),
                resp.refused(), latencyMs));
        return resp;
    }

    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "anonymous";
    }
}
