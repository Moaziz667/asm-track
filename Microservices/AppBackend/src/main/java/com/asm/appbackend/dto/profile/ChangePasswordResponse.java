package com.asm.appbackend.dto.profile;

import lombok.Builder;

@Builder
public record ChangePasswordResponse(
        String message
) {
}
