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
     * Rewrites a public MinIO URL to the internal container URL when both are configured.
     */
    String internalMinioUrl(String url) {
        if (url != null && minioPublicUrl != null && !minioPublicUrl.isBlank()
                && minioInternalUrl != null && !minioInternalUrl.isBlank()
                && url.startsWith(minioPublicUrl)) {
            return minioInternalUrl + url.substring(minioPublicUrl.length());
        }
        return url;
    }

    /**
     * Sync POD to Odoo: attach photos as ir.attachment, post metadata as chatter note.
     */
    public boolean syncProofOfDelivery(Integer erpId, ErpPodDTO pod) {
        if (pod == null) pod = ErpPodDTO.builder().build();

        createPodAttachment(erpId,
                resolvePhotoBase64(pod.getBonLivraisonPhotoUrl(), pod.getBlPhotoBase64()),
                "bon-livraison.png");
        createPodAttachment(erpId,
                resolvePhotoBase64(pod.getPackagePhotoUrl(), pod.getPackagePhotoBase64()),
                "package.png");

        saleOrderService.addNoteToSaleOrder(erpId, buildPodNote(pod));
        return true;
    }

    /**
     * Resolves a POD photo to base64. Prefers the MinIO URL (fetched over HTTP);
     * falls back to legacyBase64 when no URL is given.
     */
    String resolvePhotoBase64(String url, String legacyBase64) {
        if (url != null && !url.isBlank()) {
            try {
                byte[] bytes = podHttpClient.get().uri(internalMinioUrl(url))
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

    private void createPodAttachment(Integer erpId, String base64, String name) {
        if (base64 == null || base64.isBlank()) return;
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
            log.warn("provider=odoo operation=syncPod erpId={} attachment={} action=skip reason={}",
                    erpId, name, e.getMessage());
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
