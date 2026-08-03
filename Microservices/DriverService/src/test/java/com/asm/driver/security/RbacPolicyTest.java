package com.asm.driver.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authorization rules, pinned.
 *
 * <p>This is the one piece of DriverService where being wrong is not a bug but an incident: a rule
 * that accidentally matches too early hands one tenant's drivers to another company's dispatcher.
 * The policy file is generated from the gateway's copy, so the risk is not someone editing this
 * class carelessly — it is a well-meaning edit to the shared JSON reordering the rules.
 */
class RbacPolicyTest {

    private static final Set<String> NOBODY = Set.of();

    @Test
    void deniesAPathNoRuleCovers() {
        // Fail-closed is the whole design. If a new endpoint ships without a rule, it must be
        // unreachable rather than open — a 403 gets reported, a silent hole does not.
        assertThat(RbacPolicy.isAuthorized("/api/v1/something-nobody-declared", NOBODY, HttpMethod.GET)).isFalse();
        assertThat(RbacPolicy.isAuthorized("/api/v1/something-nobody-declared",
                Set.of("ADMIN", "perm:driver:manage"), HttpMethod.GET)).isFalse();
    }

    @Test
    void deniesEveryCallerWithNoRoles() {
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/drivers", NOBODY, HttpMethod.GET)).isFalse();
        assertThat(RbacPolicy.isAuthorized("/api/v1/driver/route", NOBODY, HttpMethod.GET)).isFalse();
    }

    @Test
    void lettsADriverReachTheDriverApi() {
        assertThat(RbacPolicy.isAuthorized("/api/v1/driver/route", Set.of("DRIVER"), HttpMethod.GET)).isTrue();
    }

    @Test
    void keepsAdminsOutOfTheDriverApiAndDriversOutOfAdmin() {
        // Not a formality: the driver app and the back-office authenticate against the same realm,
        // so only the role separates them.
        assertThat(RbacPolicy.isAuthorized("/api/v1/driver/route", Set.of("ADMIN"), HttpMethod.GET)).isFalse();
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/drivers", Set.of("DRIVER"), HttpMethod.GET)).isFalse();
    }

    @Test
    void separatesReadingDriversFromChangingThem() {
        Set<String> viewer = Set.of("perm:driver:view");
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/drivers", viewer, HttpMethod.GET)).isTrue();
        // Same path, different verb: a viewer must not be able to create or suspend a driver.
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/drivers", viewer, HttpMethod.POST)).isFalse();
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/drivers", viewer, HttpMethod.DELETE)).isFalse();

        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/drivers",
                Set.of("perm:driver:manage"), HttpMethod.POST)).isTrue();
    }

    @Test
    void honoursTheOrderOfRules() {
        // /api/v1/admin/me is declared before the catch-all /api/v1/admin/ prefix. If the order is
        // ever lost, a logged-in user with no dispatch permission stops being able to read their
        // own profile — and the app looks broken for a reason nobody connects to a policy edit.
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/me", Set.of("DRIVER"), HttpMethod.GET)).isTrue();
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/stats", Set.of("DRIVER"), HttpMethod.GET)).isFalse();
    }

    @Test
    void acceptsAnyOneOfTheAlternativePermissions() {
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/companies/me",
                Set.of("perm:dispatch:operate"), HttpMethod.GET)).isTrue();
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/companies/me",
                Set.of("perm:company:manage"), HttpMethod.GET)).isTrue();
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/companies/me",
                Set.of("perm:report:view"), HttpMethod.GET)).isFalse();
    }

    @Test
    void comparesRoleNamesWithoutCaseButPermissionsExactly() {
        // Roles arrive from Keycloak in whatever case the realm was configured with, so the rule
        // compares them loosely. Permissions are machine-generated strings and are not negotiable —
        // a near-miss must fail rather than be guessed at.
        assertThat(RbacPolicy.isAuthorized("/api/v1/driver/route", Set.of("driver"), HttpMethod.GET)).isTrue();
        assertThat(RbacPolicy.isAuthorized("/api/v1/admin/drivers",
                Set.of("PERM:DRIVER:VIEW"), HttpMethod.GET)).isFalse();
    }
}
