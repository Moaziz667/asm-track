package com.asm.delivery.service;

import com.asm.delivery.dto.request.AssignDeliveryRequest;
import com.asm.delivery.dto.response.AdminDeliveryDetailResponse;
import com.asm.delivery.dto.response.AdminDeliverySummaryResponse;
import com.asm.delivery.dto.response.AdminDriverResponse;
import com.asm.delivery.dto.response.AdminStatsResponse;
import com.asm.delivery.dto.response.StatusHistoryResponse;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.DeliveryStatusHistory;
import com.asm.delivery.entity.FailureCode;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.OrderSource;
import com.asm.delivery.entity.OrderStatus;
import com.asm.delivery.odoo.OdooSyncService;
import com.asm.delivery.entity.Route;
import com.asm.delivery.entity.RouteStatus;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.DeliveryStatusHistoryRepository;
import com.asm.delivery.repository.OrderRepository;
import com.asm.delivery.repository.ProofOfDeliveryRepository;
import com.asm.delivery.repository.RouteRepository;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import com.asm.delivery.service.EventPublisher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminDeliveryService {

    private static final List<DeliveryStatus> ACTIVE_STATUSES = List.of(
            DeliveryStatus.ASSIGNED,
            DeliveryStatus.PICKED_UP,
            DeliveryStatus.IN_TRANSIT
    );

    private final DeliveryRepository deliveryRepo;
    private final TransportPort transportPort;
    private final DeliveryStatusHistoryRepository historyRepo;
    private final OrderRepository orderRepo;
    private final ProofOfDeliveryRepository podRepo;
    private final DriverDeliveryService driverDeliveryService;
    private final EntityManager entityManager;
    private final RouteRepository routeRepository;
    private final EventPublisher eventPublisher;
    private final OdooSyncService odooSyncService;

    // ── Search deliveries ─────────────────────────────────────────────────────

    public Page<AdminDeliverySummaryResponse> searchDeliveries(
            DeliveryStatus status,
            UUID driverId,
            LocalDate date,
            OrderSource source,
            String zone,
            Pageable pageable
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<Delivery> cq = cb.createQuery(Delivery.class);
        Root<Delivery> root = cq.from(Delivery.class);
        root.fetch("order", JoinType.INNER);
        List<Predicate> predicates = buildPredicates(cb, root, status, driverId, date, source, zone);
        cq.select(root).distinct(true).where(predicates.toArray(Predicate[]::new))
                .orderBy(cb.desc(root.get("createdAt")));

        TypedQuery<Delivery> query = entityManager.createQuery(cq);
        query.setFirstResult((int) pageable.getOffset());
        query.setMaxResults(pageable.getPageSize());
        List<Delivery> deliveries = query.getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Delivery> countRoot = countQuery.from(Delivery.class);
        List<Predicate> countPredicates = buildPredicates(cb, countRoot, status, driverId, date, source, zone);
        countQuery.select(cb.count(countRoot)).where(countPredicates.toArray(Predicate[]::new));
        long total = entityManager.createQuery(countQuery).getSingleResult();

        // Bulk-fetch driver info from Driver Service
        Map<String, DriverDTO> driverMap = loadDriverMap(deliveries);

        List<AdminDeliverySummaryResponse> content = deliveries.stream()
                .map(d -> {
                    DriverDTO driver = d.getDriverId() != null ? driverMap.get(d.getDriverId().toString()) : null;
                    return toSummaryResponse(d, driver);
                })
                .toList();

        return new PageImpl<>(content, pageable, total);
    }

    // ── Delivery detail ───────────────────────────────────────────────────────

    public AdminDeliveryDetailResponse getDeliveryDetail(UUID id) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(id)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        DriverDTO driver = null;
        if (delivery.getDriverId() != null) {
            driver = transportPort.getDriver(delivery.getDriverId().toString());
        }

        List<StatusHistoryResponse> history = historyRepo
                .findByDeliveryIdOrderByChangedAtAsc(delivery.getId()).stream()
                .map(this::toHistoryResponse)
                .toList();

        boolean podExists = podRepo.existsByDeliveryId(delivery.getId());

        return toDetailResponse(delivery, driver, history, podExists);
    }

    // ── Drivers list ──────────────────────────────────────────────────────────

    public List<AdminDriverResponse> getDrivers() {
                List<DriverDTO> drivers = new ArrayList<>(transportPort.getAvailableDrivers());

        // Map driverId → active deliveryId for currently active deliveries
        Map<String, UUID> activeDeliveryMap = deliveryRepo.findActiveDeliveries(ACTIVE_STATUSES).stream()
                .filter(d -> d.getDriverId() != null)
                .collect(Collectors.toMap(
                        d -> d.getDriverId().toString(),
                        Delivery::getId,
                        (existing, replacement) -> existing
                ));

                // Ensure busy drivers appear in the list even if the transport endpoint only returns available ones.
                java.util.Set<String> knownDriverIds = drivers.stream().map(DriverDTO::getId).collect(Collectors.toSet());
                for (String driverId : activeDeliveryMap.keySet()) {
                        if (!knownDriverIds.contains(driverId)) {
                                DriverDTO busyDriver = transportPort.getDriver(driverId);
                                if (busyDriver != null) {
                                        drivers.add(busyDriver);
                                        knownDriverIds.add(driverId);
                                }
                        }
                }

                LocalDate today = LocalDate.now();
                Map<String, UUID> activeRouteMap = routeRepository.findAll().stream()
                        .filter(route -> today.equals(route.getDate()))
                        .filter(route -> route.getStatus() == RouteStatus.VALIDATED || route.getStatus() == RouteStatus.IN_PROGRESS)
                        .collect(Collectors.toMap(
                                route -> route.getDriverId().toString(),
                                Route::getId,
                                (existing, replacement) -> existing
                        ));
                for (String driverId : activeRouteMap.keySet()) {
                        if (!knownDriverIds.contains(driverId)) {
                                DriverDTO busyDriver = transportPort.getDriver(driverId);
                                if (busyDriver != null) {
                                        drivers.add(busyDriver);
                                        knownDriverIds.add(driverId);
                                }
                        }
                }

        return drivers.stream()
                .map(d -> AdminDriverResponse.builder()
                        .id(parseUuid(d.getId()))
                        .name(d.getName())
                        .phone(d.getPhone())
                        .available(d.isAvailable()
                                && !activeDeliveryMap.containsKey(d.getId())
                                && !activeRouteMap.containsKey(d.getId()))
                        .currentLat(d.getCurrentLat() != null ? BigDecimal.valueOf(d.getCurrentLat()) : null)
                        .currentLng(d.getCurrentLng() != null ? BigDecimal.valueOf(d.getCurrentLng()) : null)
                        .activeDeliveryId(activeDeliveryMap.get(d.getId()))
                        .activeRouteId(activeRouteMap.get(d.getId()))
                        .build())
                .toList();
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    public AdminStatsResponse getStats() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime now = LocalDateTime.now();

        return AdminStatsResponse.builder()
                .today(buildTodayStats(startOfDay, now))
                .byDriver(buildDriverStats(startOfDay, now))
                .byFailureCode(buildFailureStats(startOfDay, now))
                .build();
    }

    // ── Assign ────────────────────────────────────────────────────────────────

    @Transactional
    public AdminDeliveryDetailResponse assignDelivery(UUID deliveryId, AssignDeliveryRequest request) {
        driverDeliveryService.accept(deliveryId, request.getDriverId());
        return getDeliveryDetail(deliveryId);
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    @Transactional
    public void cancelDelivery(UUID deliveryId, String reason) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        if (!List.of(DeliveryStatus.WAITING_DRIVER, DeliveryStatus.ASSIGNED, DeliveryStatus.PICKED_UP).contains(delivery.getStatus())) {
            throw AppException.badRequest("Cannot cancel delivery in status " + delivery.getStatus());
        }

        if (delivery.getDriverId() != null) {
            transportPort.setAvailability(delivery.getDriverId().toString(), true);
        }

        Order order = delivery.getOrder();

        // Clean up delivery history to avoid orphan records
        var history = historyRepo.findByDeliveryIdOrderByChangedAtAsc(deliveryId);
        historyRepo.deleteAll(history);

        // Delete the delivery entirely
        deliveryRepo.delete(delivery);

        // To truly "return to import state", we must delete the associated Order if it came from Odoo.
        // Otherwise, it gets stuck as PENDING locally but `alreadyImported` stays true in the dashboard.
        if (order != null && order.getSource() == OrderSource.ODOO) {
            orderRepo.delete(order);
        } else if (order != null) {
            order.setStatus(OrderStatus.PENDING);
            orderRepo.save(order);
        }
    }

    @Transactional
    public AdminDeliveryDetailResponse createBackorderDelivery(UUID deliveryId) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        Order order = delivery.getOrder();
        if (order == null) throw AppException.badRequest("No order attached to this delivery");

        if (order.getOdooBackorderId() == null) {
            throw AppException.badRequest("No Odoo Backorder ID registered for this order.");
        }

        // We clone the order to create a new delivery task
        // By appending "-B1" etc, we bypass unique constraint locally, while OdooClient still knows to look up the original sale.order
        String originalErpId = order.getErpOrderId();
        String newErpId = originalErpId != null ? originalErpId + "-B" + System.currentTimeMillis() : null;

        // Calculate remaining items
        List<com.asm.delivery.entity.OrderItem> remainingItems = new ArrayList<>();
        int newTotalQuantity = 0;

        if (order.getItems() != null) {
            for (com.asm.delivery.entity.OrderItem item : order.getItems()) {
                int planned = item.getQuantity() != null ? item.getQuantity() : 0;
                int done = item.getQuantityDone() != null ? item.getQuantityDone() : 0;
                int remaining = Math.max(planned - done, 0);

                if (remaining > 0) {
                    com.asm.delivery.entity.OrderItem clonedItem = new com.asm.delivery.entity.OrderItem();
                    clonedItem.setId(item.getId());
                    clonedItem.setSku(item.getSku());
                    clonedItem.setName(item.getName());
                    clonedItem.setQuantity(remaining);
                    clonedItem.setQuantityDone(0);
                    remainingItems.add(clonedItem);
                    newTotalQuantity += remaining;
                }
            }
        }

        if (remainingItems.isEmpty()) {
            throw AppException.badRequest("No remaining items to backorder");
        }

        Order backorder = Order.builder()
                .source(order.getSource())
                .schemaVersion(order.getSchemaVersion())
                .clientId(order.getClientId())
                .clientName(order.getClientName())
                .clientPhone(order.getClientPhone())
                .clientEmail(order.getClientEmail())
                .erpOrderId(newErpId)
                .erpClientId(order.getErpClientId())
                .erpExternalRef(order.getErpExternalRef())
                .originName(order.getOriginName())
                .originAddress(order.getOriginAddress())
                .originCity(order.getOriginCity())
                .originPostalCode(order.getOriginPostalCode())
                .originCountryCode(order.getOriginCountryCode())
                .originContactName(order.getOriginContactName())
                .originContactPhone(order.getOriginContactPhone())
                .originContactEmail(order.getOriginContactEmail())
                .dropoffAddress(order.getDropoffAddress())
                .dropoffCity(order.getDropoffCity())
                .dropoffPostalCode(order.getDropoffPostalCode())
                .dropoffCountryCode(order.getDropoffCountryCode())
                .dropoffLat(order.getDropoffLat())
                .dropoffLng(order.getDropoffLng())
                .deliveryInstructions(order.getDeliveryInstructions())
                .totalAmount(order.getTotalAmount())
                .currency(order.getCurrency())
                .priority(order.getPriority())
                .status(OrderStatus.PENDING)
                .items(remainingItems)
                .totalQuantity(newTotalQuantity)
                .odooSyncStatus(null) // Unsynced because we just created it
                .build();
                
        // Save the new Order
        backorder = orderRepo.save(backorder);

        // Delete Odoo Backorder ID from the original order because we processed it
        order.setOdooBackorderId(null);
        orderRepo.save(order);

        // Automatically create a Delivery task for this backorder
        Delivery newDelivery = Delivery.builder()
                .order(backorder)
                .status(DeliveryStatus.WAITING_DRIVER)
                .createdAt(LocalDateTime.now())
                .build();
        deliveryRepo.save(newDelivery);

        return getDeliveryDetail(delivery.getId()); 
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<Predicate> buildPredicates(CriteriaBuilder cb,
                                            Root<Delivery> root,
                                            DeliveryStatus status,
                                            UUID driverId,
                                            LocalDate date,
                                                                                        OrderSource source,
                                                                                        String zone) {
        List<Predicate> predicates = new ArrayList<>();
        if (status != null) {
            predicates.add(cb.equal(root.get("status"), status));
        }
        if (driverId != null) {
            predicates.add(cb.equal(root.get("driverId"), driverId));
        }
        if (date != null) {
            LocalDateTime start = date.atStartOfDay();
            LocalDateTime end = start.plusDays(1);
            predicates.add(cb.between(root.get("createdAt"), start, end));
        }
        if (source != null) {
            Join<Delivery, Order> orderJoin = root.join("order");
            predicates.add(cb.equal(orderJoin.get("source"), source));
        }
                if (StringUtils.hasText(zone)) {
                        Join<Delivery, Order> orderJoin = root.join("order");
                        String normalized = zone.trim().toLowerCase();
                        predicates.add(cb.equal(cb.lower(orderJoin.get("dropoffCity")), normalized));
                }
        return predicates;
    }

    /** Bulk-fetch all unique drivers needed for a list of deliveries. */
    private Map<String, DriverDTO> loadDriverMap(List<Delivery> deliveries) {
        Map<String, DriverDTO> map = new HashMap<>();
        deliveries.stream()
                .map(Delivery::getDriverId)
                .filter(Objects::nonNull)
                .map(UUID::toString)
                .distinct()
                .forEach(id -> {
                    DriverDTO dto = transportPort.getDriver(id);
                    if (dto != null) map.put(id, dto);
                });
        return map;
    }

    private AdminDeliverySummaryResponse toSummaryResponse(Delivery delivery, DriverDTO driver) {
        Order order = delivery.getOrder();
        return AdminDeliverySummaryResponse.builder()
                .deliveryId(delivery.getId())
                .orderId(order != null ? order.getId() : null)
                .status(delivery.getStatus().name())
                .source(order != null ? order.getSource() : null)
                .clientName(order != null ? order.getClientName() : null)
                .dropoffAddress(order != null ? order.getDropoffAddress() : null)
                .dropoffCity(order != null ? order.getDropoffCity() : null)
                .dropoffLat(order != null ? order.getDropoffLat() : null)
                .dropoffLng(order != null ? order.getDropoffLng() : null)
                .driverId(delivery.getDriverId())
                .driverName(driver != null ? driver.getName() : null)
                .driverPhone(driver != null ? driver.getPhone() : null)
                .totalAmount(order != null ? order.getTotalAmount() : null)
                .createdAt(delivery.getCreatedAt())
                .completedAt(delivery.getCompletedAt())
                .build();
    }

    private AdminDeliveryDetailResponse toDetailResponse(Delivery delivery,
                                                         DriverDTO driver,
                                                         List<StatusHistoryResponse> history,
                                                         boolean podExists) {
        Order order = delivery.getOrder();
        return AdminDeliveryDetailResponse.builder()
                .deliveryId(delivery.getId())
                .orderId(order != null ? order.getId() : null)
                .status(delivery.getStatus().name())
                .failureCode(delivery.getFailureCode())
                .failureComment(delivery.getFailReason())
                .driverId(delivery.getDriverId())
                .driverName(driver != null ? driver.getName() : null)
                .driverPhone(driver != null ? driver.getPhone() : null)
                .source(order != null ? order.getSource() : null)
                .erpOrderId(order != null ? order.getErpOrderId() : null)
                .clientName(order != null ? order.getClientName() : null)
                .clientPhone(order != null ? order.getClientPhone() : null)
                .clientEmail(order != null ? order.getClientEmail() : null)
                .dropoffAddress(order != null ? order.getDropoffAddress() : null)
                .dropoffCity(order != null ? order.getDropoffCity() : null)
                .deliveryInstructions(order != null ? order.getDeliveryInstructions() : null)
                .items(order != null ? order.getItems() : null)
                .totalAmount(order != null ? order.getTotalAmount() : null)
                .currency(order != null ? order.getCurrency() : null)
                .odooSyncStatus(order != null ? order.getOdooSyncStatus() : null)
                .odooBackorderId(order != null ? order.getOdooBackorderId() : null)
                .createdAt(delivery.getCreatedAt())
                .assignedAt(delivery.getAssignedAt())
                .pickedUpAt(delivery.getPickedUpAt())
                .inTransitAt(delivery.getInTransitAt())
                .completedAt(delivery.getCompletedAt())
                .failedAt(delivery.getFailedAt())
                .cancelledAt(delivery.getCancelledAt())
                .podExists(podExists)
                .statusHistory(history)
                .build();
    }

    private StatusHistoryResponse toHistoryResponse(DeliveryStatusHistory h) {
        return StatusHistoryResponse.builder()
                .status(h.getStatus().name())
                .changedBy(h.getChangedBy())
                .changedByRole(h.getChangedByRole() != null ? h.getChangedByRole().name() : null)
                .note(h.getNote())
                .changedAt(h.getChangedAt())
                .build();
    }

    private AdminStatsResponse.TodayStats buildTodayStats(LocalDateTime start, LocalDateTime end) {
        long total     = countByCreatedAt(start, end);
        long delivered = countByField("completedAt", start, end);
        long failed    = countByField("failedAt", start, end);
        long inTransit = countStatusWithin("inTransitAt", DeliveryStatus.IN_TRANSIT, start, end);
        long waiting   = countStatusWithin("createdAt", DeliveryStatus.WAITING_DRIVER, start, end);
        long assigned  = countStatusWithin("assignedAt", DeliveryStatus.ASSIGNED, start, end);
        double successRate = total > 0 ? (double) delivered / total : 0.0;

        return AdminStatsResponse.TodayStats.builder()
                .total(total).delivered(delivered).failed(failed)
                .inTransit(inTransit).waiting(waiting).assigned(assigned)
                .successRate(successRate)
                .build();
    }

    private long countByCreatedAt(LocalDateTime start, LocalDateTime end) {
        return entityManager.createQuery(
                "SELECT COUNT(d) FROM Delivery d WHERE d.createdAt BETWEEN :start AND :end", Long.class)
                .setParameter("start", start).setParameter("end", end).getSingleResult();
    }

    private long countByField(String fieldName, LocalDateTime start, LocalDateTime end) {
        return entityManager.createQuery(
                "SELECT COUNT(d) FROM Delivery d WHERE d." + fieldName + " BETWEEN :start AND :end", Long.class)
                .setParameter("start", start).setParameter("end", end).getSingleResult();
    }

    private long countStatusWithin(String timestampField, DeliveryStatus status, LocalDateTime start, LocalDateTime end) {
        return entityManager.createQuery(
                "SELECT COUNT(d) FROM Delivery d WHERE d.status = :status AND d." + timestampField + " BETWEEN :start AND :end",
                Long.class)
                .setParameter("status", status).setParameter("start", start).setParameter("end", end)
                .getSingleResult();
    }

    private List<AdminStatsResponse.DriverStats> buildDriverStats(LocalDateTime start, LocalDateTime end) {
        TypedQuery<Object[]> query = entityManager.createQuery(
                "SELECT d.driverId, COUNT(d), " +
                        "SUM(CASE WHEN d.status = :delivered THEN 1 ELSE 0 END), " +
                        "SUM(CASE WHEN d.status = :failed THEN 1 ELSE 0 END) " +
                        "FROM Delivery d WHERE d.driverId IS NOT NULL AND d.createdAt BETWEEN :start AND :end " +
                        "GROUP BY d.driverId",
                Object[].class);
        query.setParameter("delivered", DeliveryStatus.DELIVERED);
        query.setParameter("failed", DeliveryStatus.FAILED);
        query.setParameter("start", start);
        query.setParameter("end", end);

        return query.getResultList().stream()
                .map(row -> {
                    UUID driverId = (UUID) row[0];
                    long total    = row[1] != null ? ((Number) row[1]).longValue() : 0;
                    long del      = row[2] != null ? ((Number) row[2]).longValue() : 0;
                    long fail     = row[3] != null ? ((Number) row[3]).longValue() : 0;
                    double sr     = total > 0 ? (double) del / total : 0.0;

                    String driverName = null;
                    if (driverId != null) {
                        DriverDTO dto = transportPort.getDriver(driverId.toString());
                        if (dto != null) driverName = dto.getName();
                    }

                    return AdminStatsResponse.DriverStats.builder()
                            .driverId(driverId != null ? driverId.toString() : null)
                            .driverName(driverName)
                            .total(total).delivered(del).failed(fail).successRate(sr)
                            .build();
                })
                .toList();
    }

    private List<AdminStatsResponse.FailureStats> buildFailureStats(LocalDateTime start, LocalDateTime end) {
        TypedQuery<Object[]> query = entityManager.createQuery(
                "SELECT d.failureCode, COUNT(d) FROM Delivery d " +
                        "WHERE d.failureCode IS NOT NULL AND d.failedAt BETWEEN :start AND :end " +
                        "GROUP BY d.failureCode",
                Object[].class);
        query.setParameter("start", start).setParameter("end", end);

        return query.getResultList().stream()
                .map(row -> AdminStatsResponse.FailureStats.builder()
                        .code(row[0] != null ? row[0].toString() : null)
                        .count(row[1] != null ? ((Number) row[1]).longValue() : 0)
                        .build())
                .toList();
    }

    private static UUID parseUuid(String id) {
        if (id == null) return null;
        try { return UUID.fromString(id); } catch (IllegalArgumentException e) { return null; }
    }
}
