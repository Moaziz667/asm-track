package com.asm.erpadapter.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Connects to an already-running Odoo instance (e.g. via docker-compose).
 * No container lifecycle management — just connection info and health checks.
 *
 * <p>Pre-configured instances from docker-compose:
 * <ul>
 *   <li>V16: localhost:8069</li>
 *   <li>V17/V18/V19: localhost:8070</li>
 * </ul>
 *
 * <p>Override via env vars:
 * <ul>
 *   <li>ODOO_V16_URL, ODOO_V16_DB, ODOO_V16_LOGIN, ODOO_V16_PASSWORD</li>
 *   <li>ODOO_V17_URL, etc.</li>
 * </ul>
 */
public class OdooContainer implements AutoCloseable {

    private final OdooVersion version;
    private final String url;
    private final String db;
    private final String login;
    private final String password;

    private static final String DEFAULT_URL = "http://localhost:8069/jsonrpc";
    private static final String DEFAULT_DB = "odoo";
    private static final String DEFAULT_LOGIN = "admin";
    private static final String DEFAULT_PASSWORD = "admin";

    public OdooContainer(OdooVersion version) {
        this.version = version;
        String prefix = "ODOO_" + version.name() + "_";
        this.url = System.getenv().getOrDefault(prefix + "URL", defaultUrl(version));
        this.db = System.getenv().getOrDefault(prefix + "DB", DEFAULT_DB);
        this.login = System.getenv().getOrDefault(prefix + "LOGIN", DEFAULT_LOGIN);
        this.password = System.getenv().getOrDefault(prefix + "PASSWORD", DEFAULT_PASSWORD);
    }

    /**
     * Verify Odoo is reachable and can authenticate.
     * No container lifecycle — just checks HTTP + auth.
     */
    public void start() {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            try {
                int uid = authenticate();
                if (uid > 0) {
                    System.out.println("[OdooContainer] Odoo " + version + " ready — uid=" + uid
                            + " url=" + url);
                    return;
                }
            } catch (Exception ignored) {}
            try { Thread.sleep(3_000); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        throw new RuntimeException("Odoo " + version + " not reachable at " + url
                + " — make sure docker-compose odoo containers are running");
    }

    public void stop() {
        // No-op — docker-compose manages the lifecycle
    }

    @Override
    public void close() {
        stop();
    }

    // ── Connection info ──────────────────────────────────────────────────────

    public String getOdooUrl() { return url; }
    public String getOdooHost() {
        try { return java.net.URI.create(url).getHost(); }
        catch (Exception e) { return "localhost"; }
    }
    public int getOdooPort() {
        try { return java.net.URI.create(url).getPort(); }
        catch (Exception e) { return 8069; }
    }
    public String getDb() { return db; }
    public String getLogin() { return login; }
    public String getPassword() { return password; }
    public OdooVersion getVersion() { return version; }

    // ── Authentication ───────────────────────────────────────────────────────

    public int authenticate() {
        return authenticate(db, login, password);
    }

    @SuppressWarnings("unchecked")
    public int authenticate(String db, String login, String password) {
        try {
            var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var body = Map.of(
                    "jsonrpc", "2.0",
                    "method", "call",
                    "params", Map.of(
                            "service", "common",
                            "method", "authenticate",
                            "args", List.of(db, login, password, Map.of())
                    )
            );
            String json = objectMapper.writeValueAsString(body);

            var client = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                    .build();

            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            var map = objectMapper.readValue(response.body(), java.util.Map.class);
            Object result = map.get("result");
            if (result instanceof Number n) return n.intValue();
            return 0;
        } catch (Exception e) {
            throw new RuntimeException("Odoo authentication failed: " + e.getMessage(), e);
        }
    }

    // ── Default URLs ─────────────────────────────────────────────────────────

    private static String defaultUrl(OdooVersion version) {
        String host = resolveHost();
        return switch (version) {
            case V16 -> "http://" + host + ":8069/jsonrpc";
            case V17, V18, V19 -> "http://" + host + ":8070/jsonrpc";
        };
    }

    /**
     * Detect the right host to reach the host's Odoo from inside a container:
     * - Outside Docker → localhost
     * - Inside Docker → host.docker.internal (Docker Desktop) or localhost (--network host on Linux)
     */
    private static String resolveHost() {
        if (!isInsideDocker()) return "localhost";
        // Try host.docker.internal first (Docker Desktop on Mac/Windows)
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("host.docker.internal", 8069), 2000);
            return "host.docker.internal";
        } catch (Exception ignored) {}
        // Fallback to localhost (--network host on Linux Docker)
        return "localhost";
    }

    private static boolean isInsideDocker() {
        return java.nio.file.Files.exists(java.nio.file.Path.of("/.dockerenv"));
    }
}
