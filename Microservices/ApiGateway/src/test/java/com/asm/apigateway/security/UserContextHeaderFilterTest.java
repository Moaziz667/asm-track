package com.asm.apigateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RBAC authorization matrix (role/perm-set × endpoint → allow/deny) over the canonical {@link RbacPolicy}
 * that {@code isAuthorized} delegates to. This is the drift guard: any change to the policy that breaks an
 * expected decision fails here.
 *
 * <p>Perm-sets mirror the Keycloak role composites as they arrive expanded in {@code realm_access.roles}.
 * There is no ADMIN superuser bypass — ADMIN is allowed purely because its composite grants every perm:*.
 * MANAGER is configured "full like dispatcher", so its perm-set equals DISPATCHER's.
 */
class UserContextHeaderFilterTest {

    private final UserContextHeaderFilter filter = new UserContextHeaderFilter();

    private static final Set<String> ADMIN = Set.of("ADMIN",
            "perm:user:manage", "perm:driver:manage", "perm:driver:view", "perm:route:view",
            "perm:route:manage", "perm:route:validate", "perm:delivery:view", "perm:delivery:manage",
            "perm:dispatch:operate", "perm:erp:sync", "perm:erp:config", "perm:report:view",
            "perm:audit:view", "perm:settings:manage", "perm:company:manage");

    private static final Set<String> DISPATCHER = Set.of("DISPATCHER",
            "perm:driver:view", "perm:route:view", "perm:route:manage", "perm:route:validate",
            "perm:delivery:view", "perm:delivery:manage", "perm:dispatch:operate", "perm:erp:sync",
            "perm:report:view", "perm:audit:view");

    // MANAGER = full-like-dispatcher (same composite). Kept as its own constant so a future divergence
    // is a one-line edit here + explicit assertions, never a silent drift.
    private static final Set<String> MANAGER = Set.of("MANAGER",
            "perm:driver:view", "perm:route:view", "perm:route:manage", "perm:route:validate",
            "perm:delivery:view", "perm:delivery:manage", "perm:dispatch:operate", "perm:erp:sync",
            "perm:report:view", "perm:audit:view");

    private static final Set<String> DRIVER = Set.of("DRIVER");
    private static final Set<String> CLIENT = Set.of("CLIENT");

    private boolean auth(Set<String> roles, String path, HttpMethod m) {
        return filter.isAuthorized(path, roles, m);
    }

    @Test
    void admin_reaches_the_whole_admin_surface_via_perms_not_a_bypass() {
        assertTrue(auth(ADMIN, "/api/v1/admin/users", HttpMethod.POST));
        assertTrue(auth(ADMIN, "/api/v1/admin/companies/x", HttpMethod.PUT));
        assertTrue(auth(ADMIN, "/api/v1/admin/erp/import", HttpMethod.POST));
        assertTrue(auth(ADMIN, "/api/v1/admin/reports/settings", HttpMethod.PUT));
        assertTrue(auth(ADMIN, "/api/v1/admin/drivers", HttpMethod.POST));   // driver:manage
        assertTrue(auth(ADMIN, "/api/v1/admin/vehicles", HttpMethod.POST));  // driver:manage
        assertTrue(auth(ADMIN, "/api/v1/depots", HttpMethod.POST));          // route:manage
    }

    @Test
    void no_admin_superuser_bypass_on_client_or_driver_scoped_paths() {
        // ADMIN holds no CLIENT/DRIVER role, so it is correctly denied client/driver-only endpoints
        // (least privilege). Previously an ADMIN short-circuit let it through — that bypass is gone.
        assertFalse(auth(ADMIN, "/api/v1/orders/1", HttpMethod.GET));
        assertFalse(auth(ADMIN, "/api/v1/driver/me", HttpMethod.GET));
    }

    @Test
    void dispatcher_views_drivers_and_vehicles_but_cannot_manage_them() {
        assertTrue(auth(DISPATCHER, "/api/v1/admin/drivers", HttpMethod.GET));
        assertFalse(auth(DISPATCHER, "/api/v1/admin/drivers", HttpMethod.POST));    // no driver:manage
        assertFalse(auth(DISPATCHER, "/api/v1/admin/drivers/123", HttpMethod.PUT));
        assertTrue(auth(DISPATCHER, "/api/v1/admin/vehicles", HttpMethod.GET));
        assertFalse(auth(DISPATCHER, "/api/v1/admin/vehicles", HttpMethod.POST));   // no driver:manage
    }

    @Test
    void dispatcher_cannot_manage_users_settings_or_company() {
        assertFalse(auth(DISPATCHER, "/api/v1/admin/users", HttpMethod.GET));       // no user:manage
        assertFalse(auth(DISPATCHER, "/api/v1/admin/reports/settings", HttpMethod.PUT));
        assertFalse(auth(DISPATCHER, "/api/v1/admin/companies/abc", HttpMethod.PUT));
    }

    @Test
    void dispatcher_operates_admin_surface_erp_reports_and_company_me() {
        assertTrue(auth(DISPATCHER, "/api/v1/admin/fleet/drivers", HttpMethod.GET)); // catch-all → dispatch:operate
        assertTrue(auth(DISPATCHER, "/api/v1/admin/returns/kpi", HttpMethod.GET));
        assertTrue(auth(DISPATCHER, "/api/v1/admin/erp/sync", HttpMethod.POST));
        assertTrue(auth(DISPATCHER, "/api/v1/admin/ops/overview", HttpMethod.GET));
        assertTrue(auth(DISPATCHER, "/api/v1/admin/companies/me", HttpMethod.GET));
    }

    @Test
    void depots_and_zones_split_read_from_write() {
        assertTrue(auth(DISPATCHER, "/api/v1/depots", HttpMethod.GET));             // route:view
        assertTrue(auth(DISPATCHER, "/api/v1/zones/9", HttpMethod.GET));
        assertTrue(auth(DISPATCHER, "/api/v1/depots", HttpMethod.POST));            // route:manage
        // A hypothetical view-only principal (route:view, no route:manage) can read but not write.
        Set<String> viewer = Set.of("perm:route:view");
        assertTrue(auth(viewer, "/api/v1/depots", HttpMethod.GET));
        assertFalse(auth(viewer, "/api/v1/depots", HttpMethod.POST));
        assertFalse(auth(viewer, "/api/v1/zones/9", HttpMethod.PUT));
    }

    @Test
    void manager_matches_dispatcher_full_access() {
        assertTrue(auth(MANAGER, "/api/v1/admin/drivers", HttpMethod.GET));
        assertTrue(auth(MANAGER, "/api/v1/admin/fleet/drivers", HttpMethod.GET));
        assertTrue(auth(MANAGER, "/api/v1/admin/routes", HttpMethod.POST));         // dispatch:operate
        assertTrue(auth(MANAGER, "/api/v1/depots", HttpMethod.POST));               // route:manage
        assertFalse(auth(MANAGER, "/api/v1/admin/drivers", HttpMethod.POST));       // no driver:manage
        assertFalse(auth(MANAGER, "/api/v1/admin/users", HttpMethod.GET));          // no user:manage
    }

    @Test
    void client_and_driver_paths_stay_role_scoped() {
        assertTrue(auth(CLIENT, "/api/v1/orders/1", HttpMethod.GET));
        assertFalse(auth(CLIENT, "/api/v1/admin/routes", HttpMethod.GET));
        assertTrue(auth(DRIVER, "/api/v1/driver/me", HttpMethod.GET));
        assertTrue(auth(DRIVER, "/api/v1/deliveries/5", HttpMethod.GET));
        assertFalse(auth(DRIVER, "/api/v1/admin/routes", HttpMethod.GET));
    }

    @Test
    void own_profile_is_any_authenticated_and_unprivileged_is_denied() {
        assertTrue(auth(Set.of(), "/api/v1/admin/me", HttpMethod.GET));            // self-profile
        assertFalse(auth(Set.of(), "/api/v1/admin/routes", HttpMethod.GET));
        assertFalse(auth(Set.of(), "/api/v1/depots", HttpMethod.GET));
    }
}
