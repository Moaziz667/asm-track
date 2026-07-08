package com.asm.delivery.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * Enterprise freshness for analytics: a {@link ShallowEtagHeaderFilter} scoped to the <b>stats</b>
 * endpoints so identical payloads collapse to a {@code 304 Not Modified} (zero body) on the poll.
 *
 * <p>Deliberately NOT applied to the live ops endpoints ({@code /ops/*}) — those carry
 * {@code Cache-Control: no-store} and must never be buffered/cached (hard freshness for operational
 * state). Cache-Control on the stats responses is set per-endpoint (stale-while-revalidate).
 */
@Configuration
public class AnalyticsCacheConfig {

    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> analyticsEtagFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> reg =
                new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        reg.addUrlPatterns("/api/admin/reports/*", "/api/admin/deliveries/stats");
        reg.setName("analyticsEtagFilter");
        return reg;
    }
}
