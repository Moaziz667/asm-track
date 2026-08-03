package com.asm.delivery.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Turns a stored MinIO object key into an absolute URL <b>at read time</b>, derived from the origin
 * the caller itself used.
 *
 * <p><b>Why this exists.</b> Media URLs used to be persisted absolute, so every row froze the
 * server's address at upload time — the same table ended up holding
 * {@code http://10.116.151.125/files/...} next to {@code http://192.168.1.7/files/...}. The moment
 * the host moved (new DHCP lease, another Wi-Fi, VPS, production domain) every previously stored
 * photo became unreachable, and re-pointing {@code MINIO_PUBLIC_URL} only fixed rows written
 * afterwards. Storing the key and resolving late removes the whole class of failure: the database
 * carries no host, and each caller is answered with an address it can actually reach — the phone
 * that called {@code http://192.168.1.7/api/...} gets {@code http://192.168.1.7/files/...}, the
 * browser on localhost gets localhost, from the very same row.
 *
 * <p>Resolution order for the base:
 * <ol>
 *   <li>{@code app.public-base-url} when configured — an explicit override always wins, which is
 *       what you want behind a CDN and what stops a spoofed {@code Host} from rewriting URLs if the
 *       service is ever exposed without the gateway in front;</li>
 *   <li>the current request's forwarded origin ({@code X-Forwarded-Proto}/{@code X-Forwarded-Host},
 *       falling back to the request's own scheme/host) — the normal path;</li>
 *   <li>{@code MINIO_PUBLIC_URL} when there is no request at all (outbox drain, scheduled jobs,
 *       ERP sync) — those consumers are server-side and get a configured, stable address.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MediaUrlResolver {

    /** Gateway path that proxies MinIO (see ApiGateway route {@code minio-files}, StripPrefix=1). */
    private static final String FILES_PREFIX = "/files";

    private final MinioConfig minioConfig;
    private final MinioStorageService minioStorageService;

    /** Long enough to open a report or reload a tracking page; short enough that a leaked link dies. */
    private static final java.time.Duration SIGNED_URL_TTL = java.time.Duration.ofHours(6);

    /** Explicit public base (scheme://host[:port]); wins over the request origin when set. */
    @Value("${app.public-base-url:}")
    private String configuredBaseUrl;

    /**
     * @param keyOrLegacyUrl a storage key ({@code {companyId}/pod/...}) or, for rows written before
     *                       this change, a full absolute URL — legacy values are normalised back to
     *                       their key and re-based on the current origin, so old photos start
     *                       working again on whatever network they are served from.
     * @return an absolute, caller-reachable URL, or {@code null} when there is nothing to resolve.
     */
    public String toPublicUrl(String keyOrLegacyUrl) {
        if (!StringUtils.hasText(keyOrLegacyUrl)) return null;
        String key = toKey(keyOrLegacyUrl);
        if (key == null) return keyOrLegacyUrl; // foreign URL (not ours) — hand back untouched
        String url = baseUrl() + FILES_PREFIX + "/" + minioConfig.getBucket() + "/" + key;

        // Sign it. This bucket holds every tenant's proof-of-delivery photos and used to be
        // anonymously readable: the object key was the only protection, and it is derived from ids the
        // caller already holds ({companyId}/pod/{deliveryId}/…), so anyone with — or guessing — a URL
        // could read another tenant's evidence. Signing makes the URL itself the grant, and a
        // short-lived one, so a link that leaks stops working.
        //
        // The origin stays the caller's own, so this keeps working across networks (phone, laptop,
        // production domain): the gateway rewrites Host to MinIO's when proxying /files/**, which is
        // exactly what the signature was computed against.
        String query = minioStorageService.presignedQueryForKey(key, SIGNED_URL_TTL);
        return query != null ? url + "?" + query : url;
    }

    /**
     * Storage key for a stored value that may be a key already or a legacy absolute URL.
     * Returns {@code null} for a URL that doesn't point at our bucket.
     */
    public String toKey(String keyOrLegacyUrl) {
        if (!StringUtils.hasText(keyOrLegacyUrl)) return null;
        String key;
        if (!keyOrLegacyUrl.startsWith("http://") && !keyOrLegacyUrl.startsWith("https://")) {
            key = stripLeadingSlash(keyOrLegacyUrl);
        } else {
            // Legacy absolute URL: everything after "/{bucket}/" is the key, regardless of which host
            // or proxy path it was written with.
            String marker = "/" + minioConfig.getBucket() + "/";
            int at = keyOrLegacyUrl.indexOf(marker);
            if (at < 0) {
                log.debug("MediaUrlResolver: '{}' does not reference bucket '{}' — leaving as-is",
                        keyOrLegacyUrl, minioConfig.getBucket());
                return null;
            }
            key = stripLeadingSlash(keyOrLegacyUrl.substring(at + marker.length()));
        }
        // Legacy tenant-less reference (a bare logical path): the object lives under this tenant's
        // prefix. Keys that already name a tenant are left untouched.
        if (!TENANT_PREFIXED.matcher(key).matches()) {
            java.util.UUID companyId = com.asm.delivery.security.TenantContext.get();
            if (companyId != null) key = companyId + "/" + key;
        }
        return key;
    }

    /** A key that already carries an owning tenant, i.e. "{uuid}/rest/of/path". */
    private static final java.util.regex.Pattern TENANT_PREFIXED = java.util.regex.Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/.+");

    private static String stripLeadingSlash(String s) {
        return s.startsWith("/") ? s.substring(1) : s;
    }

    private String baseUrl() {
        if (StringUtils.hasText(configuredBaseUrl)) return trimTrailingSlash(configuredBaseUrl);

        HttpServletRequest request = currentRequest();
        if (request != null) {
            String proto = firstHeaderValue(request.getHeader("X-Forwarded-Proto"));
            String host = firstHeaderValue(request.getHeader("X-Forwarded-Host"));
            if (StringUtils.hasText(host)) {
                return (StringUtils.hasText(proto) ? proto : request.getScheme()) + "://" + host;
            }
            StringBuilder sb = new StringBuilder(request.getScheme()).append("://").append(request.getServerName());
            int port = request.getServerPort();
            boolean defaultPort = ("http".equals(request.getScheme()) && port == 80)
                    || ("https".equals(request.getScheme()) && port == 443);
            if (port > 0 && !defaultPort) sb.append(':').append(port);
            return sb.toString();
        }

        // No request in scope (outbox, scheduler, ERP sync): fall back to the configured public URL.
        return trimTrailingSlash(minioConfig.getPublicUrl() != null && !minioConfig.getPublicUrl().isBlank()
                ? minioConfig.getPublicUrl()
                : minioConfig.getUrl());
    }

    /** {@code X-Forwarded-*} may carry a comma-separated chain; the first entry is the origin client. */
    private static String firstHeaderValue(String header) {
        if (!StringUtils.hasText(header)) return null;
        int comma = header.indexOf(',');
        return (comma > 0 ? header.substring(0, comma) : header).trim();
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }
}
