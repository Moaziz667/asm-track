package com.asm.delivery.web;

import com.asm.delivery.entity.Role;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** resolve() turns a stored changedBy value into a display name. Deps unused by resolve() → null. */
class ActorNameResolverTest {

    private final ActorNameResolver resolver = new ActorNameResolver(null, null);

    @Test
    void system_literal_localizes() {
        assertEquals("Système", resolver.resolve("SYSTEM", Role.SYSTEM, Map.of()));
        assertEquals("Système", resolver.resolve("system", Role.SYSTEM, Map.of()));
    }

    @Test
    void null_changedBy_is_null() {
        assertNull(resolver.resolve(null, Role.ADMIN, Map.of()));
    }

    @Test
    void uuid_resolves_from_prefetched_map() {
        String id = UUID.randomUUID().toString();
        assertEquals("Chief Dispatcher", resolver.resolve(id, Role.DISPATCHER, Map.of(id, "Chief Dispatcher")));
    }

    @Test
    void uuid_not_in_map_falls_back_to_short_id_not_a_generic_label() {
        String id = "abcdef12-3456-7890-1234-567890abcdef";
        // Critically NOT "Dispatching" (the old hardcoded label) — a short, honest id stub instead.
        assertEquals("ABCDEF12", resolver.resolve(id, Role.ADMIN, Map.of()));
    }

    @Test
    void legacy_literal_name_passes_through() {
        assertEquals("Aziz", resolver.resolve("Aziz", Role.ADMIN, Map.of()));
    }
}
