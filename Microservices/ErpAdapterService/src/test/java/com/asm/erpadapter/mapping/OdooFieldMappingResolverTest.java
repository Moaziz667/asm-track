package com.asm.erpadapter.mapping;

import com.asm.erpadapter.adapter.odoo.OdooJsonRpcClient;
import com.asm.erpadapter.entity.ErpFieldMapping;
import com.asm.erpadapter.repository.ErpFieldMappingRepository;
import com.asm.erpadapter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OdooFieldMappingResolverTest {

    private static final UUID TENANT = UUID.fromString("54ed4906-3009-4a22-888e-8717f3d23178");

    @Mock private ErpFieldMappingRepository repository;
    @Mock private OdooJsonRpcClient rpc;

    private OdooFieldMappingResolver resolver;

    private final Map<String, Map<String, Object>> records = Map.of(
            "stock.picking", Map.of(
                    "name", "WH/OUT/00341",
                    "partner_id", List.of(42, "Grant Plastics Ltd."),
                    "x_client_nom", "Nom Personnalisé"),
            "sale.order", Map.of("client_order_ref", "REF-001"),
            "res.partner", Map.of("city", "Sfax", "phone", "+216 20 000 000"));

    @BeforeEach
    void setUp() {
        resolver = new OdooFieldMappingResolver(repository, rpc);
        TenantContext.set(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void mapped(CanonicalField field, String path, String readAs) {
        when(repository.findByTenantIdAndProviderAndCanonicalField(TENANT, "odoo", field.name()))
                .thenReturn(Optional.of(ErpFieldMapping.builder()
                        .tenantId(TENANT).provider("odoo").canonicalField(field.name())
                        .sourcePath(path).readAs(readAs).build()));
    }

    // ── The property that makes this safe to ship ────────────────────────────────────────────────

    /**
     * Every tenant in production has mapped nothing. If that path did not run the caller's own reader
     * verbatim, introducing mapping would silently change what three validated ERPs import.
     */
    @Test
    void withoutAnyMappingTheCallersOwnReaderIsUsed() {
        when(repository.findByTenantIdAndProviderAndCanonicalField(any(), any(), any()))
                .thenReturn(Optional.empty());

        Object v = resolver.resolveOrDefault(CanonicalField.CUSTOMER_NAME, records, () -> "valeur par défaut");

        assertThat(v).isEqualTo("valeur par défaut");
        verifyNoInteractions(rpc);
    }

    @Test
    void withoutTenantContextTheDefaultIsUsed() {
        TenantContext.clear();
        assertThat(resolver.resolveOrDefault(CanonicalField.DELIVERY_CITY, records, () -> "défaut"))
                .isEqualTo("défaut");
    }

    // ── Reading a mapped value ───────────────────────────────────────────────────────────────────

    @Test
    void readsACustomFieldOnThePrimaryDocument() {
        mapped(CanonicalField.CUSTOMER_NAME, "x_client_nom", "AUTO");

        assertThat(resolver.resolveOrDefault(CanonicalField.CUSTOMER_NAME, records, () -> "défaut"))
                .isEqualTo("Nom Personnalisé");
    }

    @Test
    void readsFromAnotherDocumentInScope() {
        mapped(CanonicalField.DELIVERY_CITY, "res.partner:city", "AUTO");

        assertThat(resolver.resolveOrDefault(CanonicalField.DELIVERY_CITY, records, () -> "défaut"))
                .isEqualTo("Sfax");
    }

    /** Odoo hands relations back as [id, label]; copying that raw would show "[42, Grant…]" to a user. */
    @Test
    void takesTheLabelSideOfARelationByDefault() {
        mapped(CanonicalField.CUSTOMER_NAME, "partner_id", "AUTO");

        assertThat(resolver.resolveOrDefault(CanonicalField.CUSTOMER_NAME, records, () -> "défaut"))
                .isEqualTo("Grant Plastics Ltd.");
    }

    @Test
    void takesTheIdSideWhenAsked() {
        mapped(CanonicalField.CUSTOMER_NAME, "partner_id", "ID");

        assertThat(resolver.resolveOrDefault(CanonicalField.CUSTOMER_NAME, records, () -> "défaut"))
                .isEqualTo(42);
    }

    /** Most customer data hangs off a related record, so a path has to be able to step through one. */
    @Test
    void walksThroughARelationFetchingTheRelatedRecord() {
        mapped(CanonicalField.DELIVERY_CITY, "partner_id.city", "AUTO");
        when(rpc.callRpc(any())).thenReturn(Map.of("result", List.of(Map.of("city", "Tunis"))));

        assertThat(resolver.resolveOrDefault(CanonicalField.DELIVERY_CITY, records, () -> "défaut"))
                .isEqualTo("Tunis");
        verify(rpc).buildArgs(eq("res.partner"), eq("read"), any());
    }

    // ── A wrong mapping must be visible, not papered over ────────────────────────────────────────

    /**
     * An override that resolves to nothing wins over the default. Falling back would dress a wrong
     * mapping in plausible data, and the integrator would never find out.
     */
    @Test
    void anEmptyMappedValueBeatsTheDefault() {
        mapped(CanonicalField.DELIVERY_CITY, "x_inexistant", "AUTO");

        assertThat(resolver.resolveOrDefault(CanonicalField.DELIVERY_CITY, records, () -> "Sfax"))
                .isNull();
    }

    /** Odoo returns false for an empty value; the user must not see the string "false". */
    @Test
    void odooFalseBecomesNull() {
        Map<String, Map<String, Object>> withFalse = Map.of("stock.picking", Map.of("x_vide", false));
        mapped(CanonicalField.EXTERNAL_REF, "x_vide", "AUTO");

        assertThat(resolver.resolveOrDefault(CanonicalField.EXTERNAL_REF, withFalse, () -> "défaut"))
                .isNull();
    }

    /** A typo must not abort the import of an otherwise valid order. */
    @Test
    void aBrokenPathYieldsNullRatherThanThrowing() {
        mapped(CanonicalField.DELIVERY_CITY, "modele.inconnu:champ", "AUTO");

        assertThat(resolver.resolveOrDefault(CanonicalField.DELIVERY_CITY, records, () -> "défaut"))
                .isNull();
    }

    @Test
    void anAbsurdlyDeepPathIsRefused() {
        mapped(CanonicalField.DELIVERY_CITY, "a.b.c.d.e.f.g", "AUTO");

        assertThat(resolver.resolveOrDefault(CanonicalField.DELIVERY_CITY, records, () -> "défaut"))
                .isNull();
        verifyNoInteractions(rpc);
    }

    // ── Customer-defined extras ──────────────────────────────────────────────────────────────────

    @Test
    void collectsTheCustomerDefinedExtras() {
        when(repository.findByTenantIdAndProviderAndCanonicalFieldIsNull(TENANT, "odoo"))
                .thenReturn(List.of(
                        ErpFieldMapping.builder().tenantId(TENANT).provider("odoo")
                                .customKey("Référence interne").sourcePath("x_client_nom").readAs("AUTO").build(),
                        ErpFieldMapping.builder().tenantId(TENANT).provider("odoo")
                                .customKey("Absent").sourcePath("x_pas_la").readAs("AUTO").build()));

        Map<String, Object> extras = resolver.resolveCustomFields(records);

        assertThat(extras).containsEntry("Référence interne", "Nom Personnalisé");
        assertThat(extras).doesNotContainKey("Absent"); // nothing to show is not shown
    }

    @Test
    void noExtrasConfiguredMeansAnEmptyBag() {
        when(repository.findByTenantIdAndProviderAndCanonicalFieldIsNull(TENANT, "odoo"))
                .thenReturn(List.of());

        assertThat(resolver.resolveCustomFields(records)).isEmpty();
    }
}
