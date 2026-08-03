package com.asm.delivery.erp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/erp")
@Tag(
    name = "Admin — ERP Import",
    description = """
        Endpoints for connecting ASM Track to an external ERP (Odoo, SAP, Sage, etc.).

        **Flow:**
        1. Call `/pending-orders` to see confirmed ERP orders not yet in ASM Track.
        2. Call `/pending-orders/{erpOrderId}` to preview full order details.
        3. Call `/import-order/{erpOrderId}` (single) or `/bulk-import` (multiple) to create deliveries.

        Once imported, orders appear in the Deliveries board and can be assigned to drivers.
        The ERP adapter syncs delivery status back to the ERP automatically after each status change.
        """
)
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
@Validated
public class ErpController {

    private final ErpLookupService erpLookupService;
    // ── Client Search ─────────────────────────────────────────────────────────

    @GetMapping("/clients")
    @Operation(
        summary = "Search ERP clients",
        description = """
            Search customers from the connected ERP by name, phone, or email.
            Used to auto-complete the client field when creating a new delivery manually.
            Results are cached for 5 minutes per company.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of matching ERP clients",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ErpClientDTO.class)))),
        @ApiResponse(responseCode = "503", description = "ERP adapter unreachable — Odoo may be down", content = @Content)
    })
    public ResponseEntity<List<ErpClientDTO>> searchClients(
            @Parameter(description = "Search term — matches name, phone, or email", example = "Mohamed")
            @RequestParam(defaultValue = "") String search,

            @Parameter(description = "Max results to return (1–50)", example = "10")
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit
    ) {
        return ResponseEntity.ok(erpLookupService.searchClients(search, limit));
    }

    // ── Product Search ────────────────────────────────────────────────────────

    @GetMapping("/products")
    @Operation(
        summary = "Search ERP products",
        description = """
            Search products from the connected ERP by name or SKU.
            Used to populate the items list when creating a new delivery manually.
            Results are cached for 5 minutes per company.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of matching ERP products",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ErpProductDTO.class)))),
        @ApiResponse(responseCode = "503", description = "ERP adapter unreachable", content = @Content)
    })
    public ResponseEntity<List<ErpProductDTO>> searchProducts(
            @Parameter(description = "Search term — matches product name or SKU", example = "Colis")
            @RequestParam(defaultValue = "") String search,

            @Parameter(description = "Max results to return (1–50)", example = "10")
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit
    ) {
        return ResponseEntity.ok(erpLookupService.searchProducts(search, limit));
    }

    // ── Pending Orders ────────────────────────────────────────────────────────

    @GetMapping("/pending-orders")
    @Operation(
        summary = "List pending ERP orders",
        description = """
            Returns confirmed ERP orders (state = 'sale'). Already-imported orders are
            included but flagged with `alreadyImported=true` so the UI can split them into
            "À importer" / "Déjà importées" tabs.

            Results are cached for 5 minutes. Click "Synchroniser ERP" on the import page to force a refresh.

            **Scheduler:** The system also checks this endpoint every 2 minutes and sends a real-time
            WebSocket notification to the admin when new orders appear.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of importable ERP orders",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ErpPendingOrderSummaryDTO.class)))),
        @ApiResponse(responseCode = "503", description = "ERP adapter unreachable", content = @Content)
    })
    public ResponseEntity<List<ErpPendingOrderSummaryDTO>> getPendingOrders(
            @Parameter(description = "Max orders to fetch from ERP (1–300)", example = "100")
            @RequestParam(defaultValue = "100") @Min(1) @Max(300) int limit,
            @Parameter(description = "Bypass the 5-minute cache and fetch live from ERP")
            @RequestParam(defaultValue = "false") boolean forceRefresh
    ) {
        return ResponseEntity.ok(erpLookupService.getPendingOrders(limit, forceRefresh));
    }

    // ── Order Preview ─────────────────────────────────────────────────────────

    @GetMapping("/pending-orders/preview")
    @Operation(
        summary = "Preview a pending ERP order",
        description = """
            Returns the full detail of a single ERP order including all line items, weight,
            payment term, and whether it has already been imported.

            Use this before importing to validate the data is complete
            (client name, phone, delivery address are all required for a successful import).
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Full order preview",
            content = @Content(schema = @Schema(implementation = ErpPendingOrderPreviewDTO.class))),
        @ApiResponse(responseCode = "404", description = "Order not found in ERP", content = @Content)
    })
    public ResponseEntity<ErpPendingOrderPreviewDTO> getPendingOrderPreview(
            @Parameter(description = "ERP order reference (e.g. WH/OUT/00042)", example = "WH/OUT/00042", required = true)
            @RequestParam @Size(min = 1, max = 100) String erpOrderId
    ) {
        return ResponseEntity.ok(erpLookupService.getPendingOrderPreview(erpOrderId));
    }

    // ── Single Import ─────────────────────────────────────────────────────────

    @PostMapping("/import-order")
    @Operation(
        summary = "Import a single ERP order",
        description = """
            Imports one confirmed ERP order into ASM Track.

            **What happens on import:**
            - Creates an `Order` record with all client and product data
            - Creates a `Delivery` record with status `UNSCHEDULED`
            - Publishes a `delivery.created` WebSocket event
            - The order is then visible in the Deliveries board, ready to be assigned

            **Returns 409** if the order was already imported — safe to retry.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order imported — delivery created with status UNSCHEDULED"),
        @ApiResponse(responseCode = "404", description = "ERP order not found", content = @Content),
        @ApiResponse(responseCode = "409", description = "Order already imported — idempotent, safe to ignore", content = @Content),
        @ApiResponse(responseCode = "503", description = "ERP adapter unreachable", content = @Content)
    })
    public ResponseEntity<com.asm.delivery.dto.response.OrderResponse> importOrder(
            @Parameter(description = "ERP order reference (e.g. WH/OUT/00042)", example = "WH/OUT/00042", required = true)
            @RequestParam @Size(min = 1, max = 100) String erpOrderId
    ) {
        return ResponseEntity.ok(erpLookupService.importPendingOrder(erpOrderId));
    }

    // ── Bulk Import ───────────────────────────────────────────────────────────

    @PostMapping("/bulk-import")
    @Operation(
        summary = "Bulk import multiple ERP orders",
        description = """
            Imports multiple ERP orders in a single call. Each order is processed independently —
            if one fails (already imported, missing data), it is skipped and the others continue.

            The response tells you exactly how many were imported vs skipped.

            **Typical usage:** Admin selects multiple orders on the import page and clicks "Importer (N)".
            """
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
        description = "List of ERP order references to import",
        required = true,
        content = @Content(
            examples = @ExampleObject(value = "[\"S-00042\", \"S-00043\", \"S-00044\"]")
        )
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "Import summary",
            content = @Content(schema = @Schema(example = "{\"imported\": 3, \"skipped\": 0, \"requested\": 3}")))
    })
    public ResponseEntity<Map<String, Object>> bulkImport(
            @RequestBody @Valid @Size(min = 1, max = 100)
            List<@Size(min = 1, max = 100) String> erpOrderIds
    ) {
        return ResponseEntity.ok(erpLookupService.bulkImportOrders(erpOrderIds));
    }
}
