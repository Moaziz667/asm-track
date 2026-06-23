package com.asm.appbackend.client;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Display-name → Keycloak firstName/lastName split (drives the JWT `name` claim → attribution). */
class KeycloakAdminClientNameFieldsTest {

    @Test
    void splits_first_and_rest() {
        Map<String, Object> m = KeycloakAdminClient.nameFields("System Admin");
        assertEquals("System", m.get("firstName"));
        assertEquals("Admin", m.get("lastName"));
    }

    @Test
    void multi_word_last_name() {
        Map<String, Object> m = KeycloakAdminClient.nameFields("Mohamed Aziz Hadjkacem");
        assertEquals("Mohamed", m.get("firstName"));
        assertEquals("Aziz Hadjkacem", m.get("lastName"));
    }

    @Test
    void single_token_has_empty_last_name() {
        Map<String, Object> m = KeycloakAdminClient.nameFields("Hadjkacem");
        assertEquals("Hadjkacem", m.get("firstName"));
        assertEquals("", m.get("lastName"));
    }

    @Test
    void blank_or_null_yields_empty_map() {
        assertTrue(KeycloakAdminClient.nameFields(null).isEmpty());
        assertTrue(KeycloakAdminClient.nameFields("   ").isEmpty());
    }
}
