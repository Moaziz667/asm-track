package com.asm.erpadapter.mapping;

import com.asm.erpadapter.adapter.erpnext.ErpNextRestClient;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The ERPNext counterpart of {@link OdooFieldMappingResolverTest}.
 *
 * <p>The cases that matter are the ones where ERPNext differs from Odoo — a Link is a plain string,
 * and the scope is the Sales Order rather than a delivery note that does not exist yet.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ErpNextFieldMappingResolverTest {

    private static final UUID TENANT = UUID.fromString("3b2ae6fe-d67a-4c53-9b57-7bc997661408");

    @Mock private ErpFieldMappingRepository repository;
    @Mock private ErpNextRestClient rest;

    private ErpNextFieldMappingResolver resolver;

    private final Map<String, Map<String, Object>> records = Map.of(
            "Sales Order", Map.of(
                    "name", "SAL-ORD-2026-00027",
                    "customer", "Grant Plastics Ltd.",
                    "customer_name", "Grant Plastics Ltd.",
                    "custom_nom_destinataire", "Grant Plastics Ltd. — Réception magasin"),
            "Sales Order Item", Map.of("item_code", "SKU001", "custom_lot", "LOT-42"),
            "Customer", Map.of("mobile_no", "", "custom_tel_livraison", "+216 55 001 001"),
            "Address", Map.of("city", "Fairfield", "custom_ville_livraison", "Sousse"));

    @BeforeEach
    void setUp() {
        resolver = new ErpNextFieldMappingResolver(repository, rest);
        TenantContext.set(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void mapped(CanonicalField field, String path) {
        when(repository.findByTenantIdAndProviderAndCanonicalField(TENANT, "erpnext", field.name()))
                .thenReturn(Optional.of(ErpFieldMapping.builder()
                        .tenantId(TENANT).provider("erpnext").canonicalField(field.name())
                        .sourcePath(path).readAs("AUTO").build()));
    }

    private Object resolve(CanonicalField field, Object builtIn) {
        return resolver.resolveOrDefault(field, records, () -> builtIn);
    }

    // ── Scope ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void headerScopeIsTheOrder_notADeliveryNoteThatDoesNotExistYet() {
        // ERPNext creates the delivery note at delivery time. Offering it here would hand the
        // integrator a document with no rows to read, and every mapping would resolve to nothing.
        FieldMappingResolver.MappingScope header = resolver.scopeFor(CanonicalField.Scope.HEADER);
        assertThat(header.primary()).isEqualTo("Sales Order");
        assertThat(header.addressable()).doesNotContain("Delivery Note");
    }

    @Test
    void lineScopeLeadsWithTheOrderRowAndStillReachesTheHeader() {
        FieldMappingResolver.MappingScope line = resolver.scopeFor(CanonicalField.Scope.LINE);
        assertThat(line.primary()).isEqualTo("Sales Order Item");
        assertThat(line.addressable()).startsWith("Sales Order Item");
        assertThat(line.addressable()).contains("Sales Order");
    }

    @Test
    void headerScopeNeverOffersALineDocument() {
        // One per-order value cannot come from a document that has many rows.
        assertThat(resolver.scopeFor(CanonicalField.Scope.HEADER).addressable())
                .doesNotContain("Sales Order Item", "Item");
    }

    // ── Path shapes ───────────────────────────────────────────────────────────────────────────────

    @Test
    void aBareHeaderPathReadsAgainstTheOrder() {
        mapped(CanonicalField.CUSTOMER_NAME, "custom_nom_destinataire");
        assertThat(resolve(CanonicalField.CUSTOMER_NAME, "ignored"))
                .isEqualTo("Grant Plastics Ltd. — Réception magasin");
    }

    @Test
    void aBareLinePathReadsAgainstTheOrderRow_notTheOrder() {
        // The regression this guards: resolving a line path against the header returns nothing,
        // which on screen is indistinguishable from an empty ERP.
        mapped(CanonicalField.ITEM_SKU, "custom_lot");
        assertThat(resolve(CanonicalField.ITEM_SKU, "SKU001")).isEqualTo("LOT-42");
    }

    @Test
    void aQualifiedPathReadsAgainstTheDocumentItNames() {
        mapped(CanonicalField.CUSTOMER_PHONE, "Customer:custom_tel_livraison");
        assertThat(resolve(CanonicalField.CUSTOMER_PHONE, "+000")).isEqualTo("+216 55 001 001");
    }

    @Test
    void aPathNamingADocumentOutOfScopeResolvesToNothing() {
        mapped(CanonicalField.DELIVERY_CITY, "Delivery Note:city");
        assertThat(resolve(CanonicalField.DELIVERY_CITY, "Fairfield")).isNull();
    }

    // ── Values ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aLinkIsAPlainString_soThereIsNothingToInterpret() {
        // The whole reason SourceKind does not apply here: Odoo would return [42, "Grant..."] and
        // force the mapping to say which half it meant.
        mapped(CanonicalField.CUSTOMER_NAME, "customer");
        assertThat(resolve(CanonicalField.CUSTOMER_NAME, "ignored")).isEqualTo("Grant Plastics Ltd.");
    }

    @Test
    void aMappingThatResolvesToBlankWinsOverTheDefault() {
        // An override pointing at an empty field must show empty. Falling back to the standard value
        // would hide the wrong mapping behind plausible data.
        mapped(CanonicalField.CUSTOMER_PHONE, "Customer:mobile_no");
        assertThat(resolve(CanonicalField.CUSTOMER_PHONE, "+216 99 999 999")).isNull();
    }

    @Test
    void anUnmappedFieldGoesThroughTheCallersOwnReader() {
        assertThat(resolve(CanonicalField.DELIVERY_CITY, "Fairfield")).isEqualTo("Fairfield");
    }

    @Test
    void aBrokenPathReturnsNothingRatherThanFailingTheImport() {
        mapped(CanonicalField.DELIVERY_CITY, "no_such_field");
        assertThat(resolve(CanonicalField.DELIVERY_CITY, "Fairfield")).isNull();
    }

    @Test
    void anAbsurdlyDeepPathIsRefusedInsteadOfFetchingForever() {
        mapped(CanonicalField.DELIVERY_CITY, "a.b.c.d.e.f");
        assertThat(resolve(CanonicalField.DELIVERY_CITY, "Fairfield")).isNull();
        verify(rest, never()).getDoc(any(), any());
    }

    // ── Widening the adapter's field list ─────────────────────────────────────────────────────────

    @Test
    void extraFieldsAreClaimedOnlyByTheDocumentTheyBelongTo() {
        when(repository.findByTenantIdAndProvider(TENANT, "erpnext")).thenReturn(List.of(
                ErpFieldMapping.builder().tenantId(TENANT).provider("erpnext")
                        .canonicalField(CanonicalField.CUSTOMER_NAME.name())
                        .sourcePath("custom_nom_destinataire").readAs("AUTO").build(),
                ErpFieldMapping.builder().tenantId(TENANT).provider("erpnext")
                        .canonicalField(CanonicalField.CUSTOMER_PHONE.name())
                        .sourcePath("Customer:custom_tel_livraison").readAs("AUTO").build()));

        assertThat(resolver.extraFieldsFor("Sales Order")).containsExactly("custom_nom_destinataire");
        assertThat(resolver.extraFieldsFor("Customer")).containsExactly("custom_tel_livraison");
        assertThat(resolver.extraFieldsFor("Address")).isEmpty();
    }

    @Test
    void aLineMappingWidensTheRowDocument_notTheOrder() {
        when(repository.findByTenantIdAndProvider(TENANT, "erpnext")).thenReturn(List.of(
                ErpFieldMapping.builder().tenantId(TENANT).provider("erpnext")
                        .canonicalField(CanonicalField.ITEM_SKU.name())
                        .sourcePath("custom_lot").readAs("AUTO").build()));

        assertThat(resolver.extraFieldsFor("Sales Order Item")).containsExactly("custom_lot");
        assertThat(resolver.extraFieldsFor("Sales Order")).isEmpty();
    }

    // ── Extras ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void extrasAreKeyedByTheLabelTheIntegratorChose() {
        when(repository.findByTenantIdAndProviderAndCanonicalFieldIsNull(TENANT, "erpnext"))
                .thenReturn(List.of(ErpFieldMapping.builder()
                        .tenantId(TENANT).provider("erpnext").customKey("Num contrat")
                        .sourcePath("custom_nom_destinataire").readAs("AUTO").build()));

        assertThat(resolver.resolveCustomFields(records))
                .containsEntry("Num contrat", "Grant Plastics Ltd. — Réception magasin");
    }

    @Test
    void anExtraThatResolvesToNothingIsLeftOutOfTheBag() {
        when(repository.findByTenantIdAndProviderAndCanonicalFieldIsNull(TENANT, "erpnext"))
                .thenReturn(List.of(ErpFieldMapping.builder()
                        .tenantId(TENANT).provider("erpnext").customKey("Absent")
                        .sourcePath("no_such_field").readAs("AUTO").build()));

        assertThat(resolver.resolveCustomFields(records)).isEmpty();
    }

    @Test
    void withoutATenantNothingIsResolved() {
        TenantContext.clear();
        mapped(CanonicalField.CUSTOMER_NAME, "custom_nom_destinataire");
        assertThat(resolve(CanonicalField.CUSTOMER_NAME, "fallback")).isEqualTo("fallback");
        verify(repository, never())
                .findByTenantIdAndProviderAndCanonicalField(any(), eq("erpnext"), any());
    }
}
