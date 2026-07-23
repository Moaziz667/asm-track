package com.asm.erpadapter.controller;

import com.asm.erpadapter.conformance.ConformanceReport;
import com.asm.erpadapter.routing.ErpProviderRouter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Certifies the current tenant's live ERP instance against the exact contract the ASM adapter needs —
 * the "drytest". Strictly read-only ({@code fields_get} / {@code check_access_rights} / version read),
 * so it is safe to run against a client's production ERP. The provider is resolved per-tenant by
 * {@link ErpProviderRouter} from the tenant settings + propagated {@code X-Company-Id}.
 *
 * <p>Intended callers: the admin "Test connection" flow (via AppBackend) after a passing auth check, and
 * a scheduled fleet health job. A {@code NO_GO} verdict must block enabling the tenant's sync.
 */
@RestController
@RequestMapping("/api/erp/conformance")
@Tag(name = "ERP Conformance", description = "Read-only certification (drytest) of the tenant's ERP "
        + "instance against the ASM integration contract — version + model/field/access checks.")
@RequiredArgsConstructor
public class ErpConformanceController {

    private final ErpProviderRouter router;

    @GetMapping
    @Operation(summary = "Certify the tenant's ERP instance (drytest)",
            description = "Runs the read-only conformance probe against the current tenant's configured ERP "
                    + "and returns a GO/DEGRADED/NO_GO report with per-capability results and the detected "
                    + "version. 204 when the tenant has no ERP configured.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Conformance report"),
            @ApiResponse(responseCode = "204", description = "No ERP configured for this tenant")
    })
    public ResponseEntity<ConformanceReport> certify() {
        return router.getProbe()
                .map(probe -> ResponseEntity.ok(probe.probe()))
                .orElseGet(() -> ResponseEntity.<ConformanceReport>noContent().build());
    }
}
