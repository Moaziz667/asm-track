package com.asm.delivery.transport;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Admin/dispatcher identity resolved from AppBackend's {@code /internal/admin-users} endpoint. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserDTO {
    private String id;
    private String name;
    private String role;
}
