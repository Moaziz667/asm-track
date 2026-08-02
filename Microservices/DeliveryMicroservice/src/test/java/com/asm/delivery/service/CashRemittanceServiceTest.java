package com.asm.delivery.service;

import com.asm.delivery.entity.CashCollection;
import com.asm.delivery.entity.CashRemittance;
import com.asm.delivery.entity.CashRemittanceStatus;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.CashCollectionRepository;
import com.asm.delivery.repository.CashRemittanceRepository;
import com.asm.delivery.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The separation that makes the count mean anything.
 *
 * <p>A driver who can confirm his own handover has typed a number twice, and every table around it
 * becomes decoration. These tests are the module: the rest is bookkeeping around them.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CashRemittanceServiceTest {

    @Mock CashRemittanceRepository remittanceRepo;
    @Mock CashCollectionRepository collectionRepo;
    @Mock AuditLogService auditLogService;

    CashRemittanceService service;

    private final UUID driverId = UUID.randomUUID();
    private final UUID dispatcherId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CashRemittanceService(remittanceRepo, collectionRepo, auditLogService);
        when(remittanceRepo.save(any(CashRemittance.class))).thenAnswer(i -> {
            CashRemittance r = i.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
        when(remittanceRepo.findFirstByDriverIdAndStatusIn(any(), any())).thenReturn(Optional.empty());
    }

    private static UserPrincipal user(UUID id) {
        return new UserPrincipal(id.toString(), "DISPATCHER", "Someone", null, UUID.randomUUID());
    }

    private static CashCollection collected(String amount) {
        return CashCollection.builder()
                .id(UUID.randomUUID())
                .amountCollected(new BigDecimal(amount))
                .build();
    }

    private void driverHolds(String... amounts) {
        when(collectionRepo.findByDriverIdAndRemittanceIdIsNull(driverId))
                .thenReturn(java.util.Arrays.stream(amounts).map(CashRemittanceServiceTest::collected).toList());
    }

    // ── Declaration ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("expectedTotal comes from the collections, not from the driver's request")
    void expectedIsComputedNotSupplied() {
        // The one figure in the row neither party can move.
        driverHolds("2000.000", "1400.000");

        CashRemittance r = service.declare(driverId, "Ali", new BigDecimal("3400.000"), user(driverId));

        assertThat(r.getExpectedTotal()).isEqualByComparingTo("3400.000");
        assertThat(r.getStatus()).isEqualTo(CashRemittanceStatus.DECLARED);
    }

    @Test
    @DisplayName("a driver declaring less than he took is still recorded — the gap is the point")
    void underDeclarationIsRecorded() {
        driverHolds("2000.000", "1400.000");

        CashRemittance r = service.declare(driverId, "Ali", new BigDecimal("3000.000"), user(driverId));

        assertThat(r.getExpectedTotal()).isEqualByComparingTo("3400.000");
        assertThat(r.getDeclaredTotal()).isEqualByComparingTo("3000.000");
    }

    @Test
    @DisplayName("the collections are attached to the handover")
    void collectionsAreAttached() {
        driverHolds("500.000");
        service.declare(driverId, "Ali", new BigDecimal("500.000"), user(driverId));
        verify(collectionRepo).saveAll(any());
    }

    @Test
    @DisplayName("refuses a second handover while one is in flight")
    void refusesConcurrentHandover() {
        driverHolds("500.000");
        when(remittanceRepo.findFirstByDriverIdAndStatusIn(any(), any()))
                .thenReturn(Optional.of(CashRemittance.builder().status(CashRemittanceStatus.DECLARED).build()));

        assertThatThrownBy(() -> service.declare(driverId, "Ali", new BigDecimal("500.000"), user(driverId)))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("refuses a handover with nothing to hand over")
    void refusesEmptyHandover() {
        when(collectionRepo.findByDriverIdAndRemittanceIdIsNull(driverId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.declare(driverId, "Ali", new BigDecimal("0"), user(driverId)))
                .isInstanceOf(AppException.class);
    }

    // ── The two-party rule ───────────────────────────────────────────────────

    private CashRemittance declared(UUID declaredBy) {
        CashRemittance r = CashRemittance.builder()
                .id(UUID.randomUUID())
                .driverId(driverId)
                .status(CashRemittanceStatus.DECLARED)
                .expectedTotal(new BigDecimal("3400.000"))
                .declaredTotal(new BigDecimal("3400.000"))
                .declaredBy(declaredBy)
                .build();
        when(remittanceRepo.findById(r.getId())).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    @DisplayName("the person who declared cannot be the person who counts")
    void declarerCannotCount() {
        CashRemittance r = declared(dispatcherId);

        assertThatThrownBy(() -> service.receive(r.getId(), new BigDecimal("3400.000"), null, user(dispatcherId)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("déclaré");
    }

    @Test
    @DisplayName("a driver cannot count his own handover")
    void driverCannotCountOwnHandover() {
        CashRemittance r = declared(UUID.randomUUID());

        assertThatThrownBy(() -> service.receive(r.getId(), new BigDecimal("3400.000"), null, user(driverId)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("livreur");
    }

    // ── Counting ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a matching count closes the handover on its own")
    void matchingCountReconciles() {
        CashRemittance r = declared(UUID.randomUUID());

        CashRemittance out = service.receive(r.getId(), new BigDecimal("3400.000"), null, user(dispatcherId));

        assertThat(out.getStatus()).isEqualTo(CashRemittanceStatus.RECONCILED);
        assertThat(out.getDiscrepancy()).isEqualByComparingTo("0");
        assertThat(out.getClosedAt()).isNotNull();
    }

    @Test
    @DisplayName("a short count opens a dispute, and the gap is measured against what he took")
    void shortCountDisputes() {
        // Not against what he declared: a driver who declares 3 350 for 3 400 taken has already
        // lost 50, and comparing his own two numbers would show nothing.
        CashRemittance r = declared(UUID.randomUUID());

        CashRemittance out = service.receive(r.getId(), new BigDecimal("3350.000"), null, user(dispatcherId));

        assertThat(out.getStatus()).isEqualTo(CashRemittanceStatus.DISPUTED);
        assertThat(out.getDiscrepancy()).isEqualByComparingTo("-50.000");
        assertThat(out.getClosedAt()).isNull();
    }

    @Test
    @DisplayName("counting twice is refused")
    void cannotCountTwice() {
        CashRemittance r = declared(UUID.randomUUID());
        r.setStatus(CashRemittanceStatus.RECONCILED);

        assertThatThrownBy(() -> service.receive(r.getId(), new BigDecimal("3400.000"), null, user(dispatcherId)))
                .isInstanceOf(AppException.class);
    }

    // ── Settling ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a discrepancy cannot be closed without an explanation")
    void reconcileNeedsANote() {
        CashRemittance r = declared(UUID.randomUUID());
        r.setStatus(CashRemittanceStatus.DISPUTED);
        r.setDiscrepancy(new BigDecimal("-50.000"));

        assertThatThrownBy(() -> service.reconcile(r.getId(), "   ", user(dispatcherId)))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("settling records who explained it and when")
    void reconcileIsAttributed() {
        CashRemittance r = declared(UUID.randomUUID());
        r.setStatus(CashRemittanceStatus.DISPUTED);
        r.setDiscrepancy(new BigDecimal("-50.000"));

        CashRemittance out = service.reconcile(r.getId(), "erreur de rendu de monnaie", user(dispatcherId));

        assertThat(out.getStatus()).isEqualTo(CashRemittanceStatus.RECONCILED);
        assertThat(out.getReconciledBy()).isEqualTo(dispatcherId);
        assertThat(out.getReconciledAt()).isNotNull();
        assertThat(out.getNote()).isEqualTo("erreur de rendu de monnaie");
    }

    @Test
    @DisplayName("only a disputed handover can be settled")
    void onlyDisputedCanBeSettled() {
        CashRemittance r = declared(UUID.randomUUID());

        assertThatThrownBy(() -> service.reconcile(r.getId(), "peu importe", user(dispatcherId)))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("a failing audit log never undoes a movement of money")
    void auditFailureDoesNotBreakTheFlow() {
        doThrow(new RuntimeException("audit down"))
                .when(auditLogService).logAction(any(), any(), any(), any(), any());
        CashRemittance r = declared(UUID.randomUUID());

        assertThat(service.receive(r.getId(), new BigDecimal("3400.000"), null, user(dispatcherId)).getStatus())
                .isEqualTo(CashRemittanceStatus.RECONCILED);
    }
}
