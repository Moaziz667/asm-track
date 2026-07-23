package com.asm.erpadapter.adapter.odoo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient.*;

/**
 * Reusable Odoo picking (stock.picking) read/write operations.
 *
 * <p>Extracted from {@link OdooSyncAdapter} to avoid duplicating picking queries
 * across workflows. Every method is stateless — all state lives in Odoo via JSON-RPC.
 *
 * <p>Naming convention: methods prefixed with {@code find} return a picking map or null;
 * methods prefixed with {@code read} return a scalar field; methods prefixed with
 * {@code cancel} mutate state.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OdooPickingService {

    private final OdooJsonRpcClient rpc;

    // ── Picking lookups ───────────────────────────────────────────────────────

    /**
     * Find a single pending (not done/cancel) picking for a sale order.
     * Orders by id asc to always get the oldest pending picking — deterministic on backorder chains.
     * Uses {@link OdooJsonRpcClient#callRpcOrThrow} so a transport timeout is a retryable error,
     * not a false "no picking".
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> findSinglePicking(Integer erpOrderId) {
        Map<String, Object> response = rpc.callRpcOrThrow(rpc.buildArgs("stock.picking", "search_read",
                List.of(List.of(
                        List.of("sale_id", "=", erpOrderId),
                        List.of("state", "not in", List.of("done", "cancel")))),
                Map.of("fields", List.of("id", "state"), "limit", 1, "order", "id asc")));
        List<Map<String, Object>> result = (List<Map<String, Object>>) response.get("result");
        return (result != null && !result.isEmpty()) ? result.get(0) : null;
    }

    /**
     * Find a picking by its Odoo ID.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> findPickingById(Integer pickingId) {
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.picking", "search_read",
                List.of(List.of(List.of("id", "=", pickingId))),
                Map.of("fields", List.of("id", "state"), "limit", 1)));
        List<Map<String, Object>> result = (List<Map<String, Object>>) response.get("result");
        return (result != null && !result.isEmpty()) ? result.get(0) : null;
    }

    /**
     * Find a picking by its delivery-note number (BL), e.g. "WH/OUT/00012".
     * Multi-depot precision — targets the exact delivery note.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> findPickingByName(String name) {
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.picking", "search_read",
                List.of(List.of(List.of("name", "=", name))),
                Map.of("fields", List.of("id", "state"), "limit", 1)));
        List<Map<String, Object>> result = response != null ? (List<Map<String, Object>>) response.get("result") : null;
        return (result != null && !result.isEmpty()) ? result.get(0) : null;
    }

    /**
     * Find the most recent DONE outgoing picking for a sale order — the fallback return source.
     * Uses {@link OdooJsonRpcClient#callRpcOrThrow} so transport errors surface as exceptions.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> findDonePicking(Integer erpOrderId) {
        Map<String, Object> response = rpc.callRpcOrThrow(rpc.buildArgs("stock.picking", "search_read",
                List.of(List.of(
                        List.of("sale_id", "=", erpOrderId),
                        List.of("state", "=", "done"))),
                Map.of("fields", List.of("id", "state"), "limit", 1, "order", "id desc")));
        List<Map<String, Object>> result = response != null ? (List<Map<String, Object>>) response.get("result") : null;
        return (result != null && !result.isEmpty()) ? result.get(0) : null;
    }

    /**
     * Find the backorder picking created from an origin picking.
     */
    @SuppressWarnings("unchecked")
    public Integer findBackorderPickingId(Integer originPickingId) {
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.picking", "search_read",
                List.of(List.of(List.of("backorder_id", "=", originPickingId))),
                Map.of("fields", List.of("id"), "limit", 1)));
        List<Map<String, Object>> result = (List<Map<String, Object>>) response.get("result");
        return (result != null && !result.isEmpty()) ? asInt(result.get(0).get("id")) : null;
    }

    // ── Picking state reads ───────────────────────────────────────────────────

    /**
     * Read the state field of a picking.
     */
    public String readPickingState(Integer pickingId) {
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.picking", "read",
                List.of(List.of(pickingId), List.of("state"))));
        List<Map<String, Object>> result = (List<Map<String, Object>>) response.get("result");
        return (result != null && !result.isEmpty()) ? (String) result.get(0).get("state") : null;
    }

    /**
     * Read a picking's delivery-note (BL) name by id, e.g. "WH/OUT/00013". Null on any failure.
     */
    @SuppressWarnings("unchecked")
    public String readPickingName(Integer pickingId) {
        if (pickingId == null) return null;
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.picking", "read",
                List.of(List.of(pickingId), List.of("name"))));
        List<Map<String, Object>> result = response != null ? (List<Map<String, Object>>) response.get("result") : null;
        return (result != null && !result.isEmpty()) ? asString(result.get(0).get("name")) : null;
    }

    // ── Picking mutations ─────────────────────────────────────────────────────

    /**
     * Check if a sale order already has a DONE picking (idempotent success detection).
     * Uses {@link OdooJsonRpcClient#callRpcOrThrow} so transport errors are not mistaken for "no done picking".
     */
    @SuppressWarnings("unchecked")
    public boolean hasDonePicking(Integer erpOrderId) {
        Map<String, Object> response = rpc.callRpcOrThrow(rpc.buildArgs("stock.picking", "search_read",
                List.of(List.of(
                        List.of("sale_id", "=", erpOrderId),
                        List.of("state", "=", "done"))),
                Map.of("fields", List.of("id"), "limit", 1)));
        List<?> result = (List<?>) response.get("result");
        return result != null && !result.isEmpty();
    }

    /**
     * Cancel a single delivery note (picking). 'done' is treated as idempotent success.
     */
    public boolean cancelPicking(Integer pickingId) {
        Map<String, Object> resp = rpc.callRpc(rpc.buildArgs("stock.picking", "action_cancel",
                List.of(List.of(pickingId))));
        if (resp != null && resp.containsKey("error")) {
            log.warn("ERP sync failed — provider=odoo operation=cancelPicking pickingId={} odooError={} retryable=true",
                    pickingId, resp.get("error"));
            return false;
        }
        String state = readPickingState(pickingId);
        log.info("provider=odoo operation=cancelPicking pickingId={} finalState={}", pickingId, state);
        return "cancel".equalsIgnoreCase(state) || "done".equalsIgnoreCase(state);
    }
}
