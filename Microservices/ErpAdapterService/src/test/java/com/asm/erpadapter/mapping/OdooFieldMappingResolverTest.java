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
        mapped(CanonicalField.CUSTOMER_REF, "x_vide", "AUTO");

        assertThat(resolver.resolveOrDefault(CanonicalField.CUSTOMER_REF, withFalse, () -> "défaut"))
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

    // ── Widening what the adapter fetches ────────────────────────────────────────────────────────

    /**
     * The adapter reads a fixed field list. A mapping points at a field nobody anticipated, so unless
     * the request is widened the value is simply absent and the mapping yields nothing — leaving the
     * integrator unable to tell an empty ERP field from one that was never asked for.
     */
    @Test
    void reportsTheExtraFieldsTheAdapterMustFetch() {
        when(repository.findByTenantIdAndProvider(TENANT, "odoo")).thenReturn(List.of(
                ErpFieldMapping.builder().tenantId(TENANT).provider("odoo")
                        .canonicalField("CUSTOMER_NAME").sourcePath("x_client_nom").readAs("AUTO").build(),
                ErpFieldMapping.builder().tenantId(TENANT).provider("odoo")
                        .canonicalField("DELIVERY_CITY").sourcePath("res.partner:x_ville").readAs("AUTO").build()));

        assertThat(resolver.extraFieldsFor("stock.picking")).containsExactly("x_client_nom");
        assertThat(resolver.extraFieldsFor("res.partner")).containsExactly("x_ville");
        assertThat(resolver.extraFieldsFor("sale.order")).isEmpty();
    }

    /** Only the first hop needs fetching; the rest is read from the related record. */
    @Test
    void onlyTheRootOfARelationPathNeedsFetching() {
        when(repository.findByTenantIdAndProvider(TENANT, "odoo")).thenReturn(List.of(
                ErpFieldMapping.builder().tenantId(TENANT).provider("odoo")
                        .canonicalField("DELIVERY_CITY").sourcePath("partner_id.country_id.code").readAs("AUTO").build()));

        assertThat(resolver.extraFieldsFor("stock.picking")).containsExactly("partner_id");
    }

    @Test
    void nothingMappedWidensNothing() {
        when(repository.findByTenantIdAndProvider(TENANT, "odoo")).thenReturn(List.of());
        assertThat(resolver.extraFieldsFor("stock.picking")).isEmpty();
    }

    // ── Line scope ───────────────────────────────────────────────────────────────────────────────
    //
    // An integrator mapping ITEM_SKU writes "x_ref" while looking at one row of the order — meaning the
    // stock move. Resolving that against the delivery note, which has no such field, would hand back a
    // blank on every line and make the six per-line fields look broken.

    private final Map<String, Map<String, Object>> lineRecords = Map.of(
            "stock.picking", Map.of("name", "WH/OUT/00341", "x_ref", "SUR-LE-BL"),
            "sale.order", Map.of("client_order_ref", "REF-001"),
            "stock.move", Map.of("x_ref", "SUR-LA-LIGNE", "product_id", List.of(7, "Bidon 20L")),
            "product.product", Map.of("default_code", "BID-20", "x_ref_client", "CLI-77"));

    @Test
    void aBareLinePathReadsFromTheStockMoveNotTheDeliveryNote() {
        mapped(CanonicalField.ITEM_SKU, "x_ref", "AUTO");

        assertThat(resolver.resolveOrDefault(CanonicalField.ITEM_SKU, lineRecords, () -> "défaut"))
                .isEqualTo("SUR-LA-LIGNE");
    }

    /** The same bare path on a header field still means the picking — the two scopes must not swap. */
    @Test
    void aBareHeaderPathStillReadsFromTheDeliveryNote() {
        mapped(CanonicalField.CUSTOMER_REF, "x_ref", "AUTO");

        assertThat(resolver.resolveOrDefault(CanonicalField.CUSTOMER_REF, lineRecords, () -> "défaut"))
                .isEqualTo("SUR-LE-BL");
    }

    /** Most per-line values live on the product, one hop from the move. */
    @Test
    void aLinePathReachesTheProductItNamesExplicitly() {
        mapped(CanonicalField.ITEM_SKU, "product.product:x_ref_client", "AUTO");

        assertThat(resolver.resolveOrDefault(CanonicalField.ITEM_SKU, lineRecords, () -> "défaut"))
                .isEqualTo("CLI-77");
    }

    /** Header records stay addressable from a line: a per-line value is sometimes held on the order. */
    @Test
    void aLineCanStillReachTheHeaderRecords() {
        mapped(CanonicalField.ITEM_NAME, "sale.order:client_order_ref", "AUTO");

        assertThat(resolver.resolveOrDefault(CanonicalField.ITEM_NAME, lineRecords, () -> "défaut"))
                .isEqualTo("REF-001");
    }

    /** The move's own fields must be fetched, or the mapping above resolves against nothing. */
    @Test
    void aLineMappingWidensTheStockMoveFetchNotThePicking() {
        when(repository.findByTenantIdAndProvider(TENANT, "odoo")).thenReturn(List.of(
                ErpFieldMapping.builder().tenantId(TENANT).provider("odoo")
                        .canonicalField("ITEM_SKU").sourcePath("x_lot").readAs("AUTO").build()));

        assertThat(resolver.extraFieldsFor("stock.move")).containsExactly("x_lot");
        assertThat(resolver.extraFieldsFor("stock.picking")).isEmpty();
    }

    /** stock.warehouse is not offered: the adapter holds no such record, so it would always be blank. */
    @Test
    void onlyModelsTheAdapterActuallyHoldsAreOffered() {
        assertThat(OdooFieldMappingResolver.addressableModels())
                .contains("stock.move", "product.product", "sale.order.line")
                .doesNotContain("stock.warehouse");
    }
}
