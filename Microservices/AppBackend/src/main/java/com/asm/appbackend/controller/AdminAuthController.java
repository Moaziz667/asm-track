package com.asm.appbackend.controller;

import com.asm.appbackend.dto.admin.AdminLoginRequest;
import com.asm.appbackend.dto.admin.AdminLoginResponse;
import com.asm.appbackend.service.AdminUserService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/admin")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminUserService adminUserService;

    // PROD: set COOKIE_SECURE=true when serving over HTTPS
    @Value("${app.cookie.secure:false}")
    private boolean cookieSecure;

    @PostMapping("/login")
    public ResponseEntity<AdminLoginResponse> login(
            @Valid @RequestBody AdminLoginRequest req,
            HttpServletResponse response
    ) {
        AdminLoginResponse loginResponse = adminUserService.login(req);
        setAuthCookies(response, loginResponse.token(), loginResponse.refreshToken());
        return ResponseEntity.ok(loginResponse);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AdminLoginResponse> refresh(
            @CookieValue(value = "refresh_token", required = false) String refreshToken,
            HttpServletResponse response
    ) {
        if (refreshToken == null) {
            return ResponseEntity.status(401).build();
        }
        // Rotate: issue new access token and new refresh token
        AdminLoginResponse loginResponse = adminUserService.refreshToken(refreshToken);
        setAuthCookies(response, loginResponse.token(), loginResponse.refreshToken());
        return ResponseEntity.ok(loginResponse);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        response.addCookie(expireCookie("access_token"));
        response.addCookie(expireCookie("refresh_token"));
        return ResponseEntity.ok().build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setAuthCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        response.addCookie(buildCookie("access_token", accessToken, 3600));
        if (refreshToken != null) {
            response.addCookie(buildCookie("refresh_token", refreshToken, 7 * 24 * 3600));
        }
    }

    private Cookie buildCookie(String name, String value, int maxAgeSeconds) {
        Cookie cookie = new Cookie(name, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/");
        cookie.setMaxAge(maxAgeSeconds);
        // Servlet 6.0 (Jakarta EE 10) — sets SameSite attribute natively
        cookie.setAttribute("SameSite", "Strict");
        return cookie;
    }

    private Cookie expireCookie(String name) {
        Cookie cookie = new Cookie(name, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Strict");
        return cookie;
    }
}
