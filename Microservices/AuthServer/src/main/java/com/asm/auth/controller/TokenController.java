package com.asm.auth.controller;

import com.asm.auth.config.AuthProperties;
import com.asm.auth.service.TokenService;
import com.asm.auth.service.UserValidationService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@Slf4j
public class TokenController {

    private final TokenService tokenService;
    private final UserValidationService userValidationService;
    private final AuthProperties authProperties;

    @Qualifier("appJdbc")
    private final JdbcTemplate appJdbc;

    @Qualifier("driverJdbc")
    private final JdbcTemplate driverJdbc;

    public TokenController(
            TokenService tokenService,
            UserValidationService userValidationService,
            AuthProperties authProperties,
            @Qualifier("appJdbc") JdbcTemplate appJdbc,
            @Qualifier("driverJdbc") JdbcTemplate driverJdbc) {
        this.tokenService = tokenService;
        this.userValidationService = userValidationService;
        this.authProperties = authProperties;
        this.appJdbc = appJdbc;
        this.driverJdbc = driverJdbc;
    }

    /**
     * OAuth2 token endpoint supporting:
     * - grant_type=password          (user login — admin or driver)
     * - grant_type=client_credentials (service-to-service)
     * - grant_type=refresh_token      (token refresh)
     */
    @PostMapping(value = "/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> token(
            @RequestParam String grant_type,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String password,
            @RequestParam(required = false) String client_id,
            @RequestParam(required = false) String client_secret,
            @RequestParam(required = false) String refresh_token,
            @RequestParam(required = false, defaultValue = "admin-app") String scope) {

        return switch (grant_type) {
            case "password"           -> handlePasswordGrant(username, password, client_id);
            case "client_credentials" -> handleClientCredentials(client_id, client_secret);
            case "refresh_token"      -> handleRefreshToken(refresh_token, client_id);
            default -> error("unsupported_grant_type", "Grant type not supported: " + grant_type);
        };
    }

    // ── Password grant ────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> handlePasswordGrant(String username, String password, String clientId) {
        if (username == null || password == null)
            return error("invalid_request", "username and password are required");

        // Detect user type: phone number → driver, otherwise → admin
        Map<String, Object> user = isPhoneNumber(username)
                ? userValidationService.validateDriver(username, password)
                : userValidationService.validateAdmin(username, password);

        if (user == null) {
            log.warn("Password grant failed for username={}", username);
            return error("invalid_grant", "Invalid credentials");
        }

        String accessToken  = tokenService.issueAccessToken(user);
        String refreshToken = tokenService.issueRefreshToken(user);

        log.info("Password grant success — userId={} role={}", user.get("id"), user.get("role"));
        return ok(accessToken, refreshToken);
    }

    // ── Client credentials ────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> handleClientCredentials(String clientId, String clientSecret) {
        if (clientId == null || clientSecret == null)
            return error("invalid_request", "client_id and client_secret are required");

        String expected = authProperties.getClients().get(clientId);
        if (expected == null || !expected.equals(clientSecret)) {
            log.warn("Client credentials rejected for client_id={}", clientId);
            return error("invalid_client", "Invalid client credentials");
        }

        String token = tokenService.issueServiceToken(clientId);
        log.info("Client credentials grant success — clientId={}", clientId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", token);
        body.put("token_type",   "Bearer");
        body.put("expires_in",   300); // 5 min
        return ResponseEntity.ok(body);
    }

    // ── Refresh token ─────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> handleRefreshToken(String refreshToken, String clientId) {
        if (refreshToken == null)
            return error("invalid_request", "refresh_token is required");

        try {
            Claims claims = tokenService.parseToken(refreshToken);
            if (!"refresh".equals(claims.get("type"))) {
                return error("invalid_grant", "Not a refresh token");
            }

            String userId   = claims.getSubject();
            String userType = claims.get("userType", String.class);

            Map<String, Object> user = "driver".equals(userType)
                    ? reloadDriver(userId)
                    : reloadAdmin(userId);

            if (user == null) return error("invalid_grant", "User not found or disabled");

            String newAccess  = tokenService.issueAccessToken(user);
            String newRefresh = tokenService.issueRefreshToken(user);

            log.info("Refresh token rotated — userId={}", userId);
            return ok(newAccess, newRefresh);

        } catch (JwtException e) {
            log.warn("Invalid refresh token: {}", e.getMessage());
            return error("invalid_grant", "Invalid or expired refresh token");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> ok(String accessToken, String refreshToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token",  accessToken);
        body.put("token_type",    "Bearer");
        body.put("expires_in",    tokenService.getAccessExpiryMs() / 1000);
        body.put("refresh_token", refreshToken);
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> error(String error, String description) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", error, "error_description", description));
    }

    private boolean isPhoneNumber(String username) {
        return username.startsWith("+") || username.matches("\\d{8,15}");
    }

    private Map<String, Object> reloadAdmin(String userId) {
        List<Map<String, Object>> rows = appJdbc.queryForList(
                "SELECT id::text, name, email, role, active FROM admin_users WHERE id = ?::uuid",
                userId);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        if (Boolean.FALSE.equals(row.get("active"))) return null;
        Map<String, Object> u = new HashMap<>();
        u.put("id",        row.get("id"));
        u.put("name",      row.get("name"));
        u.put("role",      row.get("role"));
        u.put("type",      "admin");
        return u;
    }

    private Map<String, Object> reloadDriver(String userId) {
        List<Map<String, Object>> rows = driverJdbc.queryForList(
                "SELECT id::text, name, phone, account_status FROM drivers WHERE id = ?::uuid", userId);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        String accountStatus = (String) row.get("account_status");
        if (!"ACTIVE".equals(accountStatus)) return null;
        Map<String, Object> u = new HashMap<>();
        u.put("id",    row.get("id"));
        u.put("name",  row.get("name"));
        u.put("phone", row.get("phone"));
        u.put("role",  "DRIVER");
        u.put("type",  "driver");
        return u;
    }
}
