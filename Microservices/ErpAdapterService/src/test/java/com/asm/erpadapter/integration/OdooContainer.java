package com.asm.erpadapter.integration;

import java.time.Duration;
import java.time.Instant;

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
            var client = java.net.http.HttpClient.newHttpClient();
            var body = """
                    {
                        "jsonrpc": "2.0",
                        "method": "call",
                        "params": {
                            "service": "common",
                            "method": "authenticate",
                            "args": ["%s", "%s", "%s", {}]
                        }
                    }
                    """.formatted(db, login, password);

            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();

            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            var map = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    response.body(), java.util.Map.class);
            Object result = map.get("result");
            if (result instanceof Number n) return n.intValue();
            return 0;
        } catch (Exception e) {
            throw new RuntimeException("Odoo authentication failed: " + e.getMessage(), e);
        }
    }

    // ── Default URLs ─────────────────────────────────────────────────────────

    private static String defaultUrl(OdooVersion version) {
        // With --network host, localhost IS the host. host.docker.internal is Docker Desktop only.
        String host = resolveHost();
        return switch (version) {
            case V16 -> "http://" + host + ":8069/jsonrpc";
            case V17, V18, V19 -> "http://" + host + ":8070/jsonrpc";
        };
    }

    /**
     * Detect the right host to reach the host's Odoo from inside a container:
     * - --network host → localhost works
     * - Docker Desktop → host.docker.internal
     * - Outside Docker → localhost
     */
    private static String resolveHost() {
        if (!isInsideDocker()) return "localhost";
        // Test if localhost is reachable (works with --network host on Linux Docker)
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("localhost", 8069), 2000);
            return "localhost";
        } catch (Exception ignored) {}
        // Fallback to host.docker.internal (Docker Desktop)
        return "host.docker.internal";
    }

    private static boolean isInsideDocker() {
        return java.nio.file.Files.exists(java.nio.file.Path.of("/.dockerenv"));
    }
}
