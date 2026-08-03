package com.asm.apigateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway's copy of the authorization rules, pinned.
 *
 * <p>Every service sits behind this evaluator, so a rule that matches too early here is not one
 * service's problem — it is the platform's. The same rules are also enforced inside the services,
 * but defence in depth only works if the outer layer is actually correct.
 */
class RbacPolicyTest {

    private static final Set<String> NOBODY = Set.of();

    @Test
    void deniesAPathNoRuleCovers() {
        // Fail-closed. A new endpoint that ships without a rule must be unreachable rather than
        // open: a 403 gets reported on day one, an open door does not.
        assertThat(RbacPolicy.isAuthorized("/api/v1/not-declared-anywhere", NOBODY, HttpMethod.GET)).isFalse();
        assertThat(RbacPolicy.isAuthorized("/api/v1/not-declared-anywhere",
                Set.of("ADMIN", "perm:company:manage"), HttpMethod.POST)).isFalse();
    }

    @Test
    void deniesAnUnauthenticatedCallerEverywhereThatMatters() {
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/drivers", NOBODY, HttpMethod.GET)).isFalse();
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/companies/", NOBODY, HttpMethod.POST)).isFalse();
        assertThat(RbacPolicy.isAuthorized("/api/v1/driver/route", NOBODY, HttpMethod.GET)).isFalse();
    }

    @Test
    void keepsTheDriverApiAndTheBackOfficeApart() {
        // Both authenticate against the same realm; only the role separates a phone in a van from
        // a dispatcher's browser.
        assertThat(RbacPolicy.isAuthorized("/api/v1/driver/route", Set.of("DRIVER"), HttpMethod.GET)).isTrue();
        assertThat(RbacPolicy.isAuthorized("/api/v1/driver/route", Set.of("ADMIN"), HttpMethod.GET)).isFalse();
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/stats", Set.of("DRIVER"), HttpMethod.GET)).isFalse();
    }

    @Test
    void separatesReadingFromWritingOnTheSamePath() {
        Set<String> viewer = Set.of("perm:driver:view");
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/drivers", viewer, HttpMethod.GET)).isTrue();
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/drivers", viewer, HttpMethod.POST)).isFalse();
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/vehicles", viewer, HttpMethod.DELETE)).isFalse();
    }

    @Test
    void grantsAdminThroughItsPermissionsRatherThanAsASuperuser() {
        // There is no ADMIN bypass in the evaluator: ADMIN works because its Keycloak composite
        // hands it every perm:*. Stripping the permissions must therefore strip the access — if this
        // ever passes with the role alone, someone has added a backdoor.
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/companies/",
                Set.of("ADMIN"), HttpMethod.POST)).isFalse();
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/companies/",
                Set.of("ADMIN", "perm:company:manage"), HttpMethod.POST)).isTrue();
    }

    @Test
    void honoursTheOrderOfRules() {
        // /api/v1/admin/me is declared before the catch-all /api/v1/admin/ prefix. Reordering the
        // JSON silently locks every non-dispatcher out of their own profile.
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/me", Set.of("DRIVER"), HttpMethod.GET)).isTrue();
    }

    @Test
    void acceptsAnyOneOfTheAlternativePermissions() {
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/companies/me",
                Set.of("perm:dispatch:operate"), HttpMethod.GET)).isTrue();
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/companies/me",
                Set.of("perm:report:view"), HttpMethod.GET)).isFalse();
    }

    @Test
    void treatsPermissionStringsAsExact() {
        // Permissions are machine-generated. A near-miss must fail rather than be guessed at.
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/drivers",
                Set.of("perm:driver:views"), HttpMethod.GET)).isFalse();
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/drivers",
                Set.of("PERM:DRIVER:VIEW"), HttpMethod.GET)).isFalse();
    }
}
