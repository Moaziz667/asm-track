package com.asm.assistant.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RBAC evaluator, driven by the canonical {@code rbac-policy.json}. The gateway owns the source of
 * truth; sync-rbac-policy.py bundles an identical copy here so this service authorizes on the exact
 * same rules — one policy, no per-service drift. Kept as data + a tiny evaluator (this class is a
 * verbatim twin of the gateway's; only the DATA — the JSON — matters, and it is single-source).
 *
 * <p>Ordered: first rule whose method + path match decides; no match = deny (fail-closed).
 */
public final class RbacPolicy {

    private RbacPolicy() {}

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

    /**
     * @param roles the principal's realm roles + perm:* (ROLE_ prefix already stripped by the caller,
     *              so it matches the gateway's raw realm_access.roles form).
     */
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
