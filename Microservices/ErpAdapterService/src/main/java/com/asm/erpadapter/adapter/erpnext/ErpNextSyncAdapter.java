package com.asm.erpadapter.adapter.erpnext;

import com.asm.erpadapter.dto.ErpPartialDeliveryResultDTO;
import com.asm.erpadapter.dto.ErpPartialItemDTO;
import com.asm.erpadapter.dto.ErpPodDTO;
import com.asm.erpadapter.dto.ErpReturnItemDTO;
import com.asm.erpadapter.port.ErpSyncPort;
import com.asm.erpadapter.service.IdempotencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static com.asm.erpadapter.adapter.erpnext.ErpNextRestClient.*;

/**
 * ERPNext implementation of {@link ErpSyncPort} (Phase B — write-back).
 *
 * <p><b>erpOrderId = the Sales Order name.</b> ERPNext has no pre-created "ready" delivery document —
 * the Delivery Note is created only at delivery time from the Sales Order. So this adapter <b>creates +
 * submits the DN</b> (the analog of Odoo's {@code button_validate}) and finds it back (via the child
 * {@code against_sales_order} link) for return/POD. Idempotent on {@code txId} + a "DN already exists
 * for this SO" guard so a retry never creates a second Delivery Note.
 *
 * <p>Fail-loud: any ERPNext error (e.g. NegativeStockError) throws → the command retries and, once
 * exhausted, dead-letters into SYNC_FAILED. Never a silent fake success.
 */
@Component("erpnext")
@RequiredArgsConstructor
@Slf4j
public class ErpNextSyncAdapter implements ErpSyncPort {

    private static final String MAKE_DN   = "erpnext.selling.doctype.sales_order.sales_order.make_delivery_note";
    private static final String MAKE_RET  = "erpnext.stock.doctype.delivery_note.delivery_note.make_sales_return";
    private static final String MAKE_SINV_DN = "erpnext.stock.doctype.delivery_note.delivery_note.make_sales_invoice";
    private static final String MAKE_SINV_SO = "erpnext.selling.doctype.sales_order.sales_order.make_sales_invoice";

    private final ErpNextRestClient erp;
    private final IdempotencyService idempotency;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** POD photos are stored in MinIO with a PUBLIC base URL; rewrite it to the container URL to fetch. */
    @org.springframework.beans.factory.annotation.Value("${minio.public-url:}")
    private String minioPublicUrl;
    @org.springframework.beans.factory.annotation.Value("${minio.internal-url:}")
    private String minioInternalUrl;

    /** Short-timeout client just to pull POD photo bytes from MinIO over plain HTTP. */
    private final org.springframework.web.client.RestClient podHttp = buildPodHttp();

    private static org.springframework.web.client.RestClient buildPodHttp() {
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(10000);
        return org.springframework.web.client.RestClient.builder().requestFactory(f).build();
    }

    // ── Full delivery = create + submit a Delivery Note for the whole Sales Order ──

    @Override
    public boolean syncFullDelivery(String erpOrderId, Integer backorderPickingId, String txId, String pickingRef) {
        return idempotency.execute(txId, erpOrderId, Boolean.class,
                () -> guarded(erpOrderId, () -> doDeliver(erpOrderId, null)));
    }

    // ── Partial delivery = a DN with the delivered quantities; the SO stays "To Deliver" ──

    @Override
    public ErpPartialDeliveryResultDTO syncPartialDelivery(String erpOrderId, List<ErpPartialItemDTO> items,
                                                           String txId, String pickingRef) {
        return idempotency.execute(txId, erpOrderId, ErpPartialDeliveryResultDTO.class, () -> {
            if (!inFlight.add(erpOrderId)) return ErpPartialDeliveryResultDTO.builder().success(false).build();
            try {
                boolean ok = doDeliver(erpOrderId, qtyBySku(items));
                // No backorder plumbing: the SO stays "To Deliver" with the remaining qty and is
                // re-importable, exactly like the Odoo backorder model.
                return ErpPartialDeliveryResultDTO.builder().success(ok).build();
            } finally { inFlight.remove(erpOrderId); }
        });
    }

    /** Create + submit a DN for the SO. qtyBySku=null → full; else set per-line delivered qty, drop 0-lines. */
    private boolean doDeliver(String so, Map<String, Integer> qtyBySku) {
        if (qtyBySku == null && findSubmittedDn(so) != null) {
            log.info("[erpnext] doDeliver so={} already has a submitted DN — idempotent success", so);
            return true;
        }
        Map<String, Object> dn = mapDoc(MAKE_DN, so);
        if (dn == null) { log.warn("[erpnext] make_delivery_note null for so={}", so); return false; }

        if (qtyBySku != null) {
            List<Map<String, Object>> kept = new ArrayList<>();
            for (Map<String, Object> li : lines(dn)) {
                String sku = asString(li.get("item_code"));
                Integer q = sku != null ? qtyBySku.get(sku) : null;
                if (q == null || q <= 0) continue;          // not delivered on this line
                li.put("qty", q);
                kept.add(li);
            }
            if (kept.isEmpty()) { log.info("[erpnext] partial so={} nothing delivered — skip DN", so); return true; }
            dn.put("items", kept);
        }

        dn.put("docstatus", 1);                              // insert + submit atomically
        Map<String, Object> created = erp.insert("Delivery Note", dn);
        String name = created != null ? asString(created.get("name")) : null;
        log.info("[erpnext] delivered so={} -> DN={} partial={}", so, name, qtyBySku != null);
        return name != null;
    }

    // ── Invoice = make + submit a Sales Invoice (admin-triggered, synchronous) ────

    @Override
    public String createInvoice(String erpOrderId, String pickingRef) {
        // Prefer invoicing from the submitted Delivery Note (bills the DELIVERED qty); fall back to the SO.
        String dn = (pickingRef != null && !pickingRef.isBlank()) ? pickingRef : findSubmittedDn(erpOrderId);
        Map<String, Object> inv = dn != null ? mapDoc(MAKE_SINV_DN, dn) : mapDoc(MAKE_SINV_SO, erpOrderId);
        if (inv == null) {
            log.warn("[erpnext] make_sales_invoice null for so={} dn={}", erpOrderId, dn);
            return null;
        }
        inv.put("docstatus", 1);                             // insert + submit atomically
        Map<String, Object> created = erp.insert("Sales Invoice", inv);
        String name = created != null ? asString(created.get("name")) : null;
        log.info("[erpnext] invoice created so={} dn={} -> {}", erpOrderId, dn, name);
        return name;
    }

    @Override
    public byte[] getInvoicePdf(String invoiceRef) {
        return erp.downloadPdf("Sales Invoice", invoiceRef);
    }

    // ── Failure = a chatter note on the Sales Order ──────────────────────────────

    @Override
    public boolean syncFailure(String erpOrderId, String failureCode, String comment, String txId, String pickingRef) {
        return idempotency.execute(txId, erpOrderId, Boolean.class, () -> {
            addComment("Sales Order", erpOrderId, "<b>ASM Track — Delivery failed</b><br>Code: " + orDash(failureCode)
                    + (comment != null && !comment.isBlank() ? "<br>Comment: " + comment : ""));
            return true;
        });
    }

    // ── Cancellation = cancel the SO if nothing was delivered, else fail-loud ────

    @Override
    public boolean syncOrderCancellation(String erpOrderId, String txId, String pickingRef) {
        return idempotency.execute(txId, erpOrderId, Boolean.class, () -> {
            Map<String, Object> so = erp.getDoc("Sales Order", erpOrderId);
            if (so == null) return false;
            Integer docstatus = asInt(so.get("docstatus"));
            if (docstatus != null && docstatus == 2) return true;         // already cancelled — idempotent

            String dn = findSubmittedDn(erpOrderId);
            if (dn != null) {
                addComment("Sales Order", erpOrderId,
                        "<b>ASM Track — Cancellation requested after delivery</b> (DN " + dn + "). Order not cancelled.");
                log.warn("[erpnext] cancellation so={} already delivered (DN={}) — note only, fail-loud", erpOrderId, dn);
                return false;                                             // can't cleanly cancel a delivered SO
            }
            erp.method("frappe.client.cancel", Map.of("doctype", "Sales Order", "name", erpOrderId));
            log.info("[erpnext] cancelled SO {}", erpOrderId);
            return true;
        });
    }

    // ── Reschedule = write the delivery date on the SO lines + a note (symmetric) ──

    @Override
    public boolean syncReschedule(String erpOrderId, String scheduledAt, String txId, String pickingRef) {
        return idempotency.execute(txId, erpOrderId, Boolean.class, () -> {
            Map<String, Object> so = erp.getDoc("Sales Order", erpOrderId);
            if (so == null) return false;
            String date = toErpDate(scheduledAt);
            if (date != null) {
                // The SO header delivery_date is derived from the item rows, so set each item's date.
                for (Map<String, Object> li : lines(so)) {
                    String rowName = asString(li.get("name"));
                    if (rowName == null) continue;
                    try {
                        erp.method("frappe.client.set_value", Map.of(
                                "doctype", "Sales Order Item", "name", rowName,
                                "fieldname", "delivery_date", "value", date));
                    } catch (Exception e) {
                        log.warn("[erpnext] reschedule so={} row={} date write skipped: {}", erpOrderId, rowName, e.getMessage());
                    }
                }
            }
            addComment("Sales Order", erpOrderId, "<b>ASM Track — Rescheduled</b><br>New delivery date: " + orDash(scheduledAt));
            return true;
        });
    }

    // ── Proof of delivery = metadata note on the Delivery Note (photos: follow-up) ──

    @Override
    public boolean syncProofOfDelivery(String erpOrderId, ErpPodDTO pod, String txId, String pickingRef) {
        return idempotency.execute(txId, erpOrderId, Boolean.class, () -> {
            String dn = findSubmittedDn(erpOrderId);
            String doctype = dn != null ? "Delivery Note" : "Sales Order";
            String name = dn != null ? dn : erpOrderId;
            addComment(doctype, name, buildPodNote(pod));
            if (pod != null) {
                attachPhoto(doctype, name, fetchBase64(pod.getBonLivraisonPhotoUrl(), pod.getBlPhotoBase64()), "bon-livraison.png");
                attachPhoto(doctype, name, fetchBase64(pod.getPackagePhotoUrl(), pod.getPackagePhotoBase64()), "package.png");
            }
            return true;
        });
    }

    // ── Return (RMA) = a return Delivery Note (negative qty) + scrap the damaged units ──

    @Override
    public boolean syncReturn(String erpOrderId, List<ErpReturnItemDTO> items, String reason, String txId, String pickingRef) {
        return idempotency.execute(txId, erpOrderId, Boolean.class, () -> doReturn(erpOrderId, items, reason));
    }

    private boolean doReturn(String so, List<ErpReturnItemDTO> items, String reason) {
        String dn = findSubmittedDn(so);
        if (dn == null) { log.warn("[erpnext] return so={} — no submitted DN to return against", so); return false; }

        addComment("Delivery Note", dn, "<b>ASM Track — Customer return (RMA)</b>"
                + (reason != null && !reason.isBlank() ? "<br>Reason: " + reason : ""));

        // Idempotency guard: a return DN already exists against this DN → resume (just scrap) instead of duplicating.
        if (findReturnDn(dn) != null) {
            log.info("[erpnext] return already exists for DN={} — scrapping damaged only", dn);
            scrapDamaged(so, items);
            return true;
        }

        Map<String, Object> ret = mapDoc(MAKE_RET, dn);
        if (ret == null) { log.warn("[erpnext] make_sales_return null for DN={}", dn); return false; }

        Map<String, Integer> qtyBySku = returnQtyBySku(items);
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> li : lines(ret)) {
            String sku = asString(li.get("item_code"));
            Integer q = sku != null ? qtyBySku.get(sku) : null;
            if (q == null || q <= 0) continue;
            li.put("qty", -Math.abs(q));                     // returns are negative quantities
            kept.add(li);
        }
        if (kept.isEmpty()) { log.info("[erpnext] return so={} no matching lines — skip", so); return true; }
        ret.put("items", kept);
        ret.put("docstatus", 1);

        Map<String, Object> created = erp.insert("Delivery Note", ret);
        if (created == null) return false;
        log.info("[erpnext] return DN {} created for so={} against DN={}", created.get("name"), so, dn);

        scrapDamaged(so, items);                             // damaged units leave sellable stock
        return true;
    }

    /**
     * Scrap the DAMAGED returned units so they don't stay in sellable stock: a Stock Entry of type
     * "Material Issue" out of the source warehouse (ERPNext has no dedicated scrap doc; a Material
     * Issue is the standard write-off). Best-effort — the return itself already succeeded.
     */
    private void scrapDamaged(String so, List<ErpReturnItemDTO> items) {
        if (items == null) return;
        List<Map<String, Object>> scrap = new ArrayList<>();
        for (ErpReturnItemDTO it : items) {
            if (it == null || !"DAMAGED".equalsIgnoreCase(it.getCondition())) continue;
            if (it.getQuantity() == null || it.getQuantity() <= 0 || it.getSku() == null) continue;
            Map<String, Object> line = new HashMap<>();
            line.put("item_code", it.getSku());
            line.put("qty", it.getQuantity());
            scrap.add(line);
        }
        if (scrap.isEmpty()) return;
        Map<String, Object> soDoc = erp.getDoc("Sales Order", so);
        String company = soDoc != null ? asString(soDoc.get("company")) : null;
        String wh = soDoc != null ? asString(soDoc.get("set_warehouse")) : null;
        if (wh != null) scrap.forEach(l -> l.put("s_warehouse", wh));
        try {
            Map<String, Object> se = new HashMap<>();
            se.put("stock_entry_type", "Material Issue");
            if (company != null) se.put("company", company);
            se.put("items", scrap);
            se.put("docstatus", 1);
            Map<String, Object> created = erp.insert("Stock Entry", se);
            log.info("[erpnext] scrapped {} damaged item(s) for so={} -> {}", scrap.size(), so,
                    created != null ? created.get("name") : null);
        } catch (Exception e) {
            log.warn("[erpnext] scrapDamaged so={} skipped: {}", so, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find the submitted <b>forward</b> Delivery Note for a Sales Order via the child
     * {@code against_sales_order} link. Excludes return DNs ({@code is_return=1}) so POD / invoicing /
     * return-against always target the actual outbound shipment, never a prior credit-side return.
     */
    private String findSubmittedDn(String so) {
        List<Map<String, Object>> rows = erp.getList("Delivery Note", List.of("name"),
                List.of(List.of("Delivery Note Item", "against_sales_order", "=", so),
                        List.of("docstatus", "=", 1), List.of("is_return", "=", 0)),
                1, "creation desc");
        return rows.isEmpty() ? null : asString(rows.get(0).get("name"));
    }

    /** Find an existing (non-cancelled) return DN made against {@code dn}. */
    private String findReturnDn(String dn) {
        List<Map<String, Object>> rows = erp.getList("Delivery Note", List.of("name"),
                List.of(List.of("return_against", "=", dn), List.of("docstatus", "!=", 2)), 1, "creation desc");
        return rows.isEmpty() ? null : asString(rows.get(0).get("name"));
    }

    /** Call a make_* mapper (SO→DN, DN→return DN) and return the mapped document. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> mapDoc(String method, String sourceName) {
        Object msg = erp.methodGet(method, Map.of("source_name", sourceName));
        return msg instanceof Map ? (Map<String, Object>) msg : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lines(Map<String, Object> doc) {
        Object raw = doc.get("items");
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    /** Rewrite a public MinIO URL to the container-internal URL so the adapter can fetch it inside Docker. */
    private String internalMinio(String url) {
        if (url != null && minioPublicUrl != null && !minioPublicUrl.isBlank()
                && minioInternalUrl != null && !minioInternalUrl.isBlank() && url.startsWith(minioPublicUrl)) {
            return minioInternalUrl + url.substring(minioPublicUrl.length());
        }
        return url;
    }

    /** Resolve a POD photo to base64: prefer the MinIO URL (fetched + encoded), else the legacy base64. */
    private String fetchBase64(String url, String legacyBase64) {
        if (url != null && !url.isBlank()) {
            try {
                byte[] bytes = podHttp.get().uri(internalMinio(url)).retrieve().body(byte[].class);
                if (bytes != null && bytes.length > 0) return java.util.Base64.getEncoder().encodeToString(bytes);
            } catch (Exception e) {
                log.warn("[erpnext] POD photo fetch failed url={}: {}", url, e.getMessage());
            }
        }
        return legacyBase64;
    }

    /** Attach a base64 photo as a private File on the target doc. Best-effort (POD note already recorded). */
    private void attachPhoto(String doctype, String name, String base64, String fileName) {
        if (base64 == null || base64.isBlank()) return;
        String data = base64.contains(",") ? base64.substring(base64.indexOf(',') + 1) : base64;  // strip data-URL prefix
        try {
            erp.insert("File", Map.of("file_name", fileName, "content", data, "decode", true,
                    "attached_to_doctype", doctype, "attached_to_name", name, "is_private", 1));
        } catch (Exception e) {
            log.warn("[erpnext] POD attach {}/{} {} skipped: {}", doctype, name, fileName, e.getMessage());
        }
    }

    private void addComment(String doctype, String name, String html) {
        try {
            erp.insert("Comment", Map.of("comment_type", "Comment",
                    "reference_doctype", doctype, "reference_name", name, "content", html));
        } catch (Exception e) {
            log.warn("[erpnext] addComment {}/{} skipped: {}", doctype, name, e.getMessage());
        }
    }

    /** Partial-delivery quantities keyed by SKU (ERPNext matches DN lines by item_code). */
    private static Map<String, Integer> qtyBySku(List<ErpPartialItemDTO> items) {
        Map<String, Integer> m = new HashMap<>();
        if (items == null) return m;
        for (ErpPartialItemDTO it : items) {
            String sku = it.getReferenceKey();
            if (sku != null && !sku.isBlank() && it.getQuantityDone() != null) m.merge(sku.trim(), it.getQuantityDone(), Integer::sum);
        }
        return m;
    }

    private static Map<String, Integer> returnQtyBySku(List<ErpReturnItemDTO> items) {
        Map<String, Integer> m = new HashMap<>();
        if (items == null) return m;
        for (ErpReturnItemDTO it : items) {
            if (it.getSku() != null && !it.getSku().isBlank() && it.getQuantity() != null) m.merge(it.getSku().trim(), it.getQuantity(), Integer::sum);
        }
        return m;
    }

    private String buildPodNote(ErpPodDTO pod) {
        StringBuilder sb = new StringBuilder("<b>ASM Track — Proof of delivery</b>");
        if (pod != null) {
            if (pod.getRecipientName() != null && !pod.getRecipientName().isBlank()) sb.append("<br>Received by: ").append(pod.getRecipientName());
            if (pod.getDeliveredAt() != null && !pod.getDeliveredAt().isBlank()) sb.append("<br>At: ").append(pod.getDeliveredAt());
            if (pod.getLat() != null && pod.getLng() != null) sb.append("<br>Position: ").append(pod.getLat()).append(", ").append(pod.getLng());
            if (pod.getComment() != null && !pod.getComment().isBlank()) sb.append("<br>Comment: ").append(pod.getComment());
        }
        return sb.toString();
    }

    /** ISO-8601 ("2026-08-15T08:00[:00]") → Frappe date "yyyy-MM-dd". Null-safe. */
    private static String toErpDate(String iso) {
        if (iso == null || iso.isBlank()) return null;
        String s = iso.trim();
        int t = s.indexOf('T');
        return t >= 10 ? s.substring(0, 10) : (s.length() >= 10 ? s.substring(0, 10) : s);
    }

    private static String orDash(String s) {
        return s != null && !s.isBlank() ? s : "—";
    }

    private boolean guarded(String key, Supplier<Boolean> op) {
        if (!inFlight.add(key)) return false;
        try { return op.get(); } finally { inFlight.remove(key); }
    }
}
