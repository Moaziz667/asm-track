package com.asm.appbackend.controller;

import com.asm.appbackend.dto.profile.ChangePasswordRequest;
import com.asm.appbackend.dto.profile.ChangePasswordResponse;
import com.asm.appbackend.dto.profile.ProfileResponse;
import com.asm.appbackend.dto.profile.UpdateProfileRequest;
import com.asm.appbackend.security.UserPrincipal;
import com.asm.appbackend.service.ProfileService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class ProfileController {

    private final ProfileService profileService;

    @GetMapping
    public ResponseEntity<ProfileResponse> getProfile(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(profileService.getProfile(principal.userId()));
    }

    @PutMapping
    public ResponseEntity<ProfileResponse> updateProfile(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        return ResponseEntity.ok(profileService.updateProfile(principal.userId(), request));
    }

    @PutMapping("/password")
    public ResponseEntity<ChangePasswordResponse> changePassword(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        return ResponseEntity.ok(profileService.changePassword(principal.userId(), request));
    }
}
