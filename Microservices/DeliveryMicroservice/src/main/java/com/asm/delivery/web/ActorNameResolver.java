package com.asm.delivery.web;

import com.asm.delivery.entity.DeliveryStatusHistory;
import com.asm.delivery.entity.Role;
import com.asm.delivery.transport.AdminUserDTO;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import com.asm.delivery.transport.adapters.AdminUserInternalClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Single source of truth for turning a {@code DeliveryStatusHistory.changedBy} value into the
 * display name shown after "par " in the activity timeline. Replaces four duplicated
 * {@code resolveActorName*} copies that had drifted (and that masked every admin/dispatcher behind
 * a hardcoded "Dispatching" literal).
 *
 * <p>The stored value is one of: the literal {@code SYSTEM}; a driver UUID (resolved via
 * DriverService); an admin/dispatcher UUID (resolved via AppBackend); or — for legacy rows — a
 * literal name. Names are resolved server-side from each service's own DB, so attribution works
 * regardless of what the JWT {@code name} claim happened to carry when the row was written.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ActorNameResolver {

    private final TransportPort transportPort;
    private final AdminUserInternalClient adminClient;

    /**
     * Batch-resolve every actor name referenced by the given history rows in at most two remote
     * calls (drivers + admins). Returns a map keyed by the stored {@code changedBy} value.
     */
    public Map<String, String> prefetch(Collection<DeliveryStatusHistory> histories) {
        Set<String> driverIds = new HashSet<>();
        Set<String> adminIds = new HashSet<>();
        for (DeliveryStatusHistory h : histories) {
            String cb = h.getChangedBy();
            if (cb == null || !isUuid(cb)) continue;
            Role r = h.getChangedByRole();
            if (r == Role.DRIVER) {
                driverIds.add(cb);
            } else if (r == Role.ADMIN || r == Role.DISPATCHER || r == Role.MANAGER) {
                adminIds.add(cb);
            }
        }

        Map<String, String> names = new HashMap<>();
        for (String id : driverIds) {
            try {
                DriverDTO d = transportPort.getDriver(id);
                if (d != null && d.getName() != null) names.put(id, d.getName());
            } catch (Exception ignored) { /* fall back to short id */ }
        }
        if (!adminIds.isEmpty()) {
            try {
                List<AdminUserDTO> admins = adminClient.getByIds(new ArrayList<>(adminIds));
                if (admins != null) {
                    for (AdminUserDTO a : admins) {
                        if (a.getId() != null && a.getName() != null) names.put(a.getId(), a.getName());
                    }
                }
            } catch (Exception e) {
                log.warn("Admin actor-name prefetch failed (timeline will fall back to short id): {}", e.getMessage());
            }
        }
        return names;
    }

    /**
     * Resolve a single actor. {@code names} should come from {@link #prefetch}; values not present
     * fall back to a short uppercased id rather than a generic role label.
     */
    public String resolve(String changedBy, Role role, Map<String, String> names) {
        if (changedBy == null) return null;
        if ("SYSTEM".equalsIgnoreCase(changedBy)) return "Système";
        if (!isUuid(changedBy)) return changedBy; // legacy rows already store a literal name
        String resolved = names != null ? names.get(changedBy) : null;
        if (resolved != null) return resolved;
        return changedBy.substring(0, Math.min(8, changedBy.length())).toUpperCase();
    }

    private static boolean isUuid(String s) {
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
