package com.asm.driver.dto.response;
import lombok.Builder;
import lombok.Data;
@Data @Builder
public class AuthResponse {
    private String token;
    private String refreshToken;
    private DriverInfo driver;
}
