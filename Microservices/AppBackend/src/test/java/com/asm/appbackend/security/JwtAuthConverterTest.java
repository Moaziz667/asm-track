package com.asm.appbackend.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a token becomes an identity — and, more importantly, a tenant.
 *
 * <p>The company id extracted here decides which PostgreSQL schema the request reads from. Get it
 * wrong and one customer is served another customer's data, with no error anywhere: the query
 * succeeds, it just runs against the wrong schema. That failure mode is why this conversion is
 * worth pinning rather than trusting to a reading of the code.
 */
class JwtAuthConverterTest {

    private final JwtAuthConverter converter = new JwtAuthConverter();

    private static final UUID ALPHA = UUID.fromString("54ed4906-3009-4a22-888e-8717f3d23178");

    private static Jwt.Builder token() {
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .subject("keycloak-subject");
    }

    private UserPrincipal principalOf(Jwt jwt) {
        return (UserPrincipal) converter.convert(jwt).getPrincipal();
    }

    private List<String> authoritiesOf(Jwt jwt) {
        return converter.convert(jwt).getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void readsTheTenantFromTheOrganizationClaim() {
        Jwt jwt = token()
                .claim("organization", Map.of("tenant-alpha", Map.of("id", ALPHA.toString())))
                .build();
        assertThat(principalOf(jwt).companyId()).isEqualTo(ALPHA);
    }

    @Test
    void prefersAnExplicitOrgIdClaim() {
        UUID other = UUID.randomUUID();
        Jwt jwt = token()
                .claim("org_id", other.toString())
                .claim("organization", Map.of("tenant-alpha", Map.of("id", ALPHA.toString())))
                .build();
        assertThat(principalOf(jwt).companyId()).isEqualTo(other);
    }

    @Test
    void leavesTheTenantUnsetRatherThanGuessingIt() {
        // No tenant at all is the safe answer: downstream falls back to the public schema, which
        // holds no customer data. Inventing one would silently pick a victim.
        assertThat(principalOf(token().build()).companyId()).isNull();
        assertThat(principalOf(token().claim("org_id", "not-a-uuid").build()).companyId()).isNull();
        assertThat(principalOf(token().claim("organization", Map.of()).build()).companyId()).isNull();
        assertThat(principalOf(token()
                .claim("organization", Map.of("tenant-alpha", Map.of("id", "not-a-uuid")))
                .build()).companyId()).isNull();
    }

    @Test
    void keepsPermissionsAndPrefixesCoarseRoles() {
        Jwt jwt = token()
                .claim("realm_access", Map.of("roles", List.of("ADMIN", "perm:driver:view")))
                .build();
        assertThat(authoritiesOf(jwt)).containsExactlyInAnyOrder("ROLE_ADMIN", "perm:driver:view");
    }

    @Test
    void dropsRolesItDoesNotRecognise() {
        // Keycloak realms carry housekeeping roles like default-roles-asm and uma_authorization.
        // Turning those into authorities would put noise in every authorization decision.
        Jwt jwt = token()
                .claim("realm_access", Map.of("roles",
                        List.of("ADMIN", "default-roles-asm", "uma_authorization", "offline_access")))
                .build();
        assertThat(authoritiesOf(jwt)).containsExactly("ROLE_ADMIN");
    }

    @Test
    void picksTheStrongestRoleAsTheDominantOne() {
        Jwt jwt = token()
                .claim("realm_access", Map.of("roles", List.of("DRIVER", "ADMIN", "MANAGER")))
                .build();
        assertThat(principalOf(jwt).role()).isEqualTo("ADMIN");
    }

    @Test
    void fallsBackToClientForATokenCarryingNoKnownRole() {
        assertThat(principalOf(token().build()).role()).isEqualTo("CLIENT");
    }

    @Test
    void identifiesTheUserByTheApplicationIdWhenThereIsOne() {
        // The Keycloak subject and the row in admin_user are different identifiers. Auditing and
        // ownership checks use the application one, so it wins when present.
        String appUserId = UUID.randomUUID().toString();
        assertThat(principalOf(token().claim("app_user_id", appUserId).build()).userId()).isEqualTo(appUserId);
        assertThat(principalOf(token().build()).userId()).isEqualTo("keycloak-subject");
    }
}
