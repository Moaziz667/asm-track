package com.asm.appbackend.dto.auth;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record ResendOtpResponse(
        String message,
        String devOtp,
        LocalDateTime otpExpiresAt
) {
}
