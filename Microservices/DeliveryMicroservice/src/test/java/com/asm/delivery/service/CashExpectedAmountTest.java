package com.asm.delivery.service;

import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.OrderItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a driver is expected to have collected, once the delivery has actually happened.
 *
 * <p>The amount stored on the order describes the delivery note as it left the depot. It stops being
 * true the moment a customer refuses part of it at the door: less is handed over, less is owed, less
 * is paid — and the delivery used to be filed as a cash shortfall the driver had to account for, over
 * money nobody was owed.
 */
class CashExpectedAmountTest {

    private final CashCollectionService service = new CashCollectionService(null, null);

    private static OrderItem line(int ordered, int delivered, String unitTtc) {
        OrderItem item = new OrderItem();
        item.setQuantity(ordered);
        item.setQuantityDone(delivered);
        if (unitTtc != null) item.setUnitPriceTtc(new BigDecimal(unitTtc));
        return item;
    }

    private static Order order(BigDecimal stored, OrderItem... items) {
        Order o = new Order();
        o.setCodAmount(stored);
        o.setItems(List.of(items));
        return o;
    }

    private BigDecimal expected(Order o) {
        return (BigDecimal) ReflectionTestUtils.invokeMethod(
                service, "expectedFor", o, UUID.randomUUID());
    }

    @Test
    @DisplayName("everything handed over: the stored amount stands")
    void fullHandoverMatchesStored() {
        Order o = order(new BigDecimal("3200.000"), line(4, 4, "800.000"));

        assertThat(expected(o)).isEqualByComparingTo("3200.000");
    }

    @Test
    @DisplayName("half refused at the door: only what was taken is owed")
    void refusalAtTheDoorLowersWhatIsOwed() {
        // The note said four; the customer took two. Owing 3 200 would make a correct payment of
        // 1 600 look like a driver 1 600 short.
        Order o = order(new BigDecimal("3200.000"), line(4, 2, "800.000"));

        assertThat(expected(o)).isEqualByComparingTo("1600.000");
    }

    @Test
    @DisplayName("everything refused: nothing is owed")
    void everythingRefusedOwesNothing() {
        Order o = order(new BigDecimal("3200.000"), line(4, 0, "800.000"));

        assertThat(expected(o)).isEqualByComparingTo("0.000");
    }

    @Test
    @DisplayName("several lines, only one short")
    void mixedLines() {
        Order o = order(new BigDecimal("3672.800"),
                line(4, 4, "800.000"),      // 3 200
                line(12, 6, "39.400"));     //   236,40

        assertThat(expected(o)).isEqualByComparingTo("3436.400");
    }

    @Test
    @DisplayName("a line with no taxed price falls back to the stored amount, never to the untaxed one")
    void missingTaxedPriceFallsBack() {
        // Recomputing from price_unit would silently under-state the expectation by the VAT: a wrong
        // number that looks right, which is worse than one that has simply not been adjusted.
        Order o = order(new BigDecimal("3200.000"), line(4, 2, null));

        assertThat(expected(o)).isEqualByComparingTo("3200.000");
    }

    @Test
    @DisplayName("an order with no lines falls back to the stored amount")
    void noLinesFallsBack() {
        Order o = new Order();
        o.setCodAmount(new BigDecimal("1500.000"));
        o.setItems(List.of());

        assertThat(expected(o)).isEqualByComparingTo("1500.000");
    }

    @Test
    @DisplayName("no stored amount and nothing to recompute from yields zero, not null")
    void nothingAtAllYieldsZero() {
        Order o = new Order();
        o.setItems(List.of());

        assertThat(expected(o)).isEqualByComparingTo("0");
    }
}
