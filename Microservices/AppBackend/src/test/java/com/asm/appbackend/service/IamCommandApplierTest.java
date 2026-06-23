package com.asm.appbackend.service;

import com.asm.appbackend.client.KeycloakAdminClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.mockito.Mockito.*;

/** Verifies each IAM op routes to the correct Keycloak operation with the right arguments. */
@ExtendWith(MockitoExtension.class)
class IamCommandApplierTest {

    @Mock KeycloakAdminClient kc;
    @InjectMocks IamCommandApplier applier;

    @Test
    void provision_creates_user_with_phone_and_no_password() {
        applier.apply(IamCommandApplier.PROVISION, Map.of(
                "appUserId", "u1", "email", "d@x.com", "role", "DRIVER", "name", "Jane Doe", "phone", "555"));
        verify(kc).createUser("d@x.com", "DRIVER", "u1", null, "Jane Doe", "555");
    }

    @Test
    void update_email_passes_old_and_new() {
        applier.apply(IamCommandApplier.UPDATE_EMAIL, Map.of(
                "appUserId", "u1", "oldEmail", "a@x.com", "email", "b@x.com"));
        verify(kc).updateUserEmail("u1", "a@x.com", "b@x.com");
    }

    @Test
    void update_name_and_role_and_logout_and_delete_route_correctly() {
        applier.apply(IamCommandApplier.UPDATE_NAME, Map.of("appUserId", "u1", "email", "a@x.com", "name", "New Name"));
        verify(kc).updateUserName("u1", "a@x.com", "New Name");

        applier.apply(IamCommandApplier.SET_ROLE, Map.of("appUserId", "u1", "email", "a@x.com", "role", "MANAGER"));
        verify(kc).setUserRole("u1", "a@x.com", "MANAGER");

        applier.apply(IamCommandApplier.LOGOUT, Map.of("appUserId", "u1"));
        verify(kc).forceLogout("u1");

        applier.apply(IamCommandApplier.DELETE, Map.of("appUserId", "u1"));
        verify(kc).deleteUser("u1");
    }

    @Test
    void set_enabled_coerces_boolean() {
        applier.apply(IamCommandApplier.SET_ENABLED, Map.of("appUserId", "u1", "enabled", true));
        verify(kc).setUserEnabled("u1", true);
        applier.apply(IamCommandApplier.SET_ENABLED, Map.of("appUserId", "u1", "enabled", false));
        verify(kc).setUserEnabled("u1", false);
    }

    @Test
    void unknown_op_and_null_op_are_noops() {
        applier.apply("IAM_NOPE", Map.of("appUserId", "u1"));
        applier.apply(null, Map.of("appUserId", "u1"));
        verifyNoInteractions(kc);
    }
}
