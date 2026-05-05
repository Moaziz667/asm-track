package com.asm.delivery.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Toggle the lock flag on a route")
public class SetRouteLockRequest {
    @NotNull
    @Schema(description = "True to freeze the route from batch optimization, false to unlock", example = "true")
    private Boolean locked;
}
