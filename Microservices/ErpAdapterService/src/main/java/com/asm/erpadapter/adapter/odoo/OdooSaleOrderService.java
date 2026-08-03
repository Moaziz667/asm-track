package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.adapter.odoo.workflow.CancelHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient.*;

/**
 * Reusable Odoo sale.order operations: confirm, cancel, resolve ID, post chatter notes,
 * and manage CRM tags.
 *
 * <p>Extracted from {@link OdooSyncAdapter} so that all workflows share the same
 * note-posting, tagging, and order-state logic without duplication.
 *
 * <p>Stateless — all Odoo state is read/written via {@link OdooJsonRpcClient}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OdooSaleOrderService {

    private final OdooJsonRpcClient rpc;
    private final CancelHandler cancelHandler;

    /** Cached id of the mail.mt_note subtype ("log note"), resolved lazily. */
    private volatile Integer noteSubtypeId;

    /** Cached id of the "Livraison Échouée" CRM tag, resolved lazily. */
    private volatile Integer deliveryFailedTagId;

    // ── Order ID resolution ───────────────────────────────────────────────────

    /**
     * Resolve an ERP order reference (e.g. "S00004") or numeric ID to the Odoo sale.order ID.
     * Tries numeric parse first, then falls back to a name search.
     */
    @SuppressWarnings("unchecked")
    public Integer resolveErpId(String erpOrderId) {
        if (erpOrderId == null || erpOrderId.isBlank()) return null;
        try {
            return Integer.parseInt(erpOrderId);
        } catch (Exception e) {
            Map<String, Object> response = rpc.callRpc(rpc.buildArgs("sale.order", "search",
                    List.of(List.of(List.of("name", "=", erpOrderId))), Map.of("limit", 1)));
            List<Integer> ids = (List<Integer>) response.get("result");
            return (ids != null && !ids.isEmpty()) ? ids.get(0) : null;
        }
    }

    /**
     * Resolve the sale order behind a reference that may be a <b>picking name</b>.
     *
     * <p>ASM imports BLs, so it stores the picking name ({@code WH/OUT/00338}) as the order's ERP
     * reference — and a {@code sale.order} is never named that (it is {@code S00244}). Resolving by
     * name alone therefore returned null for <em>every</em> BL-imported order, and callers turned
     * that into a silent {@code false}: POD and partial-delivery syncs failed before a single Odoo
     * write was attempted. Here we fall back to the picking and read its {@code sale_id}.
     *
     * @return the sale.order id, or {@code null} when the reference names a standalone picking with
     *         no sale order — a legitimate case, so callers must degrade rather than abort.
     */
    @SuppressWarnings("unchecked")
    public Integer resolveSaleOrderId(String erpOrderId, String pickingRef) {
        Integer direct = resolveErpId(erpOrderId);
        if (direct != null) return direct;

        for (String ref : new String[]{pickingRef, erpOrderId}) {
            if (ref == null || ref.isBlank()) continue;
            Map<String, Object> resp = rpc.callRpc(rpc.buildArgs("stock.picking", "search_read",
                    List.of(List.of(List.of("name", "=", ref))),
                    Map.of("fields", List.of("id", "sale_id"), "limit", 1)));
            if (resp == null || resp.containsKey("error")) continue;
            List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("result");
            if (rows == null || rows.isEmpty()) continue;
            Integer saleId = asRelId(rows.get(0).get("sale_id"));
            if (saleId != null) {
                log.debug("provider=odoo resolveSaleOrderId ref={} -> picking {} -> sale.order {}",
                        ref, rows.get(0).get("id"), saleId);
                return saleId;
            }
            log.info("provider=odoo resolveSaleOrderId ref={} -> picking {} has NO sale order "
                    + "(standalone picking) — sale-order steps will be skipped", ref, rows.get(0).get("id"));
            return null;
        }
        log.warn("provider=odoo resolveSaleOrderId ref={} pickingRef={} — neither a sale order nor a picking",
                erpOrderId, pickingRef);
        return null;
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    /**
     * Cancel a sale order. Delegates to {@link CancelHandler} for version-specific
     * unlock-before-cancel logic (Odoo 19 auto-locks confirmed orders).
     */
    public boolean cancelSaleOrder(Integer erpOrderId) {
        return cancelHandler.cancelSaleOrder(erpOrderId);
    }

    /**
     * Read the state of a sale order.
     */
    public String readSaleOrderState(Integer erpOrderId) {
        return rpc.readRecordState("sale.order", erpOrderId);
    }

    // ── Chatter notes ─────────────────────────────────────────────────────────

    /**
     * Post an HTML log note to the sale-order chatter. Uses {@code mail.message.create}
     * with {@code message_type=comment} so the HTML renders correctly (Odoo 17+ escapes
     * non-Markup bodies via {@code message_post}). Best-effort.
     */
    public void addNoteToSaleOrder(Integer erpId, String note) {
        Map<String, Object> vals = new HashMap<>();
        vals.put("model", "sale.order");
        vals.put("res_id", erpId);
        vals.put("body", note);
        vals.put("message_type", "comment");
        Integer subtype = resolveNoteSubtypeId();
        if (subtype != null) vals.put("subtype_id", subtype);
        rpc.callRpc(rpc.buildArgs("mail.message", "create", List.of(vals)));
    }

    /** Resolve mail.mt_note (the chatter "log note" subtype) once; null if unavailable. */
    private Integer resolveNoteSubtypeId() {
        if (noteSubtypeId != null) return noteSubtypeId;
        try {
            List<Map<String, Object>> rows = rpc.searchRead("ir.model.data",
                    List.of(List.of("module", "=", "mail"), List.of("name", "=", "mt_note")),
                    List.of("res_id"), 1, null);
            if (rows != null && !rows.isEmpty() && rows.get(0).get("res_id") instanceof Number n) {
                noteSubtypeId = n.intValue();
            }
        } catch (Exception e) {
            log.warn("provider=odoo operation=resolveNoteSubtypeId action=skip reason={}", e.getMessage());
        }
        return noteSubtypeId;
    }

    // ── CRM tags ──────────────────────────────────────────────────────────────

    /**
     * Add the "Livraison Échouée" tag to the sale order so dispatchers can filter failed
     * deliveries directly from the Odoo sale order list. Tag ID is resolved once and cached.
     */
    public void tagOrderAsDeliveryFailed(Integer erpId) {
        try {
            Integer tagId = resolveOrCreateDeliveryFailedTag();
            if (tagId == null) {
                log.warn("provider=odoo operation=tagOrderAsDeliveryFailed erpId={} reason=tag_id_null", erpId);
                return;
            }
            rpc.callRpc(rpc.buildArgs("sale.order", "write",
                    List.of(List.of(erpId), Map.of("tag_ids", List.of(List.of(4, tagId))))));
            log.info("provider=odoo operation=tagOrderAsDeliveryFailed erpId={} tagId={}", erpId, tagId);
        } catch (Exception e) {
            log.warn("provider=odoo operation=tagOrderAsDeliveryFailed erpId={} reason={}", erpId, e.getMessage());
        }
    }

    /** Lazily resolve or create the "Livraison Échouée" CRM tag. */
    private Integer resolveOrCreateDeliveryFailedTag() {
        if (deliveryFailedTagId != null) return deliveryFailedTagId;
        synchronized (this) {
            if (deliveryFailedTagId != null) return deliveryFailedTagId;
            final String tagName = "Livraison Échouée";

            Map<String, Object> searchResp = rpc.callRpc(rpc.buildArgs(
                    "crm.tag", "search_read",
                    List.of(List.of(List.of("name", "=", tagName))),
                    Map.of("fields", List.of("id"), "limit", 1)));
            List<?> found = searchResp != null ? (List<?>) searchResp.get("result") : null;
            if (found != null && !found.isEmpty()) {
                deliveryFailedTagId = asInt(((Map<?, ?>) found.get(0)).get("id"));
                log.info("provider=odoo operation=resolveOrCreateDeliveryFailedTag action=found tagId={}",
                        deliveryFailedTagId);
                return deliveryFailedTagId;
            }

            Map<String, Object> createResp = rpc.callRpc(rpc.buildArgs(
                    "crm.tag", "create", List.of(Map.of("name", tagName))));
            Object created = createResp != null ? createResp.get("result") : null;
            if (created instanceof Number n) {
                deliveryFailedTagId = n.intValue();
                log.info("provider=odoo operation=resolveOrCreateDeliveryFailedTag action=created tagId={}",
                        deliveryFailedTagId);
                return deliveryFailedTagId;
            }
            log.warn("provider=odoo operation=resolveOrCreateDeliveryFailedTag reason=create_returned_null response={}",
                    createResp);
            return null;
        }
    }
}
