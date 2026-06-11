package com.asm.erpadapter.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * V2 — Pushes an Odoo→ASM change to the delivery platform's inbound endpoint. Service auth is applied
 * by the shared Feign interceptor (ServiceClientConfig). Used by both the webhook handler and the
 * polling fallback.
 */
@FeignClient(name = "delivery-inbound", url = "${DELIVERY_SERVICE_URL:http://delivery-service:8082}")
public interface DeliveryInboundClient {

    @PostMapping("/internal/erp/order-changed")
    void orderChanged(@RequestBody Map<String, Object> body);
}
