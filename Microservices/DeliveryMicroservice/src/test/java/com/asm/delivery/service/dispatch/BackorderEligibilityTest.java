package com.asm.delivery.service.dispatch;

import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.OrderItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.4 — A refused line should only suppress the backorder when it is a PURE refusal. A refusal for a
 * defect / wrong item / postponement means the customer still wants the product, so it must be
 * re-delivered (backorder eligible).
 */
class BackorderEligibilityTest {

    private static Order orderWith(OrderItem... items) {
        Order o = new Order();
        o.setItems(List.of(items));
        return o;
    }

    private static OrderItem line(int planned, int done, String outcome, String reason) {
        return OrderItem.builder()
                .sku("SKU1").name("Item").quantity(planned).quantityDone(done)
                .outcome(outcome).reason(reason).build();
    }

    @Test
    void shortShipNotRefused_isEligible() {
        // delivered 1 of 3, not refused → re-deliver the remainder
        assertThat(ExceptionResolutionService.hasBackorderEligibleRemainder(
                orderWith(line(3, 1, "DELIVERED", null)))).isTrue();
    }

    @Test
    void refusedDamaged_isEligible() {
        assertThat(ExceptionResolutionService.hasBackorderEligibleRemainder(
                orderWith(line(1, 0, "REFUSED", "DAMAGED")))).isTrue();
    }

    @Test
    void refusedWrongItem_isEligible() {
        assertThat(ExceptionResolutionService.hasBackorderEligibleRemainder(
                orderWith(line(1, 0, "REFUSED", "WRONG_ITEM")))).isTrue();
    }

    @Test
    void refusedPostponed_isEligible() {
        assertThat(ExceptionResolutionService.hasBackorderEligibleRemainder(
                orderWith(line(1, 0, "REFUSED", "POSTPONED")))).isTrue();
    }

    @Test
    void refusedOutright_isNotEligible() {
        // CLIENT_REJECTED = the customer no longer wants it → no re-delivery
        assertThat(ExceptionResolutionService.hasBackorderEligibleRemainder(
                orderWith(line(1, 0, "REFUSED", "CLIENT_REJECTED")))).isFalse();
    }

    @Test
    void refusedNoReason_isNotEligible() {
        assertThat(ExceptionResolutionService.hasBackorderEligibleRemainder(
                orderWith(line(1, 0, "REFUSED", null)))).isFalse();
    }

    @Test
    void fullyDelivered_isNotEligible() {
        assertThat(ExceptionResolutionService.hasBackorderEligibleRemainder(
                orderWith(line(2, 2, "DELIVERED", null)))).isFalse();
    }

    @Test
    void mixed_refusedRejectedPlusShortShip_isEligible() {
        // one line refused-rejected (no), one line short-shipped (yes) → overall eligible
        assertThat(ExceptionResolutionService.hasBackorderEligibleRemainder(orderWith(
                line(1, 0, "REFUSED", "CLIENT_REJECTED"),
                line(5, 2, "DELIVERED", null)))).isTrue();
    }
}
