package com.asm.apigateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RBAC evaluator, driven by the canonical {@code rbac-policy.json} — the single source of truth for
 * path→permission authorization across the whole platform.
 *
 * <p>The gateway owns the file and loads it here at class-init; the same JSON is synced into every
 * service (see {@code Microservices/sync-rbac-policy.py}) so all layers evaluate identical rules. There
 * is no ADMIN superuser bypass — ADMIN is allowed because its Keycloak composite grants every perm:*.
 *
 * <p>Rules are ordered: first rule whose method + path match decides; no match = deny (fail-closed).
 */
public final class RbacPolicy {

    private RbacPolicy() {}

    /** One authorization rule as loaded from JSON. {@code require} is a small predicate tree. */
    public record Rule(List<String> methods, String pathExact, String pathPrefix, Map<String, Object> require) {}

    private static final List<Rule> RULES = load();

    @SuppressWarnings("unchecked")
    private static List<Rule> load() {
        try (var in = new ClassPathResource("rbac-policy.json").getInputStream()) {
            Map<String, Object> doc = new ObjectMapper().readValue(in, Map.class);
            List<Map<String, Object>> raw = (List<Map<String, Object>>) doc.get("rules");
            return raw.stream()
                    .map(r -> new Rule(
                            (List<String>) r.get("methods"),
                            (String) r.get("pathExact"),
                            (String) r.get("pathPrefix"),
                            (Map<String, Object>) r.get("require")))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load rbac-policy.json — refusing to start with no policy", e);
        }
    }

    /** First matching rule decides; unmatched paths are denied (fail-closed). */
    public static boolean isAuthorized(String path, Set<String> roles, HttpMethod method) {
        for (Rule r : RULES) {
            if (methodMatches(r, method) && pathMatches(r, path)) {
                return evaluate(r.require(), roles);
            }
        }
        return false;
    }

    private static boolean methodMatches(Rule r, HttpMethod m) {
        for (String x : r.methods()) {
            if ("*".equals(x) || x.equalsIgnoreCase(m.name())) return true;
        }
        return false;
    }

    private static boolean pathMatches(Rule r, String path) {
        if (r.pathExact() != null) return path.equals(r.pathExact());
        if (r.pathPrefix() != null) return path.startsWith(r.pathPrefix());
        return false;
    }

    @SuppressWarnings("unchecked")
    private static boolean evaluate(Map<String, Object> require, Set<String> roles) {
        if (Boolean.TRUE.equals(require.get("authenticated"))) return true;
        Object perm = require.get("perm");
        if (perm != null) return roles.contains(perm.toString());
        Object role = require.get("role");
        if (role != null) {
            String r = role.toString();
            return roles.stream().anyMatch(x -> x.equalsIgnoreCase(r));
        }
        Object anyPerm = require.get("anyPerm");
        if (anyPerm instanceof List<?> l) return l.stream().anyMatch(p -> roles.contains(p.toString()));
        Object anyOf = require.get("anyOf");
        if (anyOf instanceof List<?> l) return l.stream().anyMatch(o -> evaluate((Map<String, Object>) o, roles));
        return false;
    }
}
