package com.asm.delivery.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Used by POST /api/dev/client-token to generate a test JWT. */
@Data
public class DevTokenRequest {
    @NotBlank
    private String userId;

    @NotBlank
    private String name;

    private String phone;
}
