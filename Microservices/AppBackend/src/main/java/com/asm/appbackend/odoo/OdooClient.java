package com.asm.appbackend.odoo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DEAD CODE: This Odoo client is a legacy implementation.
 * All ERP synchronization and interaction logic have been moved to the
 * dedicated ErpAdapterService microservice to ensure separation of concerns
 * and multi-tenant scalability.
 */
@Component
@Slf4j
@Deprecated
public class OdooClient {

    @Value("${odoo.url:http://host.docker.internal:8069/jsonrpc}")
    private String odooUrl;

    @Value("${odoo.db:odoo}")
    private String odooDb;

    @Value("${odoo.uid:2}")
    private int odooUid;

    @Value("${odoo.password:admin}")
    private String odooPassword;

    private final RestTemplate restTemplate;

    public OdooClient(RestTemplateBuilder builder) {
        this.restTemplate = builder.build();
    }

    // ── Create res.partner in Odoo ────────────────────────────────────────────

    public Integer createPartner(String name, String phone) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> partnerVals = new HashMap<>();
            partnerVals.put("name", name);
            partnerVals.put("phone", phone);
            partnerVals.put("mobile", phone);
            partnerVals.put("customer_rank", 1);
            partnerVals.put("comment", "ASM Mobile App Client");

            List<Object> args = List.of(
                    odooDb, odooUid, odooPassword,
                    "res.partner", "create",
                    List.of(partnerVals)
            );

            Map<String, Object> response = callRpc(args);
            long ms = System.currentTimeMillis() - start;

            if (response == null || response.containsKey("error")) {
                log.error("Odoo createPartner failed in {}ms — name={} response: {}", ms, name, response);
                return null;
            }

            Object result = response.get("result");
            if (result == null) {
                log.error("Odoo createPartner returned null result in {}ms — name={}", ms, name);
                return null;
            }

            Integer partnerId = ((Number) result).intValue();
            log.info("Odoo createPartner success in {}ms — name={} partnerId={}", ms, name, partnerId);
            return partnerId;

        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            log.error("Odoo createPartner exception in {}ms — name={}: {}", ms, name, e.getMessage(), e);
            return null;
        }
    }

    // ── Internal: execute JSON-RPC call ───────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> callRpc(List<Object> executeKwArgs) {
        Map<String, Object> params = new HashMap<>();
        params.put("service", "object");
        params.put("method", "execute_kw");
        params.put("args", executeKwArgs);

        Map<String, Object> body = new HashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("method", "call");
        body.put("params", params);

        return restTemplate.postForObject(odooUrl, body, Map.class);
    }
}
