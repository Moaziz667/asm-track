package com.asm.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates user credentials against the two existing databases
 * (postgres-app for admin users, postgres-driver for drivers).
 * No user migration needed — we validate against the same tables.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserValidationService {

    @Qualifier("appJdbc")
    private final JdbcTemplate appJdbc;

    @Qualifier("driverJdbc")
    private final JdbcTemplate driverJdbc;

    private final PasswordEncoder passwordEncoder;

    /**
     * Validates admin/dispatcher/manager/super-admin by email.
     * Returns user info map if valid, null otherwise.
     */
    public Map<String, Object> validateAdmin(String email, String password) {
        List<Map<String, Object>> rows = appJdbc.queryForList(
                "SELECT id::text, name, email, password_hash, role, company_id::text, active " +
                "FROM admin_users WHERE email = ?", email);

        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);

        if (Boolean.FALSE.equals(row.get("active"))) {
            log.warn("Login attempt for disabled admin: {}", email);
            return null;
        }

        String hash = (String) row.get("password_hash");
        if (!passwordEncoder.matches(password, hash)) return null;

        Map<String, Object> user = new HashMap<>();
        user.put("id",        row.get("id"));
        user.put("name",      row.get("name"));
        user.put("email",     row.get("email"));
        user.put("role",      row.get("role"));
        user.put("companyId", row.get("company_id"));
        user.put("type",      "admin");
        return user;
    }

    /**
     * Validates driver by phone number.
     * Returns user info map if valid, null otherwise.
     */
    public Map<String, Object> validateDriver(String phone, String password) {
        List<Map<String, Object>> rows = driverJdbc.queryForList(
                "SELECT id::text, name, phone, password_hash, active " +
                "FROM drivers WHERE phone = ?", phone);

        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);

        Object activeVal = row.get("active");
        if (Boolean.FALSE.equals(activeVal) || Integer.valueOf(0).equals(activeVal)) {
            log.warn("Login attempt for disabled driver: {}", phone);
            return null;
        }

        String hash = (String) row.get("password_hash");
        if (!passwordEncoder.matches(password, hash)) return null;

        Map<String, Object> user = new HashMap<>();
        user.put("id",    row.get("id"));
        user.put("name",  row.get("name"));
        user.put("phone", row.get("phone"));
        user.put("role",  "DRIVER");
        user.put("type",  "driver");
        return user;
    }
}
