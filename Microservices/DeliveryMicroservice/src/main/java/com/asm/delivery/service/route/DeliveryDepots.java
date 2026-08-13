package com.asm.delivery.service.route;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.OrderItem;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Where a shipment is loaded from, and which of its lines come from each place.
 *
 * <p>Two ERPs answer this differently and both are right. Odoo issues one delivery note per
 * warehouse, so the note's own depot is the whole answer. ERPNext puts a warehouse on every line,
 * so one note can need two loads. The mapping layer already reconciles that upstream: whatever the
 * ERP calls its line-level warehouse arrives here as {@link OrderItem#getSourceDepotId()}, so
 * nothing below names a vendor.
 *
 * <p>This lives on its own because two callers must never disagree about it. The reconciler decides
 * where pickups are inserted; the response mapper decides what the pickup stop says it holds. When
 * only the reconciler learned about per-line depots, a pickup was correctly placed in Sousse and
 * then labelled with nothing to collect, because the count was still matching on the header.
 */
final class DeliveryDepots {

    private DeliveryDepots() {
    }

    /**
     * Every depot this delivery draws from, in line order.
     *
     * <p>Lines win when they carry a depot; the order's own depot answers for everything imported
     * before they did, and for ERPs that only ever have one.
     */
    static Set<UUID> of(Delivery delivery) {
        Set<UUID> fromLines = linesWithDepot(delivery).stream()
                .map(OrderItem::getSourceDepotId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!fromLines.isEmpty()) {
            return fromLines;
        }
        return delivery.getSourceDepotId() != null ? Set.of(delivery.getSourceDepotId()) : Set.of();
    }

    /** Whether any of this delivery's goods are collected at {@code depotId}. */
    static boolean isLoadedAt(Delivery delivery, UUID depotId) {
        return depotId != null && of(delivery).contains(depotId);
    }

    /**
     * The lines to physically pick up at {@code depotId}.
     *
     * <p>When no line carries a depot the whole order is collected in one place, so the whole order
     * is the answer — a single-warehouse ERP therefore lists exactly what it always did.
     */
    static List<OrderItem> linesAt(Delivery delivery, UUID depotId) {
        List<OrderItem> tagged = linesWithDepot(delivery);
        if (tagged.isEmpty()) {
            return items(delivery);
        }
        return tagged.stream()
                .filter(i -> i.getSourceDepotId().equals(depotId))
                .toList();
    }

    private static List<OrderItem> linesWithDepot(Delivery delivery) {
        return items(delivery).stream()
                .filter(i -> i.getSourceDepotId() != null)
                .toList();
    }

    private static List<OrderItem> items(Delivery delivery) {
        Order order = delivery != null ? delivery.getOrder() : null;
        return order != null && order.getItems() != null
                ? order.getItems().stream().filter(Objects::nonNull).toList()
                : List.of();
    }
}
