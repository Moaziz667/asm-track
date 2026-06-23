package com.asm.apigateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Authorization matrix for the RBAC gateway. Asserts the permission-based {@code isAuthorized} table
 * preserves the pre-RBAC role behavior: admin = superuser, dispatcher can view (not manage) drivers,
 * manager is read-only on routes/deliveries/reports, client/driver paths stay role-scoped.
 */
class UserContextHeaderFilterTest {

    private final UserContextHeaderFilter filter = new UserContextHeaderFilter();

    // Composite expansions as they arrive in realm_access.roles.
    private static final Set<String> ADMIN = Set.of("ADMIN",
            "perm:user:manage", "perm:driver:manage", "perm:driver:view", "perm:route:view",
            "perm:route:manage", "perm:route:validate", "perm:delivery:view", "perm:delivery:manage",
            "perm:dispatch:operate", "perm:erp:sync", "perm:erp:config", "perm:report:view",
            "perm:audit:view", "perm:settings:manage", "perm:company:manage");

    private static final Set<String> DISPATCHER = Set.of("DISPATCHER",
            "perm:driver:view", "perm:route:view", "perm:route:manage", "perm:route:validate",
            "perm:delivery:view", "perm:delivery:manage", "perm:dispatch:operate", "perm:erp:sync",
            "perm:report:view", "perm:audit:view");

    private static final Set<String> MANAGER = Set.of("MANAGER",
            "perm:route:view", "perm:delivery:view", "perm:report:view");

    private static final Set<String> DRIVER = Set.of("DRIVER");
    private static final Set<String> CLIENT = Set.of("CLIENT");

    private boolean auth(Set<String> roles, String path, HttpMethod m) {
        return filter.isAuthorized(path, roles, m);
    }

    @Test
    void admin_is_superuser() {
        assertTrue(auth(ADMIN, "/api/admin/users", HttpMethod.POST));
        assertTrue(auth(ADMIN, "/api/admin/companies/x", HttpMethod.PUT));
        assertTrue(auth(ADMIN, "/api/admin/erp/import", HttpMethod.POST));
        assertTrue(auth(ADMIN, "/api/admin/reports/settings", HttpMethod.PUT));
    }

    @Test
    void dispatcher_views_drivers_but_cannot_manage_them() {
        assertTrue(auth(DISPATCHER, "/api/admin/drivers", HttpMethod.GET));
        assertFalse(auth(DISPATCHER, "/api/admin/drivers", HttpMethod.POST));   // no driver:manage
        assertFalse(auth(DISPATCHER, "/api/admin/drivers/123", HttpMethod.PUT));
    }

    @Test
    void dispatcher_cannot_manage_users_or_settings_or_company() {
        assertFalse(auth(DISPATCHER, "/api/admin/users", HttpMethod.GET));      // no user:manage
        assertFalse(auth(DISPATCHER, "/api/admin/reports/settings", HttpMethod.PUT));
        assertFalse(auth(DISPATCHER, "/api/admin/companies/abc", HttpMethod.PUT));
    }

    @Test
    void dispatcher_can_operate_admin_surface_and_erp_and_company_me() {
        assertTrue(auth(DISPATCHER, "/api/admin/routes", HttpMethod.POST));     // catch-all → dispatch:operate
        assertTrue(auth(DISPATCHER, "/api/admin/erp/sync", HttpMethod.POST));   // erp:sync
        assertTrue(auth(DISPATCHER, "/api/admin/companies/me", HttpMethod.GET));
    }

    @Test
    void manager_is_read_only_on_routes_deliveries_reports() {
        assertTrue(auth(MANAGER, "/api/admin/routes", HttpMethod.GET));
        assertTrue(auth(MANAGER, "/api/admin/deliveries", HttpMethod.GET));
        assertTrue(auth(MANAGER, "/api/admin/stats", HttpMethod.GET));
        assertFalse(auth(MANAGER, "/api/admin/routes", HttpMethod.POST));       // no route mutation
        assertFalse(auth(MANAGER, "/api/admin/users", HttpMethod.GET));
        assertFalse(auth(MANAGER, "/api/admin/companies/me", HttpMethod.GET));  // not admin-tier
    }

    @Test
    void client_and_driver_paths_stay_role_scoped() {
        assertTrue(auth(CLIENT, "/api/orders/1", HttpMethod.GET));
        assertFalse(auth(CLIENT, "/api/admin/routes", HttpMethod.GET));
        assertTrue(auth(DRIVER, "/api/driver/me", HttpMethod.GET));
        assertTrue(auth(DRIVER, "/api/deliveries/5", HttpMethod.GET));
        assertFalse(auth(DRIVER, "/api/admin/routes", HttpMethod.GET));
    }

    @Test
    void unknown_or_unprivileged_is_denied() {
        assertFalse(auth(Set.of(), "/api/admin/routes", HttpMethod.GET));
        assertFalse(auth(MANAGER, "/api/admin/erp/sync", HttpMethod.POST));     // manager lacks erp:sync
    }
}
