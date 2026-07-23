package com.asm.delivery.web;

import com.asm.delivery.entity.Role;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/**
 * Resolves the authenticated user behind the current request from the X-User-* headers the API
 * gateway injects (X-User-Id, X-User-Name, X-User-Role, X-Company-Id). Lets write paths stamp delivery history
 * with the REAL person (admin/dispatcher name) instead of a hardcoded "ADMIN"/"SYSTEM" literal —
 * so the activity timeline reads "par {name}" rather than "par Système".
 */
public final class ActorContext {

    private ActorContext() {}

    private static HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) return sra.getRequest();
        return null;
    }

    private static String header(String name) {
        HttpServletRequest req = currentRequest();
        if (req == null) return null;
        String v = req.getHeader(name);
        return (v != null && !v.isBlank()) ? v : null;
    }

    /** The display name of the acting user, or null when unavailable (e.g. system/background work). */
    public static String name() {
        return header("X-User-Name");
    }

    /** The acting user's id (subject), or null. */
    public static String id() {
        return header("X-User-Id");
    }

    /** The acting user's company/tenant id, or null when unavailable (system/background jobs). */
    public static UUID companyId() {
        String v = header("X-Company-Id");
        if (v == null) return null;
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The acting user's role, defaulting to ADMIN for an authenticated admin-API call with no role
     *  header, and SYSTEM when there is no request context at all (scheduled/background jobs). */
    public static Role role() {
        String r = header("X-User-Role");
        if (r == null) return currentRequest() != null ? Role.ADMIN : Role.SYSTEM;
        try {
            return Role.valueOf(r.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Role.ADMIN;
        }
    }

    /**
     * The value to store in DeliveryStatusHistory.changedBy: prefer the real name, then the id,
     * then the role label. Never null. This is what the timeline shows after "par ".
     */
    public static String changedBy() {
        String n = name();
        if (n != null) return n;
        String i = id();
        if (i != null) return i;
        return role().name();
    }
}
