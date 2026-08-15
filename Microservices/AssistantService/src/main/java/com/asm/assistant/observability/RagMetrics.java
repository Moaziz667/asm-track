package com.asm.assistant.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Central RAG metrics, published through Micrometer to whatever registry is present. The service
 * already exposes {@code /actuator/prometheus} (Actuator + micrometer-registry-prometheus), so these
 * meters are scrape-ready with no extra infrastructure — a Prometheus/Grafana stack can be pointed at
 * the endpoint later without touching the service.
 *
 * <p>Meters are pre-registered at startup so they appear (at 0) on the very first scrape, rather than
 * only after the first request.
 */
@Component
public class RagMetrics {

    private final MeterRegistry registry;

    public final Timer answerTimer;
    public final Timer retrievalTimer;
    public final Timer llmTimer;
    public final Timer embeddingTimer;
    public final Timer toolTimer;

    public RagMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.answerTimer = timer("assistant.answer.duration", "End-to-end answer latency");
        this.retrievalTimer = timer("assistant.retrieval.duration", "Hybrid retrieval latency");
        this.llmTimer = timer("assistant.llm.duration", "LLM call latency");
        this.embeddingTimer = timer("assistant.embedding.duration", "Embedding call latency");
        this.toolTimer = timer("assistant.tool.duration", "Live tool call latency");
    }

    /** Pre-register the tagged counters (register only, never increment) so series exist at 0. */
    @PostConstruct
    void warmUp() {
        for (String route : new String[]{"RAG", "LIVE_API", "DETERMINISTIC"}) {
            for (String outcome : new String[]{"grounded", "refused", "degraded"}) {
                answers(route, outcome);
            }
        }
        injectionCounter();
        rateLimitCounter();
    }

    private Timer timer(String name, String desc) {
        return Timer.builder(name).description(desc)
                .publishPercentiles(0.5, 0.95)
                .register(registry);
    }

    public Counter answers(String route, String outcome) {
        return Counter.builder("assistant.answers")
                .description("Answers by route and outcome")
                .tag("route", route).tag("outcome", outcome)
                .register(registry);
    }

    public void recordAnswer(String route, boolean grounded, boolean refused, boolean degraded) {
        String outcome = refused ? "refused" : degraded ? "degraded" : grounded ? "grounded" : "grounded";
        answers(route, outcome).increment();
    }

    public void injectionFlagged() { injectionCounter().increment(); }
    public void rateLimited() { rateLimitCounter().increment(); }

    public void toolCall(String domain, boolean available) {
        Counter.builder("assistant.tool.calls")
                .tag("domain", domain).tag("available", String.valueOf(available))
                .register(registry).increment();
    }

    private Counter injectionCounter() {
        return Counter.builder("assistant.injection.flagged")
                .description("Queries flagged as possible prompt injection").register(registry);
    }

    private Counter rateLimitCounter() {
        return Counter.builder("assistant.ratelimit.rejected")
                .description("Requests rejected by the assistant rate limiter").register(registry);
    }

    /** Time a supplier on the given timer. */
    public <T> T record(Timer timer, Supplier<T> action) {
        long start = System.nanoTime();
        try {
            return action.get();
        } finally {
            timer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}
