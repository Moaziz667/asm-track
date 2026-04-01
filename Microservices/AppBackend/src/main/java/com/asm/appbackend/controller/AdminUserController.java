package com.asm.appbackend.controller;

import com.asm.appbackend.dto.admin.AdminUserResponse;
import com.asm.appbackend.dto.admin.CreateAdminUserRequest;
import com.asm.appbackend.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @PostMapping
    public ResponseEntity<AdminUserResponse> createUser(@Valid @RequestBody CreateAdminUserRequest req) {
        return ResponseEntity.ok(adminUserService.createUser(req));
    }

    @GetMapping
    public ResponseEntity<List<AdminUserResponse>> listUsers() {
        return ResponseEntity.ok(adminUserService.listUsers());
    }
}
