package com.asm.driver.config;

import com.asm.tenant.TenantContext;
import com.asm.tenant.TenantSchema;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves which tenant a driver-activation request belongs to, from the invite token or the phone
 * number alone.
 *
 * <p>Account activation ({@code /api/v1/auth/driver/setup}) is necessarily <b>unauthenticated</b> —
 * the driver has no account yet, so no token, so the gateway injects no {@code X-Company-Id}. With
 * schema-per-tenant that left the lookup running against {@code public}, where invite tokens never
 * live: every activation returned "Invalid or expired invite token" and <b>no driver could ever
 * activate, in any tenant</b>. Same chicken-and-egg as public delivery tracking, and solved the same
 * way: scan the {@code company_%} schemas for the identifier, then pin the TenantContext.
 *
 * <p>Positive results are cached — an invite token belongs to one tenant forever. Misses are not
 * cached (a token may be issued moments later), and the scan uses a raw {@link JdbcTemplate} so it
 * bypasses Hibernate's tenant routing.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DriverActivationTenantResolver {

    private final JdbcTemplate jdbcTemplate;

    private final Map<String, UUID> cache = new ConcurrentHashMap<>();

    /** @return the owning companyId for an invite token, or {@code null} if no tenant has it. */
    public UUID resolveByInviteToken(UUID token) {
        return resolve("token:" + token,
                schema -> "SELECT 1 FROM \"" + schema + "\".driver_invite_tokens WHERE token = ? LIMIT 1",
                token);
    }

    /** @return the owning companyId for a driver phone number, or {@code null} if unknown. */
    public UUID resolveByPhone(String phone) {
        return resolve("phone:" + phone,
                schema -> "SELECT 1 FROM \"" + schema + "\".drivers WHERE phone = ? LIMIT 1",
                phone);
    }

    private UUID resolve(String cacheKey, java.util.function.Function<String, String> sqlFor, Object arg) {
        UUID cached = cache.get(cacheKey);
        if (cached != null) return cached;

        List<String> schemas = jdbcTemplate.queryForList(
                "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'company\\_%'",
                String.class);
        for (String schema : schemas) {
            try {
                List<Integer> hit = jdbcTemplate.query(sqlFor.apply(schema), (rs, i) -> 1, arg);
                if (!hit.isEmpty()) {
                    UUID companyId = TenantSchema.companyIdFrom(schema);
                    if (companyId != null) {
                        cache.put(cacheKey, companyId);
                        return companyId;
                    }
                }
            } catch (Exception e) {
                // A schema mid-provisioning may not have the table yet — skip, don't abort the scan.
                log.debug("Activation tenant scan skipped schema {}: {}", schema, e.getMessage());
            }
        }
        return null;
    }
}
