package com.asm.erpadapter.config;

import com.asm.erpadapter.adapter.dux.DuxLookupAdapter;
import com.asm.erpadapter.adapter.dux.DuxOrderAdapter;
import com.asm.erpadapter.adapter.dux.DuxSyncAdapter;
import com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient;
import com.asm.erpadapter.adapter.odoo.OdooLookupAdapter;
import com.asm.erpadapter.adapter.odoo.OdooOrderAdapter;
import com.asm.erpadapter.adapter.odoo.OdooSyncAdapter;
import com.asm.erpadapter.port.ErpLookupPort;
import com.asm.erpadapter.port.ErpOrderPort;
import com.asm.erpadapter.port.ErpSyncPort;
import com.asm.erpadapter.service.IdempotencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Creates per-company ERP adapter instances based on the company's ERP type and credentials.
 * Used by controllers when X-Company-Id header is present.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CompanyAdapterFactory {

    private final CompanyConfigResolver resolver;
    private final IdempotencyService idempotencyService;

    public ErpSyncPort syncFor(UUID companyId) {
        CompanyErpConfig cfg = resolver.resolve(companyId);
        return switch (cfg.erpType()) {
            case "ODOO" -> new OdooSyncAdapter(OdooJsonRpcClient.forCompany(cfg), idempotencyService);
            case "DUX"  -> new DuxSyncAdapter();
            default -> {
                log.warn("No ERP configured for company {} (type={})", companyId, cfg.erpType());
                yield new NoopSyncAdapter();
            }
        };
    }

    public ErpLookupPort lookupFor(UUID companyId) {
        CompanyErpConfig cfg = resolver.resolve(companyId);
        return switch (cfg.erpType()) {
            case "ODOO" -> new OdooLookupAdapter(OdooJsonRpcClient.forCompany(cfg));
            case "DUX"  -> new DuxLookupAdapter();
            default -> new NoopLookupAdapter();
        };
    }

    public ErpOrderPort orderFor(UUID companyId) {
        CompanyErpConfig cfg = resolver.resolve(companyId);
        return switch (cfg.erpType()) {
            case "ODOO" -> {
                OdooJsonRpcClient rpc = OdooJsonRpcClient.forCompany(cfg);
                yield new OdooOrderAdapter(rpc, new OdooSyncAdapter(rpc, idempotencyService));
            }
            case "DUX"  -> new DuxOrderAdapter();
            default -> new NoopOrderAdapter();
        };
    }
}
