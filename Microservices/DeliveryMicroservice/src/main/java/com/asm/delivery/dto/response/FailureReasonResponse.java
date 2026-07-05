package com.asm.delivery.dto.response;

import com.asm.delivery.entity.FailureCode;
import com.asm.delivery.entity.FailureReason;
import com.asm.delivery.entity.ReasonScope;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class FailureReasonResponse {
    private UUID id;
    private String code;
    private String label;
    private FailureCode category;
    private ReasonScope scope;
    private boolean active;
    private int sortOrder;

    public static FailureReasonResponse from(FailureReason r) {
        return FailureReasonResponse.builder()
                .id(r.getId())
                .code(r.getCode())
                .label(r.getLabel())
                .category(r.getCategory())
                .scope(r.getScope())
                .active(r.isActive())
                .sortOrder(r.getSortOrder())
                .build();
    }
}
