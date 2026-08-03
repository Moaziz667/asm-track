package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.dto.ErpPodDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OdooPodServiceTest {

    @Mock
    private OdooJsonRpcClient rpc;
    @Mock
    private OdooSaleOrderService saleOrderService;

    private OdooPodService service;

    @BeforeEach
    void setUp() {
        service = new OdooPodService(rpc, saleOrderService);
        ReflectionTestUtils.setField(service, "minioPublicUrl", "http://localhost:9000");
        ReflectionTestUtils.setField(service, "minioInternalUrl", "http://minio:9000");
        ReflectionTestUtils.setField(service, "minioBucket", "pod-files");
    }

    // ── internalMinioUrl ──────────────────────────────────────────────────────

    /**
     * Regression: delivery-service persists media as a bare object key. Handing that to
     * {@code URI.create} threw "URI is not absolute", so every POD photo was silently dropped.
     */
    @Test
    void internalMinioUrl_resolvesBareObjectKeyAgainstInternalMinio() {
        String key = "54ed4906-3009-4a22-888e-8717f3d23178/pod/abc/bon-livraison.png";
        assertEquals("http://minio:9000/pod-files/" + key, service.internalMinioUrl(key));
    }

    @Test
    void internalMinioUrl_doesNotDoubleTheBucketWhenKeyAlreadyCarriesIt() {
        String key = "pod-files/tenant/pod/abc/package.png";
        assertEquals("http://minio:9000/pod-files/tenant/pod/abc/package.png",
                service.internalMinioUrl(key));
    }

    @Test
    void internalMinioUrl_stripsLeadingSlashOnKey() {
        assertEquals("http://minio:9000/pod-files/tenant/pod/a.png",
                service.internalMinioUrl("/tenant/pod/a.png"));
    }

    /** Legacy rows hold an absolute URL against the configured public base. */
    @Test
    void internalMinioUrl_rewritesLegacyPublicUrl() {
        assertEquals("http://minio:9000/pod-files/tenant/pod/a.png",
                service.internalMinioUrl("http://localhost:9000/pod-files/tenant/pod/a.png"));
    }

    /**
     * Legacy rows were written with whatever origin was live at upload time — a LAN IP behind the
     * gateway's /files path, not necessarily {@code minio.public-url}. Matching on the bucket marker
     * recovers those too.
     */
    @Test
    void internalMinioUrl_rewritesLegacyUrlFromAnyOriginViaBucketMarker() {
        assertEquals("http://minio:9000/pod-files/tenant/pod/a.png",
                service.internalMinioUrl("http://192.168.1.7/files/pod-files/tenant/pod/a.png"));
    }

    @Test
    void internalMinioUrl_leavesForeignUrlUntouched() {
        String foreign = "https://cdn.example.com/other/a.png";
        assertEquals(foreign, service.internalMinioUrl(foreign));
    }

    // ── syncProofOfDelivery ───────────────────────────────────────────────────

    /**
     * Regression: the sync returned {@code true} even when no attachment could be created, so an
     * operator saw "synced" against an Odoo record holding no proof at all.
     */
    @Test
    void syncProofOfDelivery_reportsFailureWhenAnExpectedPhotoIsNeverAttached() {
        ErpPodDTO pod = ErpPodDTO.builder()
                .bonLivraisonPhotoUrl("tenant/pod/abc/bon-livraison.png") // fetch will fail: no MinIO
                .build();

        assertFalse(service.syncProofOfDelivery("sale.order", 42, pod));
        verify(rpc, never()).callRpc(any());
    }

    @Test
    void syncProofOfDelivery_succeedsWhenPhotoIsAttached() {
        when(rpc.buildArgs(any(), any(), any())).thenReturn(List.of());
        when(rpc.callRpc(any())).thenReturn(Map.of("result", 7));

        ErpPodDTO pod = ErpPodDTO.builder()
                .blPhotoBase64("data:image/png;base64,aGVsbG8=")
                .build();

        assertTrue(service.syncProofOfDelivery("sale.order", 42, pod));
        verify(saleOrderService).addNoteToSaleOrder(eq(42), any());
    }

    /** A POD with no photos at all is still a valid sync — only the chatter note is expected. */
    @Test
    void syncProofOfDelivery_succeedsWhenNoPhotoWasCaptured() {
        ErpPodDTO pod = ErpPodDTO.builder().recipientName("Jane").build();

        assertTrue(service.syncProofOfDelivery("sale.order", 42, pod));
        verify(rpc, never()).callRpc(any());
    }

    @Test
    void syncProofOfDelivery_abortsWithoutTargetRecord() {
        assertFalse(service.syncProofOfDelivery("sale.order", null, ErpPodDTO.builder().build()));
    }
}
