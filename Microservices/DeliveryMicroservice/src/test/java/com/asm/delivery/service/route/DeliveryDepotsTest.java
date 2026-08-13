package com.asm.delivery.service.route;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.OrderItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryDepotsTest {

    private static final UUID TUNIS = UUID.randomUUID();
    private static final UUID SOUSSE = UUID.randomUUID();

    /**
     * The case that shipped broken: a note whose header names one warehouse and whose lines name two.
     * The Sousse pickup was placed correctly and then reported as holding nothing, because the answer
     * was read off the header.
     */
    @Test
    void deliveryIsLoadedAtEveryDepotItsLinesName() {
        Delivery d = delivery(TUNIS, item("EPI-DAT-010", TUNIS), item("BOI-EAU-006", SOUSSE));

        assertThat(DeliveryDepots.of(d)).containsExactly(TUNIS, SOUSSE);
        assertThat(DeliveryDepots.isLoadedAt(d, SOUSSE)).isTrue();
        assertThat(DeliveryDepots.isLoadedAt(d, TUNIS)).isTrue();
    }

    @Test
    void onlyTheLinesOfThatDepotAreCollectedThere() {
        Delivery d = delivery(TUNIS, item("EPI-DAT-010", TUNIS), item("BOI-EAU-006", SOUSSE));

        assertThat(DeliveryDepots.linesAt(d, SOUSSE)).extracting(OrderItem::getSku).containsExactly("BOI-EAU-006");
        assertThat(DeliveryDepots.linesAt(d, TUNIS)).extracting(OrderItem::getSku).containsExactly("EPI-DAT-010");
    }

    /** An ERP that issues one note per warehouse leaves the lines bare — it must behave as before. */
    @Test
    void anOrderWithoutLineDepotsAnswersWithItsOwn() {
        Delivery d = delivery(TUNIS, item("EPI-DAT-010", null), item("BOI-EAU-006", null));

        assertThat(DeliveryDepots.of(d)).containsExactly(TUNIS);
        assertThat(DeliveryDepots.isLoadedAt(d, SOUSSE)).isFalse();
        assertThat(DeliveryDepots.linesAt(d, TUNIS))
                .extracting(OrderItem::getSku)
                .containsExactly("EPI-DAT-010", "BOI-EAU-006");
    }

    @Test
    void aDeliveryWithNoDepotAtAllIsLoadedNowhere() {
        Delivery d = delivery(null);

        assertThat(DeliveryDepots.of(d)).isEmpty();
        assertThat(DeliveryDepots.isLoadedAt(d, TUNIS)).isFalse();
        assertThat(DeliveryDepots.linesAt(d, TUNIS)).isEmpty();
    }

    private static Delivery delivery(UUID headerDepot, OrderItem... items) {
        Order order = new Order();
        order.setItems(List.of(items));
        Delivery d = new Delivery();
        d.setId(UUID.randomUUID());
        d.setSourceDepotId(headerDepot);
        d.setOrder(order);
        return d;
    }

    private static OrderItem item(String sku, UUID depot) {
        OrderItem i = new OrderItem();
        i.setSku(sku);
        i.setSourceDepotId(depot);
        return i;
    }
}
