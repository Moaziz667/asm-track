package com.asm.delivery.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DevTokenResponse {
    private String accessToken;
    private String userId;
    private String role;
}
