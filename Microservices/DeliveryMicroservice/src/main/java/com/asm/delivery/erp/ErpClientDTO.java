package com.asm.delivery.erp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErpClientDTO {
    private String erpClientId;
    private String name;
    private String phone;
    private String email;
}
