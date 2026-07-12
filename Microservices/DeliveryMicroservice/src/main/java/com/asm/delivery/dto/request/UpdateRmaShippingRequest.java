package com.asm.delivery.dto.request;

import lombok.Data;

/** Inbound return-shipment tracking set by ops (carrier + tracking number). Both optional/clearable. */
@Data
public class UpdateRmaShippingRequest {
    private String trackingNumber;
    private String shippingCarrier;
}
