package com.asm.delivery.dto.response;

import com.asm.delivery.entity.RmaStatus;
import com.asm.delivery.entity.RmaStatusHistory;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/** One row of an RMA's real status timeline (admin drawer). */
@Data
@Builder
public class RmaStatusHistoryDto {
    private UUID id;
    private RmaStatus fromStatus;
    private RmaStatus toStatus;
    private String note;
    private String actedByName;
    private String actedByRole;
    private LocalDateTime createdAt;

    public static RmaStatusHistoryDto from(RmaStatusHistory h) {
        return RmaStatusHistoryDto.builder()
                .id(h.getId())
                .fromStatus(h.getFromStatus())
                .toStatus(h.getToStatus())
                .note(h.getNote())
                .actedByName(h.getActedByName())
                .actedByRole(h.getActedByRole())
                .createdAt(h.getCreatedAt())
                .build();
    }
}
