package com.asm.appbackend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemSettingsDto {
    private String activeErpProvider;
    private Object erpConfiguration; // We will use Object to accept/return arbitrary JSON maps
}
