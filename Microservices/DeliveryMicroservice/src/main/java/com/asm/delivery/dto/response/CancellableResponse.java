package com.asm.delivery.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CancellableResponse {
    private boolean cancellable;
    private String  reason;
}
