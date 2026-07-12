package com.asm.delivery.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * Body for a client-initiated return on the public tracking page. The delivery id comes from the path;
 * the item shape is reused from {@link CreateRmaRequest.Item} (sku / name / quantity / unitPrice /
 * condition / reason) so the public and admin flows share one contract and the same server-side clamp.
 */
@Data
public class PublicReturnRequest {

    @NotEmpty
    private List<CreateRmaRequest.Item> items;

    /** MinIO URLs of evidence photos already uploaded via the eager photo endpoint. Optional. */
    private List<String> photoUrls;
}
