package com.asm.driver.storage;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The origin a media URL should be built on, taken from the caller rather than from configuration.
 *
 * <p><b>Why.</b> Avatar URLs were assembled from {@code MINIO_PUBLIC_URL}, a single host fixed at
 * boot. Every caller got that one address whatever address it had itself used, so the moment the
 * server moved — a new DHCP lease, a phone hotspot, the VPS, a production domain — every avatar
 * pointed somewhere the caller could not reach, and the only remedy was editing an environment
 * variable and restarting. A driver on a hotspot reached the API perfectly and saw a broken image.
 *
 * <p>Answering with the caller's own origin removes the coupling entirely: the phone that called
 * {@code http://10.157.92.125/api/...} is handed {@code http://10.157.92.125/...}, a browser on
 * localhost is handed localhost, from the same stored key and with no configuration to keep in sync.
 *
 * <p>This mirrors {@code MediaUrlResolver} in DeliveryMicroservice deliberately — same resolution
 * order, same reasoning. Two services cannot share a class here, but they must not answer the same
 * question differently, or a delivery photo and a driver avatar would resolve against different
 * hosts in one screen.
 *
 * <ol>
 *   <li>{@code app.public-base-url} when set — an explicit override always wins, which is what a CDN
 *       needs and what stops a spoofed {@code Host} rewriting URLs if the service were ever exposed
 *       without the gateway in front;</li>
 *   <li>the current request's forwarded origin, then its own scheme and host — the normal path;</li>
 *   <li>{@code MINIO_PUBLIC_URL} when there is no request at all (scheduled jobs, messaging
 *       consumers) — server-side callers get a configured, stable address.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class PublicOriginResolver {

    private final MinioConfig minioConfig;

    @Value("${app.public-base-url:}")
    private String configuredBaseUrl;

    /** Scheme and host with no trailing slash, e.g. {@code http://10.157.92.125}. */
    public String origin() {
        if (StringUtils.hasText(configuredBaseUrl)) return trimTrailingSlash(configuredBaseUrl);

        HttpServletRequest request = currentRequest();
        if (request != null) {
            String host = firstHeaderValue(request.getHeader("X-Forwarded-Host"));
            if (StringUtils.hasText(host)) {
                String proto = firstHeaderValue(request.getHeader("X-Forwarded-Proto"));
                return (StringUtils.hasText(proto) ? proto : request.getScheme()) + "://" + host;
            }
            StringBuilder sb = new StringBuilder(request.getScheme())
                    .append("://").append(request.getServerName());
            int port = request.getServerPort();
            boolean defaultPort = ("http".equals(request.getScheme()) && port == 80)
                    || ("https".equals(request.getScheme()) && port == 443);
            if (port > 0 && !defaultPort) sb.append(':').append(port);
            return sb.toString();
        }

        // The configured value historically carried the gateway prefix ("http://host/files"), because
        // it was concatenated directly. Callers now add the prefix themselves, so strip it here or it
        // would appear twice on the no-request path.
        return stripFilesSuffix(trimTrailingSlash(StringUtils.hasText(minioConfig.getPublicUrl())
                ? minioConfig.getPublicUrl()
                : minioConfig.getUrl()));
    }

    /** Gateway path that proxies MinIO — see the ApiGateway route {@code minio-files}, StripPrefix=1. */
    public static final String FILES_PREFIX = "/files";

    private static String stripFilesSuffix(String base) {
        return base.endsWith(FILES_PREFIX) ? base.substring(0, base.length() - FILES_PREFIX.length()) : base;
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
