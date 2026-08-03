package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.dto.ErpPodDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient.asInt;

/**
 * Reusable Odoo proof-of-delivery (POD) operations: photo fetch from MinIO,
 * ir.attachment creation, and chatter note posting.
 *
 * <p>Extracted from {@link OdooSyncAdapter} so the POD workflow is a thin
 * orchestrator over this focused service.
 *
 * <p>Stateless — all Odoo state is read/written via {@link OdooJsonRpcClient}.
 */
@Component
@Slf4j
public class OdooPodService {

    private final OdooJsonRpcClient rpc;
    private final OdooSaleOrderService saleOrderService;

    private final org.springframework.web.client.RestClient podHttpClient = buildPodHttpClient();

    @Value("${minio.public-url:}")
    private String minioPublicUrl;

    @Value("${minio.internal-url:}")
    private String minioInternalUrl;

    @Value("${minio.bucket:pod-files}")
    private String minioBucket;

    public OdooPodService(OdooJsonRpcClient rpc, OdooSaleOrderService saleOrderService) {
        this.rpc = rpc;
        this.saleOrderService = saleOrderService;
    }

    private static org.springframework.web.client.RestClient buildPodHttpClient() {
        org.springframework.http.client.SimpleClientHttpRequestFactory f =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(10000);
        return org.springframework.web.client.RestClient.builder().requestFactory(f).build();
    }

    /**
     * Resolves a stored POD photo reference to a URL this service can actually fetch.
     *
     * <p>Two shapes reach us, and both must work:
     * <ul>
     *   <li><b>Object key</b> — {@code {companyId}/pod/{deliveryId}/bon-livraison.png}. This is what
     *       delivery-service persists now: media rows carry no host, so the address is never frozen
     *       at upload time. A key is resolved straight against internal MinIO
     *       ({@code {internal-url}/{bucket}/{key}}), which is the correct target for a server-side
     *       consumer — it needs no public origin, no gateway hop, and keeps working when the public
     *       host changes (new Wi-Fi, VPS, production domain).</li>
     *   <li><b>Legacy absolute URL</b> — rows written before the key migration. The public MinIO base
     *       is swapped for the internal one so the container can reach it.</li>
     * </ul>
     *
     * <p>Before this, a key was handed to {@code URI.create} unchanged and failed with
     * "URI is not absolute": every POD photo was silently skipped while the sync still reported
     * success.
     */
    String internalMinioUrl(String url) {
        if (url == null || url.isBlank()) return url;

        boolean absolute = url.startsWith("http://") || url.startsWith("https://");
        if (!absolute) {
            if (minioInternalUrl == null || minioInternalUrl.isBlank()) return url;
            String key = url.startsWith("/") ? url.substring(1) : url;
            // A key may or may not already be bucket-qualified; don't double it up.
            String prefix = minioBucket + "/";
            if (!key.startsWith(prefix)) key = prefix + key;
            return trimTrailingSlash(minioInternalUrl) + "/" + key;
        }

        if (minioInternalUrl == null || minioInternalUrl.isBlank()) return url;

        // Rebuild from the bucket marker rather than from a configured host prefix: legacy rows were
        // written with whatever origin was live at the time (localhost:9000, a LAN IP, the gateway's
        // /files path), so matching on minio.public-url alone missed most of them.
        String marker = "/" + minioBucket + "/";
        int at = url.indexOf(marker);
        if (at >= 0) {
            return trimTrailingSlash(minioInternalUrl) + marker + url.substring(at + marker.length());
        }
        if (!minioPublicUrl.isBlank() && url.startsWith(minioPublicUrl)) {
            return trimTrailingSlash(minioInternalUrl) + url.substring(trimTrailingSlash(minioPublicUrl).length());
        }
        return url;
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * Sync POD to Odoo: attach photos as ir.attachment, post metadata as chatter note.
     */
    public boolean syncProofOfDelivery(Integer erpId, ErpPodDTO pod) {
        return syncProofOfDelivery("sale.order", erpId, pod);
    }

    /**
     * Attach the POD to any Odoo record. {@code sale.order} is the normal target, but a BL can be a
     * <b>standalone picking with no sale order</b> — in that case the proof belongs on the picking
     * itself rather than being dropped on the floor (which is what happened before: no sale order
     * meant the whole POD sync returned false and dead-lettered).
     */
    public boolean syncProofOfDelivery(String resModel, Integer resId, ErpPodDTO pod) {
        if (resId == null) {
            log.warn("provider=odoo operation=syncPod action=abort reason=no_target_record");
            return false;
        }
        if (pod == null) pod = ErpPodDTO.builder().build();

        // A photo the driver actually captured must end up on the record. Track expected-vs-attached
        // so a POD that lost its photos is reported as a failure instead of a silent success — the
        // sync previously returned true even when every attachment had been skipped, so the operator
        // saw "synced" against an Odoo record carrying no proof at all.
        int expected = 0, attached = 0;
        if (hasPhoto(pod.getBonLivraisonPhotoUrl(), pod.getBlPhotoBase64())) {
            expected++;
            if (createPodAttachment(resModel, resId,
                    resolvePhotoBase64(pod.getBonLivraisonPhotoUrl(), pod.getBlPhotoBase64()),
                    "bon-livraison.png")) attached++;
        }
        if (hasPhoto(pod.getPackagePhotoUrl(), pod.getPackagePhotoBase64())) {
            expected++;
            if (createPodAttachment(resModel, resId,
                    resolvePhotoBase64(pod.getPackagePhotoUrl(), pod.getPackagePhotoBase64()),
                    "package.png")) attached++;
        }

        if ("sale.order".equals(resModel)) {
            saleOrderService.addNoteToSaleOrder(resId, buildPodNote(pod));
        }

        if (expected > 0 && attached == 0) {
            log.error("provider=odoo operation=syncPod resModel={} resId={} action=failed "
                            + "reason=no_photo_attached expected={}", resModel, resId, expected);
            return false;
        }
        log.info("provider=odoo operation=syncPod resModel={} resId={} action=done photos={}/{}",
                resModel, resId, attached, expected);
        return true;
    }

    private static boolean hasPhoto(String url, String base64) {
        return (url != null && !url.isBlank()) || (base64 != null && !base64.isBlank());
    }

    /**
     * Resolves a POD photo to base64. Prefers the MinIO URL (fetched over HTTP);
     * falls back to legacyBase64 when no URL is given.
     */
    String resolvePhotoBase64(String url, String legacyBase64) {
        if (url != null && !url.isBlank()) {
            try {
                byte[] bytes = podHttpClient.get().uri(java.net.URI.create(internalMinioUrl(url)))
                        .retrieve().body(byte[].class);
                if (bytes != null && bytes.length > 0) {
                    return Base64.getEncoder().encodeToString(bytes);
                }
                log.warn("provider=odoo operation=syncPod action=fetch_empty url={}", url);
            } catch (Exception e) {
                log.warn("provider=odoo operation=syncPod action=fetch_failed url={} reason={}",
                        url, e.getMessage());
            }
        }
        return legacyBase64;
    }

    /** @return {@code true} when the attachment was created in Odoo. */
    private boolean createPodAttachment(String resModel, Integer resId, String base64, String name) {
        if (base64 == null || base64.isBlank()) return false;
        String data = base64.contains(",") ? base64.substring(base64.indexOf(',') + 1) : base64;
        try {
            Map<String, Object> values = new HashMap<>();
            values.put("name", name);
            values.put("datas", data);
            values.put("res_model", resModel);
            values.put("res_id", resId);
            values.put("mimetype", "image/png");
            Map<String, Object> resp = rpc.callRpc(rpc.buildArgs("ir.attachment", "create", List.of(values)));
            if (resp == null || resp.containsKey("error")) {
                log.warn("provider=odoo operation=syncPod resModel={} resId={} attachment={} action=failed odooError={}",
                        resModel, resId, name, resp != null ? resp.get("error") : "null_response");
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("provider=odoo operation=syncPod resModel={} resId={} attachment={} action=skip reason={}",
                    resModel, resId, name, e.getMessage());
            return false;
        }
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
}
