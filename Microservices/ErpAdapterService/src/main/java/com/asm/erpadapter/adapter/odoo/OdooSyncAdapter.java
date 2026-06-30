package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.dto.ErpPartialDeliveryResultDTO;
import com.asm.erpadapter.dto.ErpPartialItemDTO;
import com.asm.erpadapter.dto.ErpPodDTO;
import com.asm.erpadapter.dto.ErpReturnItemDTO;
import com.asm.erpadapter.port.ErpSyncPort;
import com.asm.erpadapter.service.IdempotencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient.*;

/**
 * Odoo implementation of {@link ErpSyncPort}.
 * Refactored for 'Exactly-Once' delivery using IdempotencyService.
 */
@Component("odoo")
@RequiredArgsConstructor
@Slf4j
public class OdooSyncAdapter implements ErpSyncPort {

    private final OdooJsonRpcClient rpc;
    private final IdempotencyService idempotency;

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private volatile Integer deliveryFailedTagId;

    /**
     * Small HTTP client used only to fetch POD photo bytes from MinIO URLs (V1.1). Short timeouts:
     * MinIO is on the local docker network. The adapter has no MinIO SDK/credentials — the objects are
     * served over plain HTTP via MINIO_PUBLIC_URL.
     */
    private final org.springframework.web.client.RestClient podHttpClient = buildPodHttpClient();

    /**
     * MinIO is stored with its PUBLIC base URL (reachable from the browser, e.g. http://localhost:9000),
     * but the adapter runs in another container where "localhost" is itself. These map the stored public
     * base to the container-network base (e.g. http://minio:9000) so the fetch works inside Docker.
     */
    @org.springframework.beans.factory.annotation.Value("${minio.public-url:}")
    private String minioPublicUrl;
    @org.springframework.beans.factory.annotation.Value("${minio.internal-url:}")
    private String minioInternalUrl;

    /** Rewrites a public MinIO URL to the internal container URL when both are configured. */
    private String internalMinioUrl(String url) {
        if (url != null && minioPublicUrl != null && !minioPublicUrl.isBlank()
                && minioInternalUrl != null && !minioInternalUrl.isBlank()
                && url.startsWith(minioPublicUrl)) {
            return minioInternalUrl + url.substring(minioPublicUrl.length());
        }
        return url;
    }

    private static org.springframework.web.client.RestClient buildPodHttpClient() {
        org.springframework.http.client.SimpleClientHttpRequestFactory f =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(10000);
        return org.springframework.web.client.RestClient.builder().requestFactory(f).build();
    }

    /** Per-picking in-flight key so two pickings of the same sale order don't block each other. */
    private static String inFlightKey(String erpOrderId, String pickingRef) {
        return (pickingRef != null && !pickingRef.isBlank()) ? erpOrderId + "|" + pickingRef : erpOrderId;
    }

    @Override
    public boolean syncOrderCancellation(String erpOrderId, String transactionId, String pickingRef) {
        return idempotency.execute(transactionId, erpOrderId, Boolean.class, () -> {
            String key = inFlightKey(erpOrderId, pickingRef);
            if (!inFlight.add(key)) return false;
            try { return doSyncOrderCancellation(erpOrderId, pickingRef); }
            finally { inFlight.remove(key); }
        });
    }

    @Override
    public boolean syncFullDelivery(String erpOrderId, Integer backorderPickingId, String transactionId, String pickingRef) {
        return idempotency.execute(transactionId, erpOrderId, Boolean.class, () -> {
            String key = inFlightKey(erpOrderId, pickingRef);
            if (!inFlight.add(key)) return false;
            try { return doSyncFullDelivery(erpOrderId, backorderPickingId, pickingRef); }
            finally { inFlight.remove(key); }
        });
    }

    @Override
    public ErpPartialDeliveryResultDTO syncPartialDelivery(String erpOrderId, List<ErpPartialItemDTO> items, String transactionId, String pickingRef) {
        return idempotency.execute(transactionId, erpOrderId, ErpPartialDeliveryResultDTO.class, () -> {
            String key = inFlightKey(erpOrderId, pickingRef);
            if (!inFlight.add(key)) return ErpPartialDeliveryResultDTO.builder().success(false).build();
            try { return doSyncPartialDelivery(erpOrderId, items, pickingRef); }
            finally { inFlight.remove(key); }
        });
    }

    @Override
    public boolean syncFailure(String erpOrderId, String failureCode, String comment, String transactionId, String pickingRef) {
        return idempotency.execute(transactionId, erpOrderId, Boolean.class, () -> {
            String key = inFlightKey(erpOrderId, pickingRef);
            if (!inFlight.add(key)) return false;
            try { return doSyncFailure(erpOrderId, failureCode, comment); }
            finally { inFlight.remove(key); }
        });
    }

    @Override
    public boolean syncProofOfDelivery(String erpOrderId, ErpPodDTO pod, String transactionId, String pickingRef) {
        return idempotency.execute(transactionId, erpOrderId, Boolean.class, () -> {
            String key = inFlightKey(erpOrderId, pickingRef);
            if (!inFlight.add(key)) return false;
            try { return doSyncProofOfDelivery(erpOrderId, pod); }
            finally { inFlight.remove(key); }
        });
    }

    @Override
    public boolean syncReturn(String erpOrderId, List<ErpReturnItemDTO> items, String reason, String transactionId, String pickingRef) {
        return idempotency.execute(transactionId, erpOrderId, Boolean.class, () -> {
            String key = inFlightKey(erpOrderId, pickingRef);
            if (!inFlight.add(key)) return false;
            try { return doSyncReturn(erpOrderId, items, reason, pickingRef); }
            finally { inFlight.remove(key); }
        });
    }

    @Override
    public boolean syncReschedule(String erpOrderId, String scheduledAt, String transactionId, String pickingRef) {
        return idempotency.execute(transactionId, erpOrderId, Boolean.class, () -> {
            String key = inFlightKey(erpOrderId, pickingRef);
            if (!inFlight.add(key)) return false;
            try { return doSyncReschedule(erpOrderId, scheduledAt); }
            finally { inFlight.remove(key); }
        });
    }


    // ══════════════════════════════════════════════════════════════════════════
    //  Core Sync Logic (Internal)
    // ══════════════════════════════════════════════════════════════════════════

    private boolean doSyncOrderCancellation(String erpOrderId, String pickingRef) {
        // Multi-depot: cancel only the targeted delivery note (picking), leaving sibling
        // pickings of the same sale order intact. Legacy single-picking → cancel the order.
        if (pickingRef != null && !pickingRef.isBlank()) {
            Map<String, Object> picking = findPickingByName(pickingRef);
            if (picking == null) {
                log.warn("ERP sync failed — provider=odoo operation=syncOrderCancellation pickingRef={} reason=picking_not_found retryable=false", pickingRef);
                return false;
            }
            return cancelPicking(((Number) picking.get("id")).intValue());
        }

        Integer erpId = resolveErpId(erpOrderId);
        if (erpId == null) return false;

        int attempts = 0;
        boolean success = false;
        while (attempts < 3 && !success) {
            success = cancelSaleOrder(erpId);
            attempts++;
        }
        return success;
    }

    private boolean doSyncFullDelivery(String erpOrderId, Integer backorderPickingId, String pickingRef) {
        // Backorder path: when we already know the Odoo picking ID, skip the sale order lookup.
        if (backorderPickingId != null) {
            try {
                boolean transferOk = validateTransferByPickingId(backorderPickingId);
                if (!transferOk) {
                    log.warn("ERP sync failed — provider=odoo operation=syncFullDelivery (backorder) erpOrderId={} backorderPickingId={} reason=transfer_validation_failed retryable=true",
                            erpOrderId, backorderPickingId);
                    return false;
                }
                return true;
            } catch (Exception e) {
                log.error("ERP sync exception — provider=odoo operation=syncFullDelivery (backorder) erpOrderId={} backorderPickingId={} errorClass={} reason={} retryable=true",
                        erpOrderId, backorderPickingId, e.getClass().getSimpleName(), e.getMessage(), e);
                return false;
            }
        }

        // Multi-depot: validate the exact delivery note targeted by its BL number.
        if (pickingRef != null && !pickingRef.isBlank()) {
            Map<String, Object> picking = findPickingByName(pickingRef);
            if (picking == null) {
                log.warn("ERP sync failed — provider=odoo operation=syncFullDelivery pickingRef={} reason=picking_not_found retryable=false", pickingRef);
                return false;
            }
            try {
                return validateTransferByPickingId(((Number) picking.get("id")).intValue());
            } catch (Exception e) {
                log.error("ERP sync exception — provider=odoo operation=syncFullDelivery pickingRef={} errorClass={} reason={} retryable=true",
                        pickingRef, e.getClass().getSimpleName(), e.getMessage(), e);
                return false;
            }
        }

        Integer erpId = resolveErpId(erpOrderId);
        if (erpId == null) return false;

        try {
            boolean transferOk = validateTransfer(erpId, null);
            if (!transferOk) {
                log.warn("ERP sync failed — provider=odoo operation=syncFullDelivery erpOrderId={} erpId={} reason=transfer_validation_failed retryable=true",
                        erpOrderId, erpId);
                return false;
            }
            syncSaleOrderLineDeliveredQuantities(erpId, null, true);
            return true;
        } catch (Exception e) {
            log.error("ERP sync exception — provider=odoo operation=syncFullDelivery erpOrderId={} erpId={} errorClass={} reason={} retryable=true",
                    erpOrderId, erpId, e.getClass().getSimpleName(), e.getMessage(), e);
            return false;
        }
    }

    private ErpPartialDeliveryResultDTO doSyncPartialDelivery(String erpOrderId, List<ErpPartialItemDTO> items, String pickingRef) {
        Integer erpId = resolveErpId(erpOrderId);
        if (erpId == null) return ErpPartialDeliveryResultDTO.builder().success(false).build();

        try {
            confirmOrderIfNeeded(erpId);
            // Multi-depot: target the exact delivery note by BL number when provided,
            // otherwise fall back to the single-picking resolution (legacy behaviour).
            Map<String, Object> picking = (pickingRef != null && !pickingRef.isBlank())
                    ? findPickingByName(pickingRef)
                    : findSinglePicking(erpId);
            if (picking == null) {
                log.warn("ERP sync failed — provider=odoo operation=syncPartialDelivery erpOrderId={} erpId={} pickingRef={} reason=no_picking_found retryable=true",
                        erpOrderId, erpId, pickingRef);
                return ErpPartialDeliveryResultDTO.builder().success(false).build();
            }

            Integer pickingId = ((Number) picking.get("id")).intValue();
            String state = (String) picking.get("state");

            if ("done".equals(state)) {
                // Retry path: picking already validated — sync qty lines and return success.
                syncSaleOrderLineDeliveredQuantities(erpId, items, false);
                Integer bo = findBackorderPickingId(pickingId);
                return ErpPartialDeliveryResultDTO.builder()
                        .success(true).pickingId(pickingId).backorderPickingId(bo)
                        .backorderBlNumber(readPickingName(bo)).build();
            }

            reserveStock(pickingId);
            // If stock not reservable (already reserved elsewhere), force availability
            String stateAfterReserve = readPickingState(pickingId);
            if (!"assigned".equalsIgnoreCase(stateAfterReserve)) {
                log.info("provider=odoo operation=syncPartialDelivery pickingId={} state={} action=force_availability",
                        pickingId, stateAfterReserve);
                rpc.callRpc(rpc.buildArgs("stock.picking", "action_force_availability", List.of(List.of(pickingId))));
            }
            // Write done quantities to stock.move.line — match by product default_code fetched live from Odoo.
            // Returns total qty_done written across all storable move lines.
            int totalQtyDone = applyPartialQtyDoneToMoveLines(pickingId, items);

            if (totalQtyDone == 0) {
                // All storable items were refused/damaged — nothing to move in stock.
                // Validating a zero-quantity picking throws a UserError in Odoo.
                // Leave the picking open (it becomes the re-delivery picking), just post a note.
                String allRefusedNote = buildPartialDeliveryNote(items);
                if (allRefusedNote != null) addNoteToSaleOrder(erpId, allRefusedNote);
                log.info("provider=odoo operation=syncPartialDelivery pickingId={} action=skip_validation reason=all_qty_zero", pickingId);
                return ErpPartialDeliveryResultDTO.builder().success(true).pickingId(pickingId).backorderPickingId(null).build();
            }

            Map<String, Object> validateResponse = callValidatePicking(pickingId);
            if (validateResponse == null || validateResponse.containsKey("error")) {
                Object odooError = validateResponse != null ? validateResponse.get("error") : "null_response";
                log.warn("ERP sync failed — provider=odoo operation=syncPartialDelivery erpOrderId={} erpId={} pickingId={} odooError={} retryable=true",
                        erpOrderId, erpId, pickingId, odooError);
                return ErpPartialDeliveryResultDTO.builder().success(false).build();
            }

            // Handle wizard returned by button_validate.
            // For backorder confirmation: bypass the wizard and call _action_done directly.
            // confirmBackorderWizard(res) calls wizard.process() which calls _action_done internally,
            // but it fails when moves are in 'partially_available' state because process() silently errors.
            // Calling _action_done directly handles partially_available moves correctly.
            if (validateResponse.get("result") instanceof Map<?, ?> res) {
                String resModel = (String) res.get("res_model");
                log.info("provider=odoo operation=syncPartialDelivery pickingId={} wizard={} action=confirming", pickingId, resModel);
                if ("stock.backorder.confirmation".equals(resModel)) {
                    confirmBackorderWizard(res);
                } else if ("confirm.stock.sms".equals(resModel)) {
                    confirmSmsWizard(res);
                } else if ("stock.immediate.transfer".equals(resModel)) {
                    // Do NOT call confirmImmediateTransferWizard here — its process() would fill all
                    // move lines back to the reserved quantity, overriding the qty_done=0 we wrote for
                    // refused/damaged items. Call _action_done directly to validate with our quantities.
                    log.info("provider=odoo operation=syncPartialDelivery pickingId={} wizard=immediate_transfer action=_action_done_direct", pickingId);
                    rpc.callRpc(rpc.buildArgs("stock.picking", "_action_done", List.of(List.of(pickingId))));
                }
            }

            syncSaleOrderLineDeliveredQuantities(erpId, items, false);

            // Post a structured chatter note listing refused/damaged items with reasons
            String partialNote = buildPartialDeliveryNote(items);
            if (partialNote != null) {
                addNoteToSaleOrder(erpId, partialNote);
            }

            Integer backorderPickingId = findBackorderPickingId(pickingId);
            log.info("provider=odoo operation=syncPartialDelivery pickingId={} backorderPickingId={}", pickingId, backorderPickingId);
            return ErpPartialDeliveryResultDTO.builder()
                    .success(true).pickingId(pickingId).backorderPickingId(backorderPickingId)
                    .backorderBlNumber(readPickingName(backorderPickingId)).build();
        } catch (Exception e) {
            log.error("ERP sync exception — provider=odoo operation=syncPartialDelivery erpOrderId={} erpId={} errorClass={} reason={} retryable=true",
                    erpOrderId, erpId, e.getClass().getSimpleName(), e.getMessage(), e);
            return ErpPartialDeliveryResultDTO.builder().success(false).build();
        }
    }

    /**
     * V3.3 — Writes the new committed delivery date onto the Odoo sale order ({@code commitment_date})
     * so the ERP's promised date matches the platform after a re-plan. Best-effort: also posts a chatter
     * note. {@code scheduledAt} is ISO-8601 ("2026-06-11T08:00[:00]"); Odoo wants "YYYY-MM-DD HH:MM:SS".
     */
    private boolean doSyncReschedule(String erpOrderId, String scheduledAt) {
        Integer erpId = resolveErpId(erpOrderId);
        if (erpId == null) return false;
        String odooDt = toOdooDateTime(scheduledAt);
        if (odooDt != null) {
            Map<String, Object> resp = rpc.callRpc(rpc.buildArgs("sale.order", "write",
                    List.of(List.of(erpId), Map.of("commitment_date", odooDt))));
            if (resp != null && resp.containsKey("error")) {
                log.warn("ERP sync failed — provider=odoo operation=syncReschedule erpId={} odooError={} retryable=true",
                        erpId, resp.get("error"));
                return false;
            }
        }
        addNoteToSaleOrder(erpId, "<b>ASM Track — Replanification</b><br/>Nouvelle date de livraison : "
                + (scheduledAt != null ? scheduledAt : "—"));
        log.info("provider=odoo operation=syncReschedule erpId={} commitment_date={}", erpId, odooDt);
        return true;
    }

    /** Converts ISO-8601 ("2026-06-11T08:00[:00]") to Odoo's "YYYY-MM-DD HH:MM:SS". Null-safe. */
    private String toOdooDateTime(String iso) {
        if (iso == null || iso.isBlank()) return null;
        String s = iso.trim().replace('T', ' ');
        int dot = s.indexOf('.');
        if (dot > 0) s = s.substring(0, dot);          // strip fractional seconds
        if (s.length() == 16) s = s + ":00";            // add seconds if "YYYY-MM-DD HH:MM"
        return s;
    }

    private boolean doSyncFailure(String erpOrderId, String failureCode, String comment) {
        Integer erpId = resolveErpId(erpOrderId);
        if (erpId == null) return false;

        String note = buildFailureNote(failureCode, comment);
        addNoteToSaleOrder(erpId, note);
        tagOrderAsDeliveryFailed(erpId);
        return true;
    }

    private String humanizeReason(String reason) {
        if (reason == null) return "";
        return switch (reason.toUpperCase()) {
            case "CLIENT_ABSENT"   -> "Client absent";
            case "CLIENT_REJECTED" -> "Client a refusé";
            case "DAMAGED"         -> "Endommagé";
            case "WRONG_ITEM"      -> "Mauvais article";
            case "POSTPONED"       -> "Reporté";
            default                -> reason;
        };
    }

    private String buildFailureNote(String failureCode, String comment) {
        StringBuilder sb = new StringBuilder("<b>ASM Track — Livraison échouée</b><br/>");
        sb.append("<b>Code :</b> ").append(failureCode != null ? failureCode : "UNKNOWN").append("<br/>");
        if (comment != null && !comment.isBlank()) {
            sb.append("<b>Commentaire :</b> ").append(comment);
        }
        return sb.toString();
    }

    // ── Proof of delivery ──────────────────────────────────────────────────────

    private boolean doSyncProofOfDelivery(String erpOrderId, ErpPodDTO pod) {
        Integer erpId = resolveErpId(erpOrderId);
        if (erpId == null) return false;
        if (pod == null) pod = ErpPodDTO.builder().build();

        // Attach the photos (best-effort). Prefer the MinIO URL (fetch bytes over HTTP); fall back to
        // inline base64 for legacy events. Tolerant of missing/invalid sources.
        createPodAttachment(erpId, resolvePhotoBase64(pod.getBonLivraisonPhotoUrl(), pod.getBlPhotoBase64()), "bon-livraison.png");
        createPodAttachment(erpId, resolvePhotoBase64(pod.getPackagePhotoUrl(), pod.getPackagePhotoBase64()), "package.png");

        // Post the metadata note to the chatter.
        addNoteToSaleOrder(erpId, buildPodNote(pod));
        return true;
    }

    /**
     * Resolves a POD photo to base64. Prefers the MinIO {@code url} (fetched over HTTP and base64-encoded);
     * falls back to {@code legacyBase64} when no URL is given. Returns null if neither yields bytes — the
     * caller then simply skips that attachment (POD note + the other photo still go through).
     */
    private String resolvePhotoBase64(String url, String legacyBase64) {
        if (url != null && !url.isBlank()) {
            try {
                byte[] bytes = podHttpClient.get().uri(internalMinioUrl(url)).retrieve().body(byte[].class);
                if (bytes != null && bytes.length > 0) {
                    return java.util.Base64.getEncoder().encodeToString(bytes);
                }
                log.warn("provider=odoo operation=syncPod action=fetch_empty url={}", url);
            } catch (Exception e) {
                log.warn("provider=odoo operation=syncPod action=fetch_failed url={} reason={}", url, e.getMessage());
            }
        }
        return legacyBase64;
    }

    private void createPodAttachment(Integer erpId, String base64, String name) {
        if (base64 == null || base64.isBlank()) return;
        // Strip a possible data-URL prefix (data:image/png;base64,....).
        String data = base64.contains(",") ? base64.substring(base64.indexOf(',') + 1) : base64;
        try {
            Map<String, Object> values = new HashMap<>();
            values.put("name", name);
            values.put("datas", data);
            values.put("res_model", "sale.order");
            values.put("res_id", erpId);
            values.put("mimetype", "image/png");
            rpc.callRpc(rpc.buildArgs("ir.attachment", "create", List.of(values)));
        } catch (Exception e) {
            log.warn("provider=odoo operation=syncPod erpId={} attachment={} action=skip reason={}", erpId, name, e.getMessage());
        }
    }

    // ── Returns (RMA) ──────────────────────────────────────────────────────────

    private boolean doSyncReturn(String erpOrderId, List<ErpReturnItemDTO> items, String reason, String pickingRef) {
        Integer erpId = resolveErpId(erpOrderId);
        if (erpId == null) return false;

        // Record the return on the sale-order chatter (a human-readable trace in the ERP). This is only a
        // trace — STRICT policy: a return is reported successful ONLY when a validated reverse stock move
        // actually puts the goods back. A note alone never counts as success.
        addNoteToSaleOrder(erpId, buildReturnNote(items, reason));

        return createReturnPicking(erpId, items, pickingRef);
    }

    /**
     * Performs the reverse stock move for a return and returns true ONLY when the return picking is created
     * AND validated to 'done'. Any failure (no source picking, wizard unavailable, picking not validated)
     * returns false so the RMA is marked SYNC_FAILED upstream — never a silent fake SYNCED.
     */
    @SuppressWarnings("unchecked")
    private boolean createReturnPicking(Integer erpId, List<ErpReturnItemDTO> items, String pickingRef) {
        // A return is taken against the DONE outgoing picking (the goods actually delivered). Prefer the
        // exact picking named by the RMA's BL (pickingRef) so a multi-shipment order returns against the
        // correct shipment; fall back to the most recent done picking only when the ref is absent/unknown.
        Map<String, Object> picking = findReturnSourcePicking(erpId, pickingRef);
        if (picking == null) {
            log.warn("ERP sync failed — provider=odoo operation=syncReturn erpId={} pickingRef={} reason=no_done_picking retryable=true", erpId, pickingRef);
            return false;
        }
        Integer pickingId = ((Number) picking.get("id")).intValue();

        // Idempotency guard (mirrors the outbound "already done" validate path). The upstream idempotency
        // cache only stores SUCCESSES, and create_returns has visible side effects: a prior attempt that
        // created the reverse picking but failed before/at validation — or a manual resync (new txId) —
        // would otherwise create a SECOND return picking on every retry. If a (non-cancelled) return
        // picking already exists against this source, resume/validate THAT one instead of creating another.
        Integer existingReturnId = findExistingReturnPicking(pickingId);
        if (existingReturnId != null) {
            boolean alreadyDone = "done".equalsIgnoreCase(readPickingState(existingReturnId));
            log.info("provider=odoo operation=syncReturn erpId={} sourcePickingId={} existingReturnPickingId={} alreadyDone={} action=resume_existing",
                    erpId, pickingId, existingReturnId, alreadyDone);
            boolean done = alreadyDone || validateTransferByPickingId(existingReturnId);
            if (!done) {
                log.warn("ERP sync failed — provider=odoo operation=syncReturn erpId={} existingReturnPickingId={} reason=existing_return_not_done retryable=true", erpId, existingReturnId);
                return false;
            }
            // Scrap only when WE just validated it — an already-done return was scrapped on its original pass,
            // and scrapping twice would double-remove the damaged units from stock.
            if (!alreadyDone) scrapDamagedReturnedItems(erpId, items);
            return true;
        }

        // Create the return-picking wizard bound to the source picking; Odoo pre-fills the returnable
        // move lines (stock.return.picking.line) via default_get.
        Map<String, Object> ctx = Map.of("active_id", pickingId, "active_model", "stock.picking", "active_ids", List.of(pickingId));
        Map<String, Object> wizardResp = rpc.callRpc(rpc.buildArgs("stock.return.picking", "create",
                List.of(Map.of("picking_id", pickingId)), Map.of("context", ctx)));
        Integer wizardId = asInt(wizardResp != null ? wizardResp.get("result") : null);
        if (wizardId == null) {
            log.warn("ERP sync failed — provider=odoo operation=syncReturn erpId={} pickingId={} reason=wizard_unavailable retryable=true", erpId, pickingId);
            return false;
        }

        // Respect the RMA quantities per line instead of returning the whole picking. Read the wizard's
        // pre-filled lines, match each to an RMA item by the product's default_code, set its return
        // quantity, and zero out lines not in the RMA.
        applyReturnQuantities(wizardId, items);

        // create_returns builds the reverse picking and returns an ir.actions.act_window referencing it.
        Map<String, Object> returnResp = rpc.callRpc(rpc.buildArgs("stock.return.picking", "create_returns",
                List.of(List.of(wizardId)), Map.of("context", ctx)));
        Integer returnPickingId = extractReturnPickingId(returnResp);
        if (returnPickingId == null) {
            log.warn("ERP sync failed — provider=odoo operation=syncReturn erpId={} wizardId={} reason=return_picking_id_unresolved retryable=true result={}",
                    erpId, wizardId, returnResp != null ? returnResp.get("result") : null);
            return false;
        }
        log.info("provider=odoo operation=syncReturn erpId={} sourcePickingId={} returnPickingId={} action=return_created", erpId, pickingId, returnPickingId);

        // Validate the return picking so the goods actually re-enter stock — a draft create_returns moves
        // nothing on its own. Reuses the same button_validate + wizard handling as outbound transfers.
        boolean done = validateTransferByPickingId(returnPickingId);
        if (!done) {
            log.warn("ERP sync failed — provider=odoo operation=syncReturn erpId={} returnPickingId={} reason=return_picking_not_done retryable=true", erpId, returnPickingId);
            return false;
        }

        // The goods are back on-hand now → DAMAGED units must not stay in sellable stock; scrap them
        // AFTER validation (before it, there is nothing on-hand to scrap).
        scrapDamagedReturnedItems(erpId, items);
        return true;
    }

    /**
     * Resolves the DONE outgoing picking a return is taken against. Prefers the exact picking named by the
     * RMA's BL reference (correct shipment on multi-picking orders); falls back to the most recent done
     * picking when the ref is missing or doesn't match.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findReturnSourcePicking(Integer erpOrderId, String pickingRef) {
        if (pickingRef != null && !pickingRef.isBlank()) {
            Map<String, Object> response = rpc.callRpcOrThrow(rpc.buildArgs("stock.picking", "search_read",
                    List.of(List.of(
                            List.of("sale_id", "=", erpOrderId),
                            List.of("state", "=", "done"),
                            List.of("name", "=", pickingRef.trim()))),
                    Map.of("fields", List.of("id", "state", "name"), "limit", 1)));
            List<Map<String, Object>> result = response != null ? (List<Map<String, Object>>) response.get("result") : null;
            if (result != null && !result.isEmpty()) {
                log.info("provider=odoo operation=findReturnSourcePicking erpOrderId={} pickingRef={} action=matched_by_name", erpOrderId, pickingRef);
                return result.get(0);
            }
            log.info("provider=odoo operation=findReturnSourcePicking erpOrderId={} pickingRef={} action=name_no_match fallback=most_recent_done", erpOrderId, pickingRef);
        }
        return findDonePicking(erpOrderId);
    }

    /**
     * Extracts the new return-picking id from a create_returns response. Odoo returns an
     * ir.actions.act_window referencing the created picking — usually via res_id, sometimes only via a
     * domain like [('id','in',[id,...])]; a few versions return the id directly.
     */
    @SuppressWarnings("unchecked")
    private Integer extractReturnPickingId(Map<String, Object> returnResp) {
        Object result = returnResp != null ? returnResp.get("result") : null;
        if (!(result instanceof Map<?, ?> action)) return asInt(result);
        Integer resId = asInt(action.get("res_id"));
        if (resId != null && resId > 0) return resId;
        Object domain = action.get("domain");
        if (domain instanceof List<?> clauses) {
            for (Object clause : clauses) {
                if (clause instanceof List<?> triplet && triplet.size() == 3 && "id".equals(triplet.get(0))) {
                    Object val = triplet.get(2);
                    if (val instanceof Number n) return n.intValue();
                    if (val instanceof List<?> ids && !ids.isEmpty() && ids.get(0) instanceof Number n) return n.intValue();
                }
            }
        }
        return null;
    }

    /**
     * Returns the id of a return picking already created against {@code sourcePickingId}, or null if none.
     * Detection is version-stable: each reverse move carries {@code origin_returned_move_id} pointing at the
     * original move, so we walk source moves → reverse moves → their pickings. Cancelled return pickings are
     * ignored; a {@code done} one is preferred (it means the goods already re-entered stock — idempotent
     * success). Used to make the reverse stock move retry/resync-safe (no duplicate return pickings).
     */
    @SuppressWarnings("unchecked")
    private Integer findExistingReturnPicking(Integer sourcePickingId) {
        Map<String, Object> srcMovesResp = rpc.callRpc(rpc.buildArgs("stock.move", "search_read",
                List.of(List.of(List.of("picking_id", "=", sourcePickingId))),
                Map.of("fields", List.of("id"))));
        List<Map<String, Object>> srcMoves = srcMovesResp != null ? (List<Map<String, Object>>) srcMovesResp.get("result") : null;
        if (srcMoves == null || srcMoves.isEmpty()) return null;
        List<Integer> srcMoveIds = srcMoves.stream().map(m -> asInt(m.get("id")))
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (srcMoveIds.isEmpty()) return null;

        // Reverse moves point back at the source moves via origin_returned_move_id.
        Map<String, Object> retMovesResp = rpc.callRpc(rpc.buildArgs("stock.move", "search_read",
                List.of(List.of(List.of("origin_returned_move_id", "in", srcMoveIds))),
                Map.of("fields", List.of("picking_id"))));
        List<Map<String, Object>> retMoves = retMovesResp != null ? (List<Map<String, Object>>) retMovesResp.get("result") : null;
        if (retMoves == null || retMoves.isEmpty()) return null;
        List<Integer> retPickingIds = retMoves.stream().map(m -> asRelId(m.get("picking_id")))
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (retPickingIds.isEmpty()) return null;

        // Skip cancelled return pickings; prefer a done one (idempotent success), else any live one.
        Map<String, Object> pickResp = rpc.callRpc(rpc.buildArgs("stock.picking", "search_read",
                List.of(List.of(List.of("id", "in", retPickingIds))),
                Map.of("fields", List.of("id", "state"))));
        List<Map<String, Object>> picks = pickResp != null ? (List<Map<String, Object>>) pickResp.get("result") : null;
        if (picks == null || picks.isEmpty()) return null;
        Integer firstLive = null;
        for (Map<String, Object> p : picks) {
            String state = (String) p.get("state");
            if ("cancel".equalsIgnoreCase(state)) continue;
            Integer id = asInt(p.get("id"));
            if ("done".equalsIgnoreCase(state)) return id;
            if (firstLive == null) firstLive = id;
        }
        return firstLive;
    }

    /** Finds the most recent DONE outgoing picking for a sale order — the fallback return source. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findDonePicking(Integer erpOrderId) {
        // V3.1: throw on transport error so a timeout isn't mistaken for "no done picking to return".
        Map<String, Object> response = rpc.callRpcOrThrow(rpc.buildArgs("stock.picking", "search_read",
                List.of(List.of(List.of("sale_id", "=", erpOrderId), List.of("state", "=", "done"))),
                Map.of("fields", List.of("id", "state"), "limit", 1, "order", "id desc")));
        List<Map<String, Object>> result = response != null ? (List<Map<String, Object>>) response.get("result") : null;
        return (result != null && !result.isEmpty()) ? result.get(0) : null;
    }

    /**
     * Sets the return quantity on each {@code stock.return.picking.line} from the RMA items (matched by
     * the product's {@code default_code}). Lines not present in the RMA are set to 0 so only the
     * customer's actual returned quantities are reversed — not the whole picking.
     */
    @SuppressWarnings("unchecked")
    private void applyReturnQuantities(Integer wizardId, List<ErpReturnItemDTO> items) {
        if (items == null || items.isEmpty()) return;
        // Build sku -> qty from the RMA.
        Map<String, Integer> skuToQty = new HashMap<>();
        for (ErpReturnItemDTO it : items) {
            if (it.getSku() != null && !it.getSku().isBlank() && it.getQuantity() != null) {
                skuToQty.merge(it.getSku().trim(), it.getQuantity(), Integer::sum);
            }
        }
        if (skuToQty.isEmpty()) return;

        // Read the wizard's pre-filled return lines + their products.
        Map<String, Object> linesResp = rpc.callRpc(rpc.buildArgs("stock.return.picking.line", "search_read",
                List.of(List.of(List.of("wizard_id", "=", wizardId))),
                Map.of("fields", List.of("id", "product_id", "quantity"))));
        List<Map<String, Object>> lines = linesResp != null ? (List<Map<String, Object>>) linesResp.get("result") : null;
        if (lines == null || lines.isEmpty()) {
            log.info("provider=odoo operation=applyReturnQuantities wizardId={} action=no_lines", wizardId);
            return;
        }
        // Resolve default_code for the products on these lines.
        List<Integer> productIds = lines.stream().map(l -> asRelId(l.get("product_id")))
                .filter(java.util.Objects::nonNull).distinct().collect(java.util.stream.Collectors.toList());
        Map<Integer, String> pidToSku = new HashMap<>();
        if (!productIds.isEmpty()) {
            Map<String, Object> prodResp = rpc.callRpc(rpc.buildArgs("product.product", "search_read",
                    List.of(List.of(List.of("id", "in", productIds))),
                    Map.of("fields", List.of("id", "default_code"))));
            List<Map<String, Object>> prods = prodResp != null ? (List<Map<String, Object>>) prodResp.get("result") : null;
            if (prods != null) for (Map<String, Object> p : prods) {
                Integer pid = asInt(p.get("id"));
                Object dc = p.get("default_code");
                if (pid != null && dc instanceof String s && !s.isBlank()) pidToSku.put(pid, s.trim());
            }
        }
        // Write the RMA quantity onto each line (0 when not in the RMA).
        for (Map<String, Object> line : lines) {
            Integer pid = asRelId(line.get("product_id"));
            String sku = pid != null ? pidToSku.get(pid) : null;
            int qty = (sku != null && skuToQty.containsKey(sku)) ? skuToQty.get(sku) : 0;
            rpc.callRpc(rpc.buildArgs("stock.return.picking.line", "write",
                    List.of(List.of(line.get("id")), Map.of("quantity", qty))));
        }
        log.info("provider=odoo operation=applyReturnQuantities wizardId={} lines={} skuToQty={}", wizardId, lines.size(), skuToQty);
    }

    /**
     * Scraps the DAMAGED returned units so they leave sellable inventory. Best-effort: creates a
     * {@code stock.scrap} record per damaged line and validates it. Tolerant of Odoo config differences
     * (the return note already records the damaged condition as the authoritative trace).
     */
    private void scrapDamagedReturnedItems(Integer erpId, List<ErpReturnItemDTO> items) {
        if (items == null) return;
        for (ErpReturnItemDTO it : items) {
            if (it == null || !"DAMAGED".equalsIgnoreCase(it.getCondition())) continue;
            if (it.getQuantity() == null || it.getQuantity() <= 0) continue;
            Integer productId = resolveProductId(it.getSku(), it.getName());
            if (productId == null) {
                log.info("provider=odoo operation=scrapDamaged erpId={} sku={} action=skip reason=product_not_found", erpId, it.getSku());
                continue;
            }
            try {
                Map<String, Object> scrapResp = rpc.callRpc(rpc.buildArgs("stock.scrap", "create",
                        List.of(Map.of("product_id", productId, "scrap_qty", it.getQuantity()))));
                Integer scrapId = asInt(scrapResp != null ? scrapResp.get("result") : null);
                if (scrapId != null) {
                    rpc.callRpc(rpc.buildArgs("stock.scrap", "action_validate", List.of(List.of(scrapId))));
                    log.info("provider=odoo operation=scrapDamaged erpId={} sku={} qty={} scrapId={} action=scrapped",
                            erpId, it.getSku(), it.getQuantity(), scrapId);
                }
            } catch (Exception e) {
                log.warn("provider=odoo operation=scrapDamaged erpId={} sku={} action=skip reason={}", erpId, it.getSku(), e.getMessage());
            }
        }
    }

    private String buildReturnNote(List<ErpReturnItemDTO> items, String reason) {
        StringBuilder sb = new StringBuilder("<b>ASM Track — Retour client (RMA)</b><br/>");
        if (reason != null && !reason.isBlank()) sb.append("<b>Motif :</b> ").append(reason).append("<br/>");
        if (items != null && !items.isEmpty()) {
            sb.append("<b>Articles retournés :</b><ul>");
            for (ErpReturnItemDTO it : items) {
                sb.append("<li>")
                  .append(it.getQuantity() != null ? it.getQuantity() : "?").append("× ")
                  .append(it.getName() != null ? it.getName() : (it.getSku() != null ? it.getSku() : "Article"))
                  .append(it.getCondition() != null ? " (" + it.getCondition() + ")" : "")
                  .append("</li>");
            }
            sb.append("</ul>");
        }
        return sb.toString();
    }

    private String buildPodNote(ErpPodDTO pod) {
        StringBuilder sb = new StringBuilder("<b>ASM Track — Preuve de livraison</b><br/>");
        if (pod.getRecipientName() != null && !pod.getRecipientName().isBlank()) {
            sb.append("<b>Reçu par :</b> ").append(pod.getRecipientName()).append("<br/>");
        }
        if (pod.getDeliveredAt() != null && !pod.getDeliveredAt().isBlank()) {
            sb.append("<b>Horodatage :</b> ").append(pod.getDeliveredAt()).append("<br/>");
        }
        if (pod.getLat() != null && pod.getLng() != null) {
            sb.append("<b>Position :</b> ").append(pod.getLat()).append(", ").append(pod.getLng()).append("<br/>");
        }
        if (pod.getComment() != null && !pod.getComment().isBlank()) {
            sb.append("<b>Commentaire :</b> ").append(pod.getComment());
        }
        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Odoo JSON-RPC Low-Level (Private)
    // ══════════════════════════════════════════════════════════════════════════

    private void confirmOrder(Integer erpOrderId) {
        rpc.callRpc(rpc.buildArgs("sale.order", "action_confirm", List.of(List.of(erpOrderId))));
    }

    /**
     * V4.1 — Confirm the sale order only when it is still a draft/sent quotation. Re-running
     * action_confirm on an already-confirmed order is at best a no-op and at worst throws on some Odoo
     * versions, so we read the state first.
     */
    private void confirmOrderIfNeeded(Integer erpOrderId) {
        String state = readSaleOrderState(erpOrderId);
        if ("draft".equalsIgnoreCase(state) || "sent".equalsIgnoreCase(state)) {
            confirmOrder(erpOrderId);
        }
    }

    private boolean cancelSaleOrder(Integer erpOrderId) {
        // Odoo 19 auto-locks confirmed orders — unlock before cancelling.
        rpc.callRpc(rpc.buildArgs("sale.order", "action_unlock", List.of(List.of(erpOrderId))));

        Map<String, Object> resp = rpc.callRpc(rpc.buildArgs("sale.order", "action_cancel", List.of(List.of(erpOrderId))));
        if (resp != null && resp.containsKey("error")) {
            log.warn("ERP sync failed — provider=odoo operation=cancelSaleOrder erpId={} odooError={} retryable=true",
                    erpOrderId, resp.get("error"));
            return false;
        }

        String state = readSaleOrderState(erpOrderId);
        log.info("provider=odoo operation=cancelSaleOrder erpId={} finalState={}", erpOrderId, state);
        return "cancel".equalsIgnoreCase(state);
    }


    private boolean validateTransfer(Integer erpOrderId, Integer explicitPickingId) {
        confirmOrderIfNeeded(erpOrderId);
        Map<String, Object> picking = (explicitPickingId != null)
                ? findPickingById(explicitPickingId)
                : findSinglePicking(erpOrderId);

        if (picking == null) {
            // No pending picking — check if one is already done (idempotent success)
            if (hasDonePicking(erpOrderId)) {
                log.info("provider=odoo operation=validateTransfer erpOrderId={} state=already_done action=idempotent_success", erpOrderId);
                return true;
            }
            log.warn("ERP sync failed — provider=odoo operation=validateTransfer erpOrderId={} reason=no_picking_found retryable=false",
                    erpOrderId);
            return false;
        }

        Integer pickingId = ((Number) picking.get("id")).intValue();
        if ("done".equals(picking.get("state"))) return true;

        reserveStock(pickingId);
        // If stock not available, force availability so move lines are created
        String stateAfterReserve = readPickingState(pickingId);
        if ("confirmed".equalsIgnoreCase(stateAfterReserve)) {
            log.info("provider=odoo operation=validateTransfer pickingId={} state=confirmed action=force_availability", pickingId);
            rpc.callRpc(rpc.buildArgs("stock.picking", "action_force_availability", List.of(List.of(pickingId))));
        }
        setFullQuantityDoneOnMoveLines(pickingId);

        // Check if picking has any stock.move records — service products have none
        Map<String, Object> movesResp = rpc.callRpc(rpc.buildArgs("stock.move", "search_read",
                List.of(List.of(List.of("picking_id", "=", pickingId))),
                Map.of("fields", List.of("id", "state", "product_id", "product_uom_qty"), "limit", 1)));
        List<?> moves = movesResp != null ? (List<?>) movesResp.get("result") : null;
        if (moves == null || moves.isEmpty()) {
            log.info("provider=odoo operation=validateTransfer pickingId={} info=no_stock_moves_service_order action=skip_validation", pickingId);
            return true;
        }

        // skip_sms=True bypasses the confirm.stock.sms wizard in Odoo 17/18
        Map<String, Object> validateResp = rpc.callRpc(
                rpc.buildArgs("stock.picking", "button_validate",
                        List.of(List.of(pickingId)),
                        Map.of("context", Map.of("skip_sms", true, "skip_immediate", true))));

        Object rawResult = validateResp != null ? validateResp.get("result") : null;

        if (rawResult instanceof Map<?, ?> res) {
            String resModel = (String) res.get("res_model");
            log.info("provider=odoo operation=validateTransfer pickingId={} wizard={} action=confirming", pickingId, resModel);
            if ("stock.immediate.transfer".equals(resModel)) {
                confirmImmediateTransferWizard(res);
            } else if ("stock.backorder.confirmation".equals(resModel)) {
                confirmBackorderWizard(res);
            } else if ("confirm.stock.sms".equals(resModel)) {
                // skip_sms didn't work on this Odoo version — confirm wizard directly
                confirmSmsWizard(res);
            } else {
                log.warn("provider=odoo operation=validateTransfer pickingId={} unhandled_wizard={} result={}",
                        pickingId, resModel, res);
            }
        }

        String finalState = readPickingState(pickingId);
        if (!"done".equalsIgnoreCase(finalState)) {
            log.warn("ERP sync failed — provider=odoo operation=validateTransfer erpOrderId={} pickingId={} finalState={} reason=picking_not_done retryable=true",
                    erpOrderId, pickingId, finalState);
            return false;
        }
        return true;
    }

    /** Validates a picking directly by its Odoo ID — used for backorder deliveries where the picking ID is already known. */
    private boolean validateTransferByPickingId(Integer pickingId) {
        Map<String, Object> picking = findPickingById(pickingId);
        if (picking == null) {
            log.warn("ERP sync failed — provider=odoo operation=validateTransferByPickingId pickingId={} reason=picking_not_found retryable=false", pickingId);
            return false;
        }
        if ("done".equals(picking.get("state"))) return true;

        reserveStock(pickingId);
        String stateAfterReserve = readPickingState(pickingId);
        if ("confirmed".equalsIgnoreCase(stateAfterReserve)) {
            log.info("provider=odoo operation=validateTransferByPickingId pickingId={} state=confirmed action=force_availability", pickingId);
            rpc.callRpc(rpc.buildArgs("stock.picking", "action_force_availability", List.of(List.of(pickingId))));
        }
        setFullQuantityDoneOnMoveLines(pickingId);

        Map<String, Object> movesResp = rpc.callRpc(rpc.buildArgs("stock.move", "search_read",
                List.of(List.of(List.of("picking_id", "=", pickingId))),
                Map.of("fields", List.of("id"), "limit", 1)));
        List<?> moves = movesResp != null ? (List<?>) movesResp.get("result") : null;
        if (moves == null || moves.isEmpty()) return true;

        Map<String, Object> validateResp = rpc.callRpc(
                rpc.buildArgs("stock.picking", "button_validate",
                        List.of(List.of(pickingId)),
                        Map.of("context", Map.of("skip_sms", true, "skip_immediate", true))));
        Object rawResult = validateResp != null ? validateResp.get("result") : null;
        if (rawResult instanceof Map<?, ?> res) {
            String resModel = (String) res.get("res_model");
            log.info("provider=odoo operation=validateTransferByPickingId pickingId={} wizard={} action=confirming", pickingId, resModel);
            if ("stock.immediate.transfer".equals(resModel)) confirmImmediateTransferWizard(res);
            else if ("stock.backorder.confirmation".equals(resModel)) confirmBackorderWizard(res);
            else if ("confirm.stock.sms".equals(resModel)) confirmSmsWizard(res);
        }
        String finalState = readPickingState(pickingId);
        log.info("provider=odoo operation=validateTransferByPickingId pickingId={} finalState={}", pickingId, finalState);
        return "done".equalsIgnoreCase(finalState);
    }

    private boolean hasDonePicking(Integer erpOrderId) {
        // V3.1: throw on transport error so we never report "no done picking" because of a timeout.
        Map<String, Object> response = rpc.callRpcOrThrow(rpc.buildArgs("stock.picking", "search_read",
                List.of(List.of(List.of("sale_id", "=", erpOrderId), List.of("state", "=", "done"))),
                Map.of("fields", List.of("id"), "limit", 1)));
        List<?> result = (List<?>) response.get("result");
        return result != null && !result.isEmpty();
    }

    private void confirmSmsWizard(Map<?, ?> res) {
        // confirm.stock.sms: "Validate without SMS" = action_confirm in Odoo 17, action_validate in some versions
        try {
            Object resId = res.get("res_id");
            if (resId instanceof Number wid) {
                // Try action_confirm first (Odoo 17), fall back to action_send_and_validate
                Map<String, Object> resp = rpc.callRpc(rpc.buildArgs(
                        "confirm.stock.sms", "action_confirm", List.of(List.of(wid.intValue()))));
                if (resp != null && resp.containsKey("error")) {
                    rpc.callRpc(rpc.buildArgs(
                            "confirm.stock.sms", "action_send_and_validate", List.of(List.of(wid.intValue()))));
                }
                log.info("provider=odoo operation=confirmSmsWizard wizardId={} action=confirmed_no_sms", wid.intValue());
            }
        } catch (Exception e) {
            log.warn("provider=odoo operation=confirmSmsWizard reason={}", e.getMessage());
        }
    }

    private void confirmImmediateTransferWizard(Map<?, ?> res) {
        try {
            Object resId = res.get("res_id");
            if (resId instanceof Number wid) {
                rpc.callRpc(rpc.buildArgs("stock.immediate.transfer", "process",
                        List.of(List.of(wid.intValue()))));
                log.info("provider=odoo operation=confirmImmediateTransferWizard wizardId={} action=confirmed", wid.intValue());
            } else {
                log.warn("provider=odoo operation=confirmImmediateTransferWizard reason=no_res_id res={}", res);
            }
        } catch (Exception e) {
            log.warn("provider=odoo operation=confirmImmediateTransferWizard reason={}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void confirmBackorderWizard(Map<?, ?> res) {
        try {
            Object resId = res.get("res_id");
            if (resId instanceof Number wid) {
                // Odoo 16/17: wizard record already exists, just call process
                rpc.callRpc(rpc.buildArgs("stock.backorder.confirmation", "process",
                        List.of(List.of(wid.intValue()))));
                log.info("provider=odoo operation=confirmBackorderWizard wizardId={} action=confirmed", wid.intValue());
                return;
            }
            // Odoo 18: returns ir.actions.act_window with no res_id — context has button_validate_picking_ids
            // We must create the wizard record ourselves, then call process.
            Object ctxObj = res.get("context");
            List<Integer> pickingIds = null;
            if (ctxObj instanceof Map<?, ?> ctx) {
                Object bvIds = ctx.get("button_validate_picking_ids");
                if (bvIds instanceof List<?> list && !list.isEmpty()) {
                    pickingIds = (List<Integer>) list;
                }
            }
            if (pickingIds == null || pickingIds.isEmpty()) {
                log.warn("provider=odoo operation=confirmBackorderWizard reason=no_picking_ids_in_context res={}", res);
                return;
            }
            // Create wizard with pick_ids AND backorder_confirmation_line_ids.
            // @api.onchange('pick_ids') does NOT fire via RPC, so we must create the lines
            // explicitly — otherwise process() iterates an empty lines collection and does nothing.
            List<List<Object>> pickIdsCmd = new java.util.ArrayList<>();
            List<List<Object>> linesCmd = new java.util.ArrayList<>();
            for (Integer pid : pickingIds) {
                pickIdsCmd.add(List.of(4, pid));
                linesCmd.add(List.of(0, 0, Map.of("picking_id", pid, "to_backorder", true)));
            }
            Map<String, Object> wizardVals = new java.util.HashMap<>();
            wizardVals.put("pick_ids", pickIdsCmd);
            wizardVals.put("backorder_confirmation_line_ids", linesCmd);
            Map<String, Object> createResp = rpc.callRpc(rpc.buildArgs(
                    "stock.backorder.confirmation", "create", List.of(wizardVals)));
            Object wizardId = createResp != null ? createResp.get("result") : null;
            if (wizardId instanceof Number wid2) {
                // Odoo 19: process() reads button_validate_picking_ids from context.
                // Without it, process() returns True immediately and does nothing.
                Map<String, Object> processResp = rpc.callRpc(rpc.buildArgs(
                        "stock.backorder.confirmation", "process",
                        List.of(List.of(wid2.intValue())),
                        Map.of("context", Map.of("button_validate_picking_ids", pickingIds, "skip_sms", true))));
                if (processResp != null && processResp.containsKey("error")) {
                    log.warn("provider=odoo operation=confirmBackorderWizard wizardId={} process_error={}", wid2.intValue(), processResp.get("error"));
                } else {
                    log.info("provider=odoo operation=confirmBackorderWizard wizardId={} pickingIds={} process_result={} action=confirmed_odoo19",
                            wid2.intValue(), pickingIds, processResp != null ? processResp.get("result") : "null");
                }
            } else {
                log.warn("provider=odoo operation=confirmBackorderWizard reason=create_failed wizardId={}", wizardId);
            }
        } catch (Exception e) {
            log.warn("provider=odoo operation=confirmBackorderWizard reason={}", e.getMessage());
        }
    }

    private void reserveStock(Integer pickingId) {
        Map<String, Object> resp = rpc.callRpc(rpc.buildArgs("stock.picking", "action_assign", List.of(List.of(pickingId))));
        Object result = resp != null ? resp.get("result") : null;
        Object error = resp != null ? resp.get("error") : null;
        if (error != null) {
            log.warn("provider=odoo operation=reserveStock pickingId={} odooError={}", pickingId, error);
        } else {
            log.info("provider=odoo operation=reserveStock pickingId={} result={}", pickingId, result);
        }
    }

    private void setFullQuantityDoneOnMoveLines(Integer pickingId) {
        // action_set_quantities_to_reservation sets qty_done = reserved_qty on all move lines
        // Works on Odoo 16, 17, and 18 without field-name version differences
        Map<String, Object> resp = rpc.callRpc(rpc.buildArgs(
                "stock.picking", "action_set_quantities_to_reservation", List.of(List.of(pickingId))));
        log.info("provider=odoo operation=setFullQuantityDone pickingId={} result={}",
                pickingId, resp != null ? resp.get("result") : "null");
    }


    private Map<String, Object> callValidatePicking(Integer pickingId) {
        return rpc.callRpc(rpc.buildArgs("stock.picking", "button_validate",
                List.of(List.of(pickingId)),
                Map.of("context", Map.of("skip_sms", true, "skip_immediate", true))));
    }

    private Map<String, Object> findSinglePicking(Integer erpOrderId) {
        // order by id asc to always get the oldest pending picking — deterministic on backorder chains.
        // V3.1: callRpcOrThrow so a transport timeout is a retryable error, not a false "no picking".
        Map<String, Object> response = rpc.callRpcOrThrow(rpc.buildArgs("stock.picking", "search_read",
                List.of(List.of(List.of("sale_id", "=", erpOrderId), List.of("state", "not in", List.of("done", "cancel")))), Map.of("fields", List.of("id", "state"), "limit", 1, "order", "id asc")));
        List<Map<String, Object>> result = (List<Map<String, Object>>) response.get("result");
        return (result != null && !result.isEmpty()) ? result.get(0) : null;
    }

    private Map<String, Object> findPickingById(Integer pickingId) {
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.picking", "search_read", List.of(List.of(List.of("id", "=", pickingId))), Map.of("fields", List.of("id", "state"), "limit", 1)));
        List<Map<String, Object>> result = (List<Map<String, Object>>) response.get("result");
        return (result != null && !result.isEmpty()) ? result.get(0) : null;
    }

    /** Resolve a picking by its delivery-note number (BL), e.g. "WH/OUT/00012". Multi-depot precision. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findPickingByName(String name) {
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.picking", "search_read",
                List.of(List.of(List.of("name", "=", name))),
                Map.of("fields", List.of("id", "state"), "limit", 1)));
        List<Map<String, Object>> result = response != null ? (List<Map<String, Object>>) response.get("result") : null;
        return (result != null && !result.isEmpty()) ? result.get(0) : null;
    }

    /** Cancel a single delivery note (picking). 'done' is treated as idempotent success. */
    private boolean cancelPicking(Integer pickingId) {
        Map<String, Object> resp = rpc.callRpc(rpc.buildArgs("stock.picking", "action_cancel", List.of(List.of(pickingId))));
        if (resp != null && resp.containsKey("error")) {
            log.warn("ERP sync failed — provider=odoo operation=cancelPicking pickingId={} odooError={} retryable=true", pickingId, resp.get("error"));
            return false;
        }
        String state = readPickingState(pickingId);
        log.info("provider=odoo operation=cancelPicking pickingId={} finalState={}", pickingId, state);
        return "cancel".equalsIgnoreCase(state) || "done".equalsIgnoreCase(state);
    }

    private Integer findBackorderPickingId(Integer originPickingId) {
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.picking", "search_read", List.of(List.of(List.of("backorder_id", "=", originPickingId))), Map.of("fields", List.of("id"), "limit", 1)));
        List<Map<String, Object>> result = (List<Map<String, Object>>) response.get("result");
        return (result != null && !result.isEmpty()) ? asInt(result.get(0).get("id")) : null;
    }

    private String readPickingState(Integer pickingId) {
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.picking", "read", List.of(List.of(pickingId), List.of("state"))));
        List<Map<String, Object>> result = (List<Map<String, Object>>) response.get("result");
        return (result != null && !result.isEmpty()) ? (String) result.get(0).get("state") : null;
    }

    /** Reads a picking's delivery-note (BL) name by id, e.g. "WH/OUT/00013". Null on any failure. */
    private String readPickingName(Integer pickingId) {
        if (pickingId == null) return null;
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("stock.picking", "read", List.of(List.of(pickingId), List.of("name"))));
        List<Map<String, Object>> result = response != null ? (List<Map<String, Object>>) response.get("result") : null;
        return (result != null && !result.isEmpty()) ? asString(result.get(0).get("name")) : null;
    }

    private String readSaleOrderState(Integer erpOrderId) {
        Map<String, Object> response = rpc.callRpc(rpc.buildArgs("sale.order", "read", List.of(List.of(erpOrderId), List.of("state"))));
        List<Map<String, Object>> result = (List<Map<String, Object>>) response.get("result");
        return (result != null && !result.isEmpty()) ? (String) result.get(0).get("state") : null;
    }

    private Map<Integer, Integer> resolvePartialQuantities(List<ErpPartialItemDTO> items) {
        Map<Integer, Integer> qtyMap = new HashMap<>();
        for (ErpPartialItemDTO item : items) {
            Integer pid = resolveProductId(item.getReferenceKey(), item.getItemName());
            if (pid != null) qtyMap.merge(pid, item.getQuantityDone(), Integer::sum);
        }
        return qtyMap;
    }

    /**
     * Resolves an Odoo product.product ID from a SKU (default_code).
     * Falls back to exact name match for service products that have no internal reference.
     */
    private Integer resolveProductId(String sku, String itemName) {
        // Primary: SKU / internal reference lookup
        if (sku != null && !sku.isBlank()) {
            try {
                Map<String, Object> res = rpc.callRpc(rpc.buildArgs("product.product", "search",
                        List.of(List.of(List.of("default_code", "=", sku))), Map.of("limit", 1)));
                List<Integer> ids = (List<Integer>) res.get("result");
                if (ids != null && !ids.isEmpty()) return ids.get(0);
            } catch (Exception e) {
                log.warn("provider=odoo operation=resolveProductId sku={} reason={}", sku, e.getMessage());
            }
        }
        // Fallback: exact product name — covers service products without a default_code
        if (itemName != null && !itemName.isBlank()) {
            try {
                Map<String, Object> res = rpc.callRpc(rpc.buildArgs("product.product", "search",
                        List.of(List.of(List.of("name", "=", itemName))), Map.of("limit", 1)));
                List<Integer> ids = (List<Integer>) res.get("result");
                if (ids != null && !ids.isEmpty()) {
                    log.info("provider=odoo operation=resolveProductId sku={} resolved_by_name={}", sku, itemName);
                    return ids.get(0);
                }
            } catch (Exception e) {
                log.warn("provider=odoo operation=resolveProductId itemName={} reason={}", itemName, e.getMessage());
            }
        }
        log.warn("provider=odoo operation=resolveProductId sku={} itemName={} reason=not_found action=skip", sku, itemName);
        return null;
    }

    /**
     * Writes qty_done to each stock.move.line for a partial delivery.
     * Matches move lines to items by fetching the product's default_code directly from Odoo —
     * avoids the SKU→product_id pre-resolution that fails when the SKU stored in our system
     * doesn't exactly match Odoo's default_code (e.g. bracket notation vs plain code).
     * Move lines for products not in the items list are explicitly set to 0.
     */
    /**
     * Returns total qty_done written (sum across all move lines).
     * Caller uses this to detect "all refused" and skip button_validate.
     */
    private int applyPartialQtyDoneToMoveLines(Integer pickingId, List<ErpPartialItemDTO> items) {
        // Three lookup strategies, tried in order for each move line:
        // 1. Numeric product_id: referenceKey is stored as the Odoo product DB id ("55", "27")
        // 2. SKU string: referenceKey is the product default_code ("FURN_5555", "E-COM11")
        // 3. Product name: itemName matches Odoo product name (service products without default_code)
        Map<Integer, Integer> pidToQty  = new HashMap<>();
        Map<String, Integer>  skuToQty  = new HashMap<>();
        Map<String, Integer>  nameToQty = new HashMap<>();
        for (ErpPartialItemDTO item : items) {
            String ref = item.getReferenceKey();
            if (ref != null && !ref.isBlank()) {
                try { pidToQty.put(Integer.parseInt(ref.trim()), item.getQuantityDone()); }
                catch (NumberFormatException ignored) { skuToQty.put(ref.trim(), item.getQuantityDone()); }
            }
            if (item.getItemName() != null && !item.getItemName().isBlank()) {
                nameToQty.put(item.getItemName().trim(), item.getQuantityDone());
            }
        }
        log.info("provider=odoo operation=applyPartialQty pickingId={} pidToQty={} skuToQty={}", pickingId, pidToQty, skuToQty);

        // Read move lines
        Map<String, Object> mlResp = rpc.callRpc(rpc.buildArgs("stock.move.line", "search_read",
                List.of(List.of(List.of("picking_id", "=", pickingId))),
                Map.of("fields", List.of("id", "product_id"))));
        if (mlResp == null) {
            log.warn("provider=odoo operation=applyPartialQty pickingId={} reason=null_response", pickingId);
            return 0;
        }
        List<Map<String, Object>> lines = (List<Map<String, Object>>) mlResp.get("result");
        log.info("provider=odoo operation=applyPartialQty pickingId={} move_lines_found={}", pickingId,
                lines != null ? lines.size() : "null");
        if (lines == null || lines.isEmpty()) return 0;

        // Batch-fetch product details (default_code + name) for all products in this picking
        List<Integer> productIds = lines.stream()
                .map(l -> asRelId(l.get("product_id")))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(java.util.stream.Collectors.toList());
        Map<Integer, String> pidToSku  = new HashMap<>();
        Map<Integer, String> pidToName = new HashMap<>();
        if (!productIds.isEmpty()) {
            Map<String, Object> prodResp = rpc.callRpc(rpc.buildArgs("product.product", "search_read",
                    List.of(List.of(List.of("id", "in", productIds))),
                    Map.of("fields", List.of("id", "default_code", "name"))));
            List<Map<String, Object>> prods = prodResp != null ? (List<Map<String, Object>>) prodResp.get("result") : null;
            if (prods != null) {
                for (Map<String, Object> p : prods) {
                    Integer pid = asInt(p.get("id"));
                    if (pid == null) continue;
                    Object dc = p.get("default_code");
                    if (dc instanceof String s && !s.isBlank()) pidToSku.put(pid, s.trim());
                    Object nm = p.get("name");
                    if (nm instanceof String s && !s.isBlank()) pidToName.put(pid, s.trim());
                }
            }
        }
        log.info("provider=odoo operation=applyPartialQty pickingId={} productDetails={}", pickingId, pidToSku);

        // Write qty_done to each move line; tally total for caller's zero-check
        int totalWritten = 0;
        for (Map<String, Object> line : lines) {
            Integer pid   = asRelId(line.get("product_id"));
            String  sku   = pidToSku.get(pid);
            String  pName = pidToName.get(pid);

            Integer qty = null;
            if (pid  != null && pidToQty.containsKey(pid))   qty = pidToQty.get(pid);
            if (qty  == null && sku  != null)                 qty = skuToQty.get(sku);
            // Bracket-prefix fallback: skuToQty key may be "[FURN_6666] Product Name..." when SKU was
            // stored as null in DB and Flutter fell back to the full Odoo sale.order.line description.
            if (qty == null && sku != null) {
                final String bracketPrefix = "[" + sku + "]";
                qty = skuToQty.entrySet().stream()
                        .filter(e -> e.getKey().startsWith(bracketPrefix))
                        .map(Map.Entry::getValue)
                        .findFirst().orElse(null);
            }
            if (qty  == null && pName != null)                qty = nameToQty.get(pName);
            if (qty  == null) qty = 0; // product not in delivery list → not delivered

            // Odoo 17+: "quantity" on stock.move.line is the done qty
            Map<String, Object> writeResp = rpc.callRpc(rpc.buildArgs("stock.move.line", "write",
                    List.of(List.of(line.get("id")), Map.of("quantity", qty))));
            log.info("provider=odoo operation=applyPartialQty pickingId={} lineId={} productId={} sku={} qty={} writeResult={}",
                    pickingId, line.get("id"), pid, sku, qty, writeResp != null ? writeResp.get("result") : "null");
            totalWritten += qty;
        }
        return totalWritten;
    }


    @SuppressWarnings("unchecked")
    private void syncSaleOrderLineDeliveredQuantities(Integer erpOrderId, List<ErpPartialItemDTO> items, boolean full) {
        try {
            if (full) {
                // For full delivery, Odoo automatically updates qty_delivered on sale.order.line
                // when the stock.picking is validated via button_validate. No explicit write needed.
                log.debug("syncSaleOrderLineDeliveredQuantities: full delivery for erpOrderId={} — Odoo handles qty_delivered automatically", erpOrderId);
                return;
            }

            // PARTIAL: resolve SKU → product_id → qty mapping
            if (items == null || items.isEmpty()) return;
            Map<Integer, Integer> productQtyDone = resolvePartialQuantities(items);
            if (productQtyDone.isEmpty()) {
                log.warn("syncSaleOrderLineDeliveredQuantities: no product IDs resolved for erpOrderId={}", erpOrderId);
                return;
            }

            // Fetch sale.order.line records for this order
            Map<String, Object> solResp = rpc.callRpc(rpc.buildArgs(
                    "sale.order.line", "search_read",
                    List.of(List.of(List.of("order_id", "=", erpOrderId))),
                    Map.of("fields", List.of("id", "product_id", "product_uom_qty", "qty_delivered"))));
            if (solResp == null) {
                log.warn("syncSaleOrderLineDeliveredQuantities: null response from Odoo for erpOrderId={}", erpOrderId);
                return;
            }

            List<Map<String, Object>> lines = (List<Map<String, Object>>) solResp.get("result");
            if (lines == null || lines.isEmpty()) return;

            for (Map<String, Object> line : lines) {
                Integer productId = asRelId(line.get("product_id"));
                if (productId == null) continue;
                Integer qtyDone = productQtyDone.get(productId);
                if (qtyDone == null) continue;

                // Cap qty_delivered at product_uom_qty to avoid over-delivery
                Object plannedRaw = line.get("product_uom_qty");
                int planned = plannedRaw instanceof Number n ? n.intValue() : Integer.MAX_VALUE;
                int cappedQty = Math.min(qtyDone, planned);

                Map<String, Object> writeResp = rpc.callRpc(rpc.buildArgs(
                        "sale.order.line", "write",
                        List.of(List.of(line.get("id")), Map.of("qty_delivered", cappedQty))));
                if (writeResp != null && writeResp.containsKey("error")) {
                    log.warn("syncSaleOrderLineDeliveredQuantities: write error for line={} order={}: {}",
                            line.get("id"), erpOrderId, writeResp.get("error"));
                }
            }
            log.info("syncSaleOrderLineDeliveredQuantities: updated qty_delivered for {} lines, erpOrderId={}", lines.size(), erpOrderId);
        } catch (Exception e) {
            // Best-effort — do not propagate; the picking validation already succeeded
            log.warn("syncSaleOrderLineDeliveredQuantities failed for erpOrderId={}: {}", erpOrderId, e.getMessage(), e);
        }
    }

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

    /** Cached id of the mail.mt_note subtype ("log note"), resolved lazily. */
    private volatile Integer noteSubtypeId;

    /**
     * Posts an HTML log note to the sale-order chatter. We do NOT use {@code message_post(body=...)}:
     * Odoo HTML-escapes a non-Markup string body (Odoo 17+), so the raw tags would show literally in the
     * chatter — and Markup can't cross JSON-RPC. Creating a {@code mail.message} whose {@code body} is an
     * Html field renders the (sanitized) HTML correctly. Best-effort.
     */
    private void addNoteToSaleOrder(Integer erpId, String note) {
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

    /**
     * Adds the "Livraison Échouée" tag to the sale order so dispatchers can
     * filter failed deliveries directly from the Odoo sale order list.
     * Tag ID is resolved once and cached for the lifetime of this bean.
     */
    private void tagOrderAsDeliveryFailed(Integer erpId) {
        try {
            Integer tagId = resolveOrCreateDeliveryFailedTag();
            if (tagId == null) {
                log.warn("provider=odoo operation=tagOrderAsDeliveryFailed erpId={} reason=tag_id_null", erpId);
                return;
            }
            // ORM command (4, id) = link existing record without replacing others
            rpc.callRpc(rpc.buildArgs("sale.order", "write",
                    List.of(List.of(erpId), Map.of("tag_ids", List.of(List.of(4, tagId))))));
            log.info("provider=odoo operation=tagOrderAsDeliveryFailed erpId={} tagId={}", erpId, tagId);
        } catch (Exception e) {
            // Non-critical — don't fail the whole sync if tagging fails
            log.warn("provider=odoo operation=tagOrderAsDeliveryFailed erpId={} reason={}", erpId, e.getMessage());
        }
    }

    /** Lazily resolves the "Livraison Échouée" tag ID, creating it if it doesn't exist. */
    private Integer resolveOrCreateDeliveryFailedTag() {
        if (deliveryFailedTagId != null) return deliveryFailedTagId;

        synchronized (this) {
            if (deliveryFailedTagId != null) return deliveryFailedTagId;

            final String tagName = "Livraison Échouée";

            // Try to find existing tag
            Map<String, Object> searchResp = rpc.callRpc(rpc.buildArgs(
                    "crm.tag", "search_read",
                    List.of(List.of(List.of("name", "=", tagName))),
                    Map.of("fields", List.of("id"), "limit", 1)));
            List<?> found = searchResp != null ? (List<?>) searchResp.get("result") : null;
            if (found != null && !found.isEmpty()) {
                deliveryFailedTagId = asInt(((Map<?, ?>) found.get(0)).get("id"));
                log.info("provider=odoo operation=resolveOrCreateDeliveryFailedTag action=found tagId={}", deliveryFailedTagId);
                return deliveryFailedTagId;
            }

            // Create if not found
            Map<String, Object> createResp = rpc.callRpc(rpc.buildArgs(
                    "crm.tag", "create", List.of(Map.of("name", tagName))));
            Object created = createResp != null ? createResp.get("result") : null;
            if (created instanceof Number n) {
                deliveryFailedTagId = n.intValue();
                log.info("provider=odoo operation=resolveOrCreateDeliveryFailedTag action=created tagId={}", deliveryFailedTagId);
                return deliveryFailedTagId;
            }

            log.warn("provider=odoo operation=resolveOrCreateDeliveryFailedTag reason=create_returned_null response={}", createResp);
            return null;
        }
    }

    /**
     * Builds an HTML chatter note summarising the partial delivery outcome per item.
     * Includes: refused/damaged items, partial-qty delivered items, and any driver comments.
     * Returns null when all items are fully DELIVERED with no comments.
     */
    private String buildPartialDeliveryNote(List<com.asm.erpadapter.dto.ErpPartialItemDTO> items) {
        if (items == null || items.isEmpty()) return null;

        // Separate items into refused/damaged vs delivered (full or partial)
        List<com.asm.erpadapter.dto.ErpPartialItemDTO> refused = items.stream()
                .filter(i -> i != null && ("REFUSED".equalsIgnoreCase(i.getOutcome()) || "DAMAGED".equalsIgnoreCase(i.getOutcome())))
                .collect(java.util.stream.Collectors.toList());

        // DELIVERED items that have a driver comment (typically partial-qty deliveries)
        List<com.asm.erpadapter.dto.ErpPartialItemDTO> deliveredWithComment = items.stream()
                .filter(i -> i != null
                        && "DELIVERED".equalsIgnoreCase(i.getOutcome())
                        && i.getComment() != null && !i.getComment().isBlank())
                .collect(java.util.stream.Collectors.toList());

        if (refused.isEmpty() && deliveredWithComment.isEmpty()) return null;

        StringBuilder sb = new StringBuilder("<b>ASM Track — Livraison partielle</b><br/>");

        if (!refused.isEmpty()) {
            sb.append("<b>Articles non livrés :</b><ul>");
            for (com.asm.erpadapter.dto.ErpPartialItemDTO item : refused) {
                String label = (item.getItemName() != null && !item.getItemName().isBlank())
                        ? item.getItemName() : item.getReferenceKey();
                sb.append("<li><b>").append(label).append("</b>");
                if ("DAMAGED".equalsIgnoreCase(item.getOutcome())) {
                    sb.append(" — Endommagé");
                } else {
                    sb.append(" — Refusé");
                }
                // Prefer the platform-resolved catalog label; fall back to humanizing the raw code.
                String reasonText = (item.getReasonLabel() != null && !item.getReasonLabel().isBlank())
                        ? item.getReasonLabel()
                        : humanizeReason(item.getReason());
                if (reasonText != null && !reasonText.isBlank()) {
                    sb.append(" (").append(reasonText).append(")");
                }
                if (item.getComment() != null && !item.getComment().isBlank()) {
                    sb.append("<br/><i>").append(item.getComment()).append("</i>");
                }
                sb.append("</li>");
            }
            sb.append("</ul>");
        }

        if (!deliveredWithComment.isEmpty()) {
            sb.append("<b>Notes chauffeur :</b><ul>");
            for (com.asm.erpadapter.dto.ErpPartialItemDTO item : deliveredWithComment) {
                String label = (item.getItemName() != null && !item.getItemName().isBlank())
                        ? item.getItemName() : item.getReferenceKey();
                sb.append("<li><b>").append(label).append("</b>");
                sb.append(" — Livré : <i>").append(item.getComment()).append("</i></li>");
            }
            sb.append("</ul>");
        }

        return sb.toString();
    }

    private Integer asInt(Object o) { return o instanceof Number n ? n.intValue() : null; }
    private Integer asRelId(Object o) { if (o instanceof List<?> l && !l.isEmpty()) return asInt(l.get(0)); return null; }
}
