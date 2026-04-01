package com.asm.appbackend.controller;

import com.asm.appbackend.dto.admin.AdminLoginRequest;
import com.asm.appbackend.dto.admin.AdminLoginResponse;
import com.asm.appbackend.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/admin")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminUserService adminUserService;

    @PostMapping("/login")
    public ResponseEntity<AdminLoginResponse> login(@Valid @RequestBody AdminLoginRequest req) {
        return ResponseEntity.ok(adminUserService.login(req));
    }
}
