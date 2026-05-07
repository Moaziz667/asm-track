package com.asm.delivery.controller;

import com.asm.delivery.entity.Company;
import com.asm.delivery.service.CompanyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal/companies")
@RequiredArgsConstructor
public class InternalCompanyController {

    private final CompanyService service;

    /** Called by erp-adapter to get a company's ERP connection config. */
    @GetMapping("/{id}/erp-config")
    public ResponseEntity<Map<String, Object>> erpConfig(@PathVariable UUID id) {
        return service.findById(id)
                .map(c -> ResponseEntity.ok(Map.<String, Object>of(
                        "erpType",    c.getErpType()    != null ? c.getErpType()    : "NONE",
                        "apiUrl",     c.getErpApiUrl()  != null ? c.getErpApiUrl()  : "",
                        "apiKey",     c.getErpApiKey()  != null ? c.getErpApiKey()  : "",
                        "dbName",     c.getErpDbName()  != null ? c.getErpDbName()  : "",
                        "username",   c.getErpUsername() != null ? c.getErpUsername() : "",
                        "uid",        c.getErpUid()     != null ? c.getErpUid()     : 1
                )))
                .orElse(ResponseEntity.notFound().build());
    }
}
