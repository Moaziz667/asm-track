package com.asm.driver.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.*;
import java.util.stream.Collectors;

public class JwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final List<String> ROLE_PRIORITY =
            List.of("ADMIN", "DISPATCHER", "MANAGER", "DRIVER", "CLIENT", "SERVICE");

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        List<String> roles = extractRoles(jwt);

        List<SimpleGrantedAuthority> authorities = roles.stream()
                .filter(r -> ROLE_PRIORITY.contains(r.toUpperCase()))
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r.toUpperCase()))
                .collect(Collectors.toList());

        String dominantRole = roles.stream()
                .map(String::toUpperCase)
                .filter(ROLE_PRIORITY::contains)
                .min(Comparator.comparingInt(ROLE_PRIORITY::indexOf))
                .orElse("CLIENT");

        String appUserId = jwt.getClaimAsString("app_user_id");
        String principalId = appUserId != null ? appUserId : jwt.getSubject();

        UserPrincipal principal = new UserPrincipal(principalId, dominantRole);
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null) return Collections.emptyList();
        List<String> roles = (List<String>) realmAccess.get("roles");
        return roles != null ? roles : Collections.emptyList();
    }
}
