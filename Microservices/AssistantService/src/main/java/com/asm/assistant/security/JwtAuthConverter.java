package com.asm.assistant.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Maps a Keycloak realm JWT onto Spring authorities, identically to the other ASM services: coarse
 * realm roles become {@code ROLE_*}, {@code perm:*} permission roles pass through unchanged, and the
 * caller's company is read from {@code org_id}/{@code organization} for the row-level tenant filter.
 */
public class JwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final List<String> ROLE_PRIORITY =
            List.of("ADMIN", "DISPATCHER", "MANAGER", "DRIVER", "CLIENT", "SERVICE");

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        List<String> roles = extractRoles(jwt);

        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(String::trim)
                .filter(r -> !r.isEmpty())
                .map(r -> r.startsWith("perm:")
                        ? new SimpleGrantedAuthority(r)
                        : (ROLE_PRIORITY.contains(r.toUpperCase())
                                ? new SimpleGrantedAuthority("ROLE_" + r.toUpperCase())
                                : null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        String dominantRole = roles.stream()
                .map(String::toUpperCase)
                .filter(ROLE_PRIORITY::contains)
                .min(Comparator.comparingInt(ROLE_PRIORITY::indexOf))
                .orElse("CLIENT");

        String appUserId = jwt.getClaimAsString("app_user_id");
        String principalId = appUserId != null ? appUserId : jwt.getSubject();
        String displayName = jwt.getClaimAsString("name");
        UUID companyId = extractCompanyId(jwt);

        UserPrincipal principal = new UserPrincipal(principalId, dominantRole, displayName, companyId);
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    @SuppressWarnings("unchecked")
    private UUID extractCompanyId(Jwt jwt) {
        String orgId = jwt.getClaimAsString("org_id");
        if (orgId != null) {
            try { return UUID.fromString(orgId); } catch (IllegalArgumentException ignored) {}
        }
        Map<String, Object> orgs = jwt.getClaim("organization");
        if (orgs != null && !orgs.isEmpty()) {
            Object first = orgs.values().iterator().next();
            if (first instanceof Map) {
                Object id = ((Map<?, ?>) first).get("id");
                if (id != null) {
                    try { return UUID.fromString(id.toString()); } catch (IllegalArgumentException ignored) {}
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null) return Collections.emptyList();
        List<String> roles = (List<String>) realmAccess.get("roles");
        return roles != null ? roles : Collections.emptyList();
    }
}
