package com.asm.appbackend.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientResponse {
    private UUID id;
    private String name;
    private String phone;
    private Integer odooPartnerId;
    private boolean verified;
    private LocalDateTime createdAt;
}
