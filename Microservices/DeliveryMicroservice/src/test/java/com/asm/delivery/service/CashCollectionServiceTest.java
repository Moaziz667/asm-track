package com.asm.delivery.service;

import com.asm.delivery.dto.request.ProofOfDeliveryRequest.CashCollectionEntry;
import com.asm.delivery.entity.CashCollection;
import com.asm.delivery.entity.CashCollectionStatus;
import com.asm.delivery.entity.CashMethod;
import com.asm.delivery.entity.Order;
import com.asm.delivery.repository.CashCollectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The rule this service exists to hold: <b>the money is never a gate</b>.
 *
 * <p>A driver at a shop door has already handed over the parcel. Refusing his proof because a money
 * field is missing or malformed would leave the delivery unrecorded — customer not credited, stock
 * not moved — while the cash sits in his pocket either way. So every bad input below is written down
 * and surfaced, never thrown.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CashCollectionServiceTest {

    @Mock CashCollectionRepository repo;
    @Mock FailureReasonService failureReasonService;

    CashCollectionService service;

    private final UUID deliveryId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CashCollectionService(repo, failureReasonService);
        when(repo.findByDeliveryId(any())).thenReturn(Optional.empty());
        when(failureReasonService.findLabel(any())).thenReturn(Optional.empty());
        when(repo.save(any(CashCollection.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static Order codOrder(String amount) {
        Order o = new Order();
        o.setId(UUID.randomUUID());
        o.setCodRequired(true);
        o.setCodAmount(new BigDecimal(amount));
        return o;
    }

    private static CashCollectionEntry entry(String amount, String method) {
        CashCollectionEntry e = new CashCollectionEntry();
        if (amount != null) e.setAmountCollected(new BigDecimal(amount));
        e.setMethod(method);
        return e;
    }

    @Test
    @DisplayName("records a full cash collection")
    void fullCollection() {
        CashCollection c = service.recordAtPod(codOrder("6000.000"), deliveryId, driverId, entry("6000.000", "CASH"));

        assertThat(c).isNotNull();
        assertThat(c.getStatus()).isEqualTo(CashCollectionStatus.COLLECTED);
        assertThat(c.getMethod()).isEqualTo(CashMethod.CASH);
        assertThat(c.getAmountExpected()).isEqualByComparingTo("6000.000");
    }

    @Test
    @DisplayName("a short payment is PARTIAL, not a failure")
    void partialCollection() {
        CashCollectionEntry e = entry("2000.000", "CASH");
        e.setReason("CLIENT_ABSENT");
        CashCollection c = service.recordAtPod(codOrder("6000.000"), deliveryId, driverId, e);

        assertThat(c.getStatus()).isEqualTo(CashCollectionStatus.PARTIAL);
        assertThat(c.getReason()).isEqualTo("CLIENT_ABSENT");
    }

    @Test
    @DisplayName("does nothing when the order is not COD")
    void ignoresNonCodOrder() {
        Order plain = new Order();
        plain.setId(UUID.randomUUID());
        plain.setCodRequired(false);

        assertThat(service.recordAtPod(plain, deliveryId, driverId, entry("100.000", "CASH"))).isNull();
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("a COD delivery proved with no report is still written down")
    void unreportedStillRecorded() {
        // Writing no row would make the money invisible: the delivery would look complete and the
        // expected sum would never reach anyone's reconciliation.
        CashCollection c = service.recordAtPod(codOrder("6000.000"), deliveryId, driverId, null);

        assertThat(c).isNotNull();
        assertThat(c.getStatus()).isEqualTo(CashCollectionStatus.REFUSED);
        assertThat(c.getReason()).isEqualTo(CashCollectionService.REASON_NOT_REPORTED);
        assertThat(c.getAmountCollected()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a cheque without a number is downgraded to cash, not rejected")
    void chequeWithoutNumberBecomesCash() {
        // The DB constraint refuses CHEQUE with no number; throwing here would cost the driver his
        // proof of delivery. The money changed hands regardless — keep it in the custody chain.
        CashCollection c = service.recordAtPod(codOrder("500.000"), deliveryId, driverId, entry("500.000", "CHEQUE"));

        assertThat(c.getMethod()).isEqualTo(CashMethod.CASH);
        assertThat(c.getChequeNumber()).isNull();
    }

    @Test
    @DisplayName("a cheque with its details is kept as a cheque")
    void chequeKeepsItsDetails() {
        CashCollectionEntry e = entry("500.000", "CHEQUE");
        e.setChequeNumber("1234567");
        e.setChequeBank("BIAT");
        e.setChequeDate("2026-08-15");

        CashCollection c = service.recordAtPod(codOrder("500.000"), deliveryId, driverId, e);

        assertThat(c.getMethod()).isEqualTo(CashMethod.CHEQUE);
        assertThat(c.getChequeNumber()).isEqualTo("1234567");
        assertThat(c.getChequeDate()).isNotNull();
    }

    @Test
    @DisplayName("an unparseable cheque date does not cost the driver his proof")
    void badChequeDateIsTolerated() {
        CashCollectionEntry e = entry("500.000", "CHEQUE");
        e.setChequeNumber("1234567");
        e.setChequeDate("15/08/2026");   // not ISO

        CashCollection c = service.recordAtPod(codOrder("500.000"), deliveryId, driverId, e);

        assertThat(c.getMethod()).isEqualTo(CashMethod.CHEQUE);
        assertThat(c.getChequeDate()).isNull();
    }

    @Test
    @DisplayName("an unknown method falls back to cash")
    void unknownMethodFallsBack() {
        CashCollection c = service.recordAtPod(codOrder("500.000"), deliveryId, driverId, entry("500.000", "BITCOIN"));
        assertThat(c.getMethod()).isEqualTo(CashMethod.CASH);
    }

    @Test
    @DisplayName("re-proving a delivery does not create a second collection")
    void idempotentOnReplay() {
        CashCollection existing = CashCollection.builder().deliveryId(deliveryId).build();
        when(repo.findByDeliveryId(deliveryId)).thenReturn(Optional.of(existing));

        assertThat(service.recordAtPod(codOrder("6000.000"), deliveryId, driverId, entry("6000.000", "CASH")))
                .isSameAs(existing);
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("a negative amount is floored at zero rather than stored")
    void negativeAmountFloored() {
        CashCollection c = service.recordAtPod(codOrder("500.000"), deliveryId, driverId, entry("-50.000", "CASH"));

        assertThat(c.getAmountCollected()).isEqualByComparingTo("0");
        assertThat(c.getStatus()).isEqualTo(CashCollectionStatus.REFUSED);
        assertThat(c.getMethod()).isEqualTo(CashMethod.NONE);
    }
}
