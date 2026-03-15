package com.asm.appbackend.service;

import com.asm.appbackend.dto.profile.ChangePasswordRequest;
import com.asm.appbackend.dto.profile.ChangePasswordResponse;
import com.asm.appbackend.dto.profile.ProfileResponse;
import com.asm.appbackend.dto.profile.UpdateProfileRequest;
import com.asm.appbackend.entity.Client;
import com.asm.appbackend.exception.AppException;
import com.asm.appbackend.repository.ClientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProfileService {

    private final ClientRepository clientRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public ProfileResponse getProfile(String clientId) {
        Client client = findClient(clientId);
        return toResponse(client);
    }

    @Transactional
    public ProfileResponse updateProfile(String clientId, UpdateProfileRequest request) {
        Client client = findClient(clientId);
        client.setName(request.name());
        client.setEmail(request.email());
        client.setAddress(request.address());
        clientRepository.save(client);
        return toResponse(client);
    }

    @Transactional
    public ChangePasswordResponse changePassword(String clientId, ChangePasswordRequest request) {
        Client client = findClient(clientId);

        if (!passwordEncoder.matches(request.currentPassword(), client.getPasswordHash())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
        if (request.currentPassword().equals(request.newPassword())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "New password must be different from current password");
        }

        client.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        clientRepository.save(client);

        return ChangePasswordResponse.builder()
                .message("Password changed successfully")
                .build();
    }

    private Client findClient(String clientId) {
        UUID id;
        try {
            id = UUID.fromString(clientId);
        } catch (IllegalArgumentException ex) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Invalid token subject");
        }

        return clientRepository.findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Client not found"));
    }

    private ProfileResponse toResponse(Client client) {
        return ProfileResponse.builder()
                .id(client.getId().toString())
                .name(client.getName())
                .phone(client.getPhone())
                .email(client.getEmail())
                .address(client.getAddress())
                .phoneVerified(client.isPhoneVerified())
                .build();
    }
}
