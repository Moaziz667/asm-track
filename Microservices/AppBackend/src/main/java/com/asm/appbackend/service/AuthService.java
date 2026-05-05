package com.asm.appbackend.service;

import com.asm.appbackend.dto.auth.*;
import com.asm.appbackend.entity.Client;
import com.asm.appbackend.entity.ClientOtp;
import com.asm.appbackend.exception.AppException;
import com.asm.appbackend.repository.ClientOtpRepository;
import com.asm.appbackend.repository.ClientRepository;
import com.asm.appbackend.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final ClientRepository clientRepository;
    private final ClientOtpRepository clientOtpRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        Client client = clientRepository.findByPhone(request.phone())
                .orElseGet(() -> Client.builder()
                        .id(UUID.randomUUID())
                        .phone(request.phone())
                        .phoneVerified(false)
                        .build());

        if (client.isPhoneVerified()) {
            throw new AppException(HttpStatus.CONFLICT, "Phone already registered. Please login.");
        }

        client.setName(request.name());
        client.setPasswordHash(passwordEncoder.encode(request.password()));
        clientRepository.save(client);

        String otpCode = generateOtpCode();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);

        ClientOtp otp = ClientOtp.builder()
                .id(UUID.randomUUID())
                .phone(request.phone())
                .code(otpCode)
                .expiresAt(expiresAt)
                .used(false)
                .build();
        clientOtpRepository.save(otp);

        return RegisterResponse.builder()
                .clientId(client.getId().toString())
                .message("Registration started. Verify phone OTP to activate account.")
                .devOtp(otpCode)
                .otpExpiresAt(expiresAt)
                .build();
    }

    @Transactional
    public VerifyOtpResponse verifyOtp(VerifyOtpRequest request) {
        ClientOtp latestOtp = clientOtpRepository.findFirstByPhoneAndUsedFalseOrderByCreatedAtDesc(request.phone())
                .orElseThrow(() -> new AppException(HttpStatus.BAD_REQUEST, "No active OTP found for this phone"));

        if (latestOtp.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "OTP expired");
        }
        if (!latestOtp.getCode().equals(request.code())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Invalid OTP code");
        }

        latestOtp.setUsed(true);
        clientOtpRepository.save(latestOtp);

        Client client = clientRepository.findByPhone(request.phone())
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Client not found"));

        client.setPhoneVerified(true);
        clientRepository.save(client);

        return VerifyOtpResponse.builder()
                .clientId(client.getId().toString())
                .message("Phone verified successfully")
                .build();
    }

        @Transactional
        public ResendOtpResponse resendOtp(ResendOtpRequest request) {
        Client client = clientRepository.findByPhone(request.phone())
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Client not found"));

        if (client.isPhoneVerified()) {
            throw new AppException(HttpStatus.CONFLICT, "Phone is already verified");
        }

        String otpCode = generateOtpCode();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);

        ClientOtp otp = ClientOtp.builder()
            .id(UUID.randomUUID())
            .phone(request.phone())
            .code(otpCode)
            .expiresAt(expiresAt)
            .used(false)
            .build();
        clientOtpRepository.save(otp);

        return ResendOtpResponse.builder()
            .message("OTP resent successfully")
            .devOtp(otpCode)
            .otpExpiresAt(expiresAt)
            .build();
        }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        Client client = clientRepository.findByPhone(request.phone())
                .orElseThrow(() -> new AppException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (!client.isPhoneVerified()) {
            throw new AppException(HttpStatus.FORBIDDEN, "Phone number not verified");
        }

        if (!passwordEncoder.matches(request.password(), client.getPasswordHash())) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        String token = jwtService.generateClientToken(client.getId().toString(), client.getName(), client.getOdooPartnerId());

        return LoginResponse.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .expiresInMs(jwtService.getAccessExpiryMs())
                .client(ClientSummary.builder()
                        .id(client.getId().toString())
                        .name(client.getName())
                        .phone(client.getPhone())
                        .email(client.getEmail())
                        .build())
                .build();
    }

    private String generateOtpCode() {
        int value = ThreadLocalRandom.current().nextInt(100000, 1000000);
        return String.valueOf(value);
    }
}
