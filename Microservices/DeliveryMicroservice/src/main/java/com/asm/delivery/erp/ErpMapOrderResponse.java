package com.asm.delivery.erp;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ErpMapOrderResponse {
    private Integer saleOrderId;
    private String erpOrderId;
    private Integer partnerId;
    private String customerName;
    private String customerPhone;
    private String street;
    private String city;
    private Double latitude;
    private Double longitude;
}
