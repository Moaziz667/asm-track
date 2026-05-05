package com.asm.appbackend.controller;

import com.asm.appbackend.dto.auth.*;
import com.asm.appbackend.exception.AppException;
import com.asm.appbackend.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/client")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        throw AppException.forbidden("Client auth is disabled: clients are managed in ERP only");
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<VerifyOtpResponse> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        throw AppException.forbidden("Client auth is disabled: clients are managed in ERP only");
    }

    @PostMapping("/resend-otp")
    public ResponseEntity<ResendOtpResponse> resendOtp(@Valid @RequestBody ResendOtpRequest request) {
        throw AppException.forbidden("Client auth is disabled: clients are managed in ERP only");
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        throw AppException.forbidden("Client auth is disabled: clients are managed in ERP only");
    }
}
