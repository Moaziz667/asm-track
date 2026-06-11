package com.asm.delivery.service.dispatch;

import com.asm.delivery.entity.Order;
import java.time.LocalTime;
import java.time.LocalDate;
import java.util.stream.Collectors;

import com.asm.delivery.dto.request.AdminExceptionReassignRequest;
import com.asm.delivery.dto.request.AdminExceptionReplanRequest;
import com.asm.delivery.dto.response.AdminDeliveryDetailResponse;
import com.asm.delivery.dto.response.AdminOpsExceptionsResponse;
import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.*;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import com.asm.delivery.service.AuditLogService;
import com.asm.delivery.service.DelayCalculationService;
import com.asm.delivery.service.EventPublisher;
import com.asm.delivery.service.OutboxProcessor;
import com.asm.delivery.service.RouteOptimizationService;
import com.asm.delivery.service.route.RouteWebSocketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExceptionResolutionService {

    private static final List<DeliveryStatus> REASSIGN_ALLOWED_STATUSES = List.of(
            DeliveryStatus.UNSCHEDULED,
            DeliveryStatus.SCHEDULED,
            DeliveryStatus.PICKED_UP,
            DeliveryStatus.IN_TRANSIT
    );

    private static final List<DeliveryStatus> REPLAN_ALLOWED_STATUSES = List.of(
            DeliveryStatus.SCHEDULED,
            DeliveryStatus.FAILED,
            DeliveryStatus.PARTIALLY_DELIVERED
    );

    private final DeliveryRepository deliveryRepo;
    private final TransportPort transportPort;
    private final DeliveryStatusHistoryRepository historyRepo;
    private final OrderRepository orderRepo;
    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final AuditLogService auditLogService;
    private final EventPublisher eventPublisher;
    private final DelayCalculationService delayCalculationService;
    private final RouteOptimizationService routeOptimizationService;
    private final ZoneRepository zoneRepository;
    private final DispatchService dispatchService;
    private final RouteWebSocketService routeWebSocketService;
    private final OutboxProcessor outboxProcessor;
    private final com.asm.delivery.service.HandoffService handoffService;
    private final com.asm.delivery.sla.SlaStateService slaStateService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private ExceptionResolutionService self;

    @org.springframework.beans.factory.annotation.Autowired
    public void setSelf(@org.springframework.context.annotation.Lazy ExceptionResolutionService self) {
        this.self = self;
    }

	public AdminOpsExceptionsResponse.ExceptionItem reassignException(UUID deliveryId,
																							  AdminExceptionReassignRequest request,
																							  UserPrincipal principal) {
		// 1. Transactional Mutation
		Set<UUID> affectedRouteIds = self.doReassign(deliveryId, request, principal);

		// 2. Post-Transaction Optimization (Outside DB Lock)
		for (UUID routeId : affectedRouteIds) {
			if (routeId == null) continue;
			routeOptimizationService.recalculate(routeId);
		}

		// 3. Return final state — must use join-fetch variant so Order proxy is
		//    initialized before mapActionResult() accesses order.getZoneId() outside a session.
		Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
				.orElseThrow(() -> AppException.notFound("Delivery not found"));
		return mapActionResult(delivery, "WARNING", "RESCHEDULED", "Delivery reassigned successfully");
	}

	@Transactional
	public Set<UUID> doReassign(UUID deliveryId,
								AdminExceptionReassignRequest request,
								UserPrincipal principal) {
		Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
				.orElseThrow(() -> AppException.notFound("Delivery not found"));

		assertReassignAllowed(delivery);

		// When a parcel is already picked up, reassignment implies a physical handover.
		// We require a note to keep custody changes explicit in the audit trail.
		if ((delivery.getStatus() == DeliveryStatus.PICKED_UP || delivery.getStatus() == DeliveryStatus.IN_TRANSIT)
				&& !StringUtils.hasText(request.getNote())) {
			throw AppException.badRequest("A handover note is required to reassign a delivery already in the field");
		}

		if (request.getDriverId().equals(delivery.getDriverId())) {
			Optional<RouteStop> currentStop = routeStopRepository.findActiveByDeliveryIdWithRoute(delivery.getId());
			if (currentStop.isPresent()) {
				UUID currentRouteId = currentStop.get().getRoute().getId();
				if (request.getTargetRouteId() == null || request.getTargetRouteId().equals(currentRouteId)) {
					throw AppException.badRequest("Delivery is already assigned to this driver on this route");
				}
			}
		}

		// DELETED: Blocking transportPort.getDriver() inside @Transactional.
		// We rely on frontend validity for the target driver.

		DeliveryStatus previousStatus = delivery.getStatus();
		UUID previousDriverId = delivery.getDriverId();

		// Determine the target route and its status early to apply the correct business rules
		Route targetRoute = null;
		if (request.getTargetRouteId() != null) {
			targetRoute = routeRepository.findById(request.getTargetRouteId())
					.orElseThrow(() -> AppException.notFound("Target route not found"));
			if (!request.getDriverId().equals(targetRoute.getDriverId())) {
				throw AppException.badRequest("Target route does not belong to the selected driver");
			}
		}

		RouteStatus targetRouteStatus;
		if (targetRoute != null) {
			targetRouteStatus = targetRoute.getStatus();
		} else {
			// Logic equivalent to findOrCreateRouteForDriver inheritance
			Optional<RouteStop> currentStop = routeStopRepository.findActiveByDeliveryIdWithRoute(delivery.getId());
			Route sourceRoute = currentStop.map(RouteStop::getRoute).orElse(null);
			targetRouteStatus = (sourceRoute != null && (sourceRoute.getStatus() == RouteStatus.VALIDATED || sourceRoute.getStatus() == RouteStatus.IN_PROGRESS))
					? RouteStatus.VALIDATED : RouteStatus.DRAFT;
		}

		// If assigning to a DRAFT route, we must ensure it's pinned (same as Builder logic)
		if (targetRouteStatus == RouteStatus.DRAFT) {
			assertPinned(delivery);
		}

		LocalDateTime now = LocalDateTime.now();
		boolean wasInField = previousStatus == DeliveryStatus.PICKED_UP || previousStatus == DeliveryStatus.IN_TRANSIT;
		delivery.setDriverId(request.getDriverId());

		// CRITICAL: If target is DRAFT, keep it UNSCHEDULED (Draft Planning).
		// If target is VALIDATED/IN_PROGRESS, it becomes SCHEDULED immediately (Execution Reassign).
		if (targetRouteStatus == RouteStatus.DRAFT) {
			delivery.setStatus(DeliveryStatus.UNSCHEDULED);
		} else {
			delivery.setStatus(DeliveryStatus.SCHEDULED);
		}

		delivery.setAssignedAt(now);
		// Keep pickedUpAt/inTransitAt as audit record if the parcel was already collected by the previous driver
		if (!wasInField) {
			delivery.setPickedUpAt(null);
		}
		delivery.setInTransitAt(null);
		delivery.setCompletedAt(null);
		delivery.setWaitingSlaMinutes(delayCalculationService.calculateWaitingSlaMinutes(delivery));
		delivery.setAssignSlaMinutes(null);
		delivery.setPickupSlaMinutes(null);
		delivery.setCancelledAt(null);
		delivery.setCancelReason(null);
		delivery.setCancelledBy(null);
		// failedAt and failureCode are kept for historical reporting

		deliveryRepo.save(delivery);

		ActorInfo actor = resolveActor(principal);
		String previousDriverName = previousDriverId != null ? previousDriverId.toString().substring(0, 8) : "UNKNOWN";
		String targetDriverName = request.getDriverId().toString().substring(0, 8);
		String clientName = delivery.getOrder() != null ? delivery.getOrder().getClientName() : "UNKNOWN";

		Map<String, Object> auditDetails = Map.of(
				"action", "reassign",
				"delivery", shortDeliveryId(delivery.getId()),
				"client", clientName,
				"fromDriver", previousDriverName,
				"toDriver", targetDriverName,
				"reason", request.getNote() != null ? request.getNote() : ""
		);

		auditLogService.logAction(principal, "REASSIGN_DELIVERY", "DELIVERY", delivery.getId().toString(), auditDetails);

		// Keep route plan consistent with ownership change: move stop to the new driver's route.
		Set<UUID> affectedRouteIds = moveStopToDriverRoute(
				delivery,
				request.getDriverId(),
				request.getTargetRouteId(),
				request.getInsertAtOrder(),
				actor.name(),
				request.getStartTimeWindow(),
				request.getEndTimeWindow()
		);

		// Custody handoff (parcel already in the field) is opened below, after the
		// stop has been moved onto the new driver's route — see the event section.

		// RECALCULATION MOVED TO ORCHESTRATOR

		if (previousDriverId != null && !previousDriverId.equals(request.getDriverId())) {
			auditLogService.logAction(principal, "DELIVERY_REMOVED_FROM_ROUTE", "DELIVERY", delivery.getId().toString(),
					Map.of("action", "removed_by_reassign", "fromDriver", previousDriverName, "toDriver", targetDriverName,
							"client", clientName));
		}

		if (targetRouteStatus == RouteStatus.DRAFT) {
			appendHistory(delivery,
							DeliveryStatus.UNSCHEDULED,
							actor.name(),
							actor.role(),
							"DELIVERY_ASSIGNED_TO_DRAFT",
							Map.of("routeName", targetRoute != null ? targetRoute.getName() : ""));
		} else {
			appendHistory(delivery,
							DeliveryStatus.SCHEDULED,
							actor.name(),
							actor.role(),
							"DELIVERY_REASSIGNED",
							Map.of("previousStatus", previousStatus.name(),
								   "previousDriver", previousDriverName,
								   "targetDriver", targetDriverName,
								   "reason", request.getNote() != null ? request.getNote() : ""));
		}

		boolean handoffNeeded = wasInField && previousDriverId != null
				&& !previousDriverId.equals(request.getDriverId());
		if (targetRouteStatus != RouteStatus.DRAFT) {
			if (previousStatus == DeliveryStatus.UNSCHEDULED) {
				eventPublisher.publishDeliveryScheduled(delivery.getOrder(), delivery, request.getDriverId());
			} else if (handoffNeeded) {
				// In-field parcel changing hands: admin sees the reassignment, but the
				// drivers get accurate handoff prompts (not "new delivery"/"removed").
				eventPublisher.publishDeliveryReassigned(delivery.getOrder(), delivery, previousDriverId, request.getDriverId(), false);
				handoffService.request(delivery.getId(), previousDriverId, request.getDriverId(), principal, request.getNote());
			} else {
				eventPublisher.publishDeliveryReassigned(delivery.getOrder(), delivery, previousDriverId, request.getDriverId());
			}
		}

		return affectedRouteIds;
	}

        @Transactional
        public AdminOpsExceptionsResponse.ExceptionItem replanException(UUID deliveryId,
                                                                                                                        AdminExceptionReplanRequest request,
                                                                                                                        UserPrincipal principal) {
                Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                                .orElseThrow(() -> AppException.notFound("Delivery not found"));

                assertReplanAllowed(delivery);

                DeliveryStatus previousStatus = delivery.getStatus();
                UUID previousDriverId = delivery.getDriverId();
                if (previousDriverId != null) {
                        auditLogService.logAction(principal, "DELIVERY_REMOVED_FROM_ROUTE", "DELIVERY", delivery.getId().toString(),
                                Map.of("action", "removed_by_replan", "driverId", previousDriverId.toString()));
                }

                delivery.setDriverId(null);
                delivery.setStatus(DeliveryStatus.UNSCHEDULED);
                delivery.setAssignedAt(null);
                delivery.setPickedUpAt(null);
                delivery.setInTransitAt(null);
                delivery.setCompletedAt(null);
                delivery.setWaitingSlaMinutes(null);
                delivery.setAssignSlaMinutes(null);
                delivery.setPickupSlaMinutes(null);
                delivery.setCancelledAt(null);
                delivery.setCancelReason(null);
                delivery.setCancelledBy(null);
                // failedAt and failureCode are kept for historical reporting

                // New commitment: SLAs must measure against this date, not the stale ERP one.
                boolean rescheduled = false;
                if (request.getScheduledAt() != null && delivery.getOrder() != null) {
                        delivery.getOrder().setRescheduledAt(request.getScheduledAt());
                        orderRepo.save(delivery.getOrder());
                        rescheduled = true;
                }

                deliveryRepo.save(delivery);

                // V3.3 — Push the new commitment date to the ERP so the promised date matches ASM's
                // (otherwise the two diverge after a replan). Any ERP order; routed through the outbox.
                if (rescheduled && delivery.getOrder() != null && delivery.getOrder().isFromErp()) {
                        delivery.getOrder().setOdooSyncStatus("PENDING_SYNC");
                        orderRepo.save(delivery.getOrder());
                        outboxProcessor.enqueue("ERP_SYNC_RESCHEDULE", Map.of(
                                        "deliveryId", delivery.getId().toString(),
                                        "orderId", delivery.getOrder().getId().toString(),
                                        "scheduledAt", request.getScheduledAt().toString()));
                }

                // E2 — Re-baseline the unified SLA against the NEW scheduled date and hold the planning
                // alarm for a grace window, so the replanned delivery is not re-flagged "as if newly
                // imported". Replaces the legacy in-memory clearDeliveryAlerts dedup set.
                slaStateService.refresh(delivery);
                slaStateService.applyReplanGrace(delivery.getId());

                ActorInfo actor = resolveActor(principal);
                String previousDriverName = previousDriverId != null ? previousDriverId.toString().substring(0, 8) : "UNKNOWN";
                String clientName = delivery.getOrder() != null ? delivery.getOrder().getClientName() : "UNKNOWN";

                Map<String, Object> auditDetails = Map.of(
                        "action", "replan",
                        "delivery", shortDeliveryId(delivery.getId()),
                        "client", clientName,
                        "previousDriver", previousDriverName,
                        "previousStatus", previousStatus.name(),
                        "reason", request.getNote() != null ? request.getNote() : ""
                );

                auditLogService.logAction(principal, "REPLAN_DELIVERY", "DELIVERY", delivery.getId().toString(), auditDetails);

                // Replan means pull the delivery out of the current execution route and return it to dispatch pool.
                removeStopFromCurrentRoute(delivery.getId());

                appendHistory(delivery,
                                DeliveryStatus.UNSCHEDULED,
                                actor.name(),
                                actor.role(),
                                "DELIVERY_REPLANNED",
                                Map.of("previousStatus", previousStatus.name(),
                                       "previousDriver", previousDriverName,
                                       "reason", request.getNote() != null ? request.getNote() : ""));

                eventPublisher.publishDeliveryReplanned(delivery.getOrder(), delivery, previousDriverId);

                return mapActionResult(delivery, "WARNING", "REPLANNED", "Delivery sent back to waiting lane");
        }
    // ── Confirm return-to-origin ──────────────────────────────────────────────

    @Transactional
    public void confirmReturn(UUID deliveryId, String note, UserPrincipal principal) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        if (delivery.getStatus() != DeliveryStatus.CANCELLED) {
            throw AppException.badRequest("Can only confirm return on a CANCELLED delivery");
        }

        delivery.setDriverId(null);
        // Parcel is back at depot — make available for re-dispatch unless order was cancelled
        Order order = delivery.getOrder();
        boolean orderCancelled = order != null && order.getStatus() == OrderStatus.CANCELLED;
        if (!orderCancelled) {
            delivery.setStatus(DeliveryStatus.UNSCHEDULED);
            delivery.setAssignedAt(null);
            delivery.setPickedUpAt(null);
        }
        deliveryRepo.save(delivery);

        ActorInfo actor = resolveActor(principal);
        String resolvedNote = StringUtils.hasText(note) ? note.trim() : "Return to origin confirmed by admin";
        appendHistory(delivery, delivery.getStatus(), actor.name(), actor.role(),
                "RETURN_TO_ORIGIN_CONFIRMED",
                Map.of("reason", resolvedNote));

        auditLogService.logAction(principal, "CONFIRM_RETURN", "DELIVERY", deliveryId.toString(),
                Map.of("action", "return_confirmed", "orderCancelled", String.valueOf(orderCancelled)));
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    @Transactional
    public void cancelDelivery(UUID deliveryId, String reason) {
        cancelDelivery(deliveryId, reason, true);
    }

    /**
     * @param syncToErp when false, do NOT push the cancellation back to the ERP. Used by the Odoo→ASM
     *                  inbound reconciliation: Odoo already cancelled the order, so re-syncing would be
     *                  a redundant round-trip / loop (V2 anti-loop).
     */
    @Transactional
    public void cancelDelivery(UUID deliveryId, String reason, boolean syncToErp) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        if (!List.of(DeliveryStatus.UNSCHEDULED, DeliveryStatus.SCHEDULED, DeliveryStatus.PICKED_UP).contains(delivery.getStatus())) {
            throw AppException.badRequest("Cannot cancel delivery in status " + delivery.getStatus());
        }

        Order order = delivery.getOrder();
        boolean wasPickedUp = delivery.getStatus() == DeliveryStatus.PICKED_UP;

        // Soft-cancel: preserve the delivery row and its full audit history
        delivery.setStatus(DeliveryStatus.CANCELLED);
        delivery.setCancelledAt(LocalDateTime.now());
        delivery.setCancelledBy(Role.ADMIN);
        delivery.setCancelReason(reason);
        deliveryRepo.save(delivery);

        // Remove the associated route stop (soft-delete)
        routeStopRepository.findActiveByDeliveryId(deliveryId).ifPresent(stop -> {
            Route route = stop.getRoute();
            if (route != null && (route.getStatus() == RouteStatus.VALIDATED || route.getStatus() == RouteStatus.IN_PROGRESS)) {
                stop.setStatus(RouteStopStatus.REMOVED_CANCELLED);
                stop.setRemovedAt(LocalDateTime.now());
                stop.setRemovedReason(reason != null ? reason : "CANCELLED");
                stop.setRemovedBy("ADMIN");
                routeStopRepository.save(stop);
                String clientName = order != null ? order.getClientName() : null;
                String erpOrderId = order != null ? order.getErpOrderId() : null;
                routeWebSocketService.notifyDriverStopRemoved(
                    route.getDriverId(), route.getId(), route.getName(),
                    clientName, erpOrderId, reason);
            } else if (route != null && route.getStatus() == RouteStatus.DRAFT) {
                routeStopRepository.delete(stop);
            }
        });

        appendHistory(delivery, DeliveryStatus.CANCELLED, "ADMIN", Role.ADMIN,
                "DELIVERY_CANCELLED",
                Map.of("wasPickedUp", wasPickedUp, "reason", reason != null ? reason : ""));

        eventPublisher.publishDeliveryCancelled(order, delivery, delivery.getDriverId());

        // Standardize: preserve record for audit logs and avoid FK violations with the deliveries table.
        if (order != null) {
            order.setStatus(OrderStatus.CANCELLED);
            orderRepo.save(order);
            if (syncToErp && order.isFromErp()) {
                // B4 — Carry the exact deliveryId being cancelled. An order can have several deliveries
                // (multi-depot, backorder); the processor must cancel THIS shipment's picking, not an
                // arbitrary first() one.
                outboxProcessor.enqueue("ERP_SYNC_CANCELLATION", Map.of(
                        "orderId", order.getId().toString(),
                        "deliveryId", delivery.getId().toString()));
            }
        }
    }

	/**
	 * Manual entry point. In the shipment model the backorder is created automatically when a
	 * partial delivery syncs to the ERP (ErpSyncResultConsumer -> createBackorderShipment). This
	 * returns that auto-created backorder shipment for the order, or fails clearly if none is pending.
	 */
	public AdminDeliveryDetailResponse createBackorderDelivery(UUID deliveryId) {
		Delivery source = deliveryRepo.findByIdWithOrder(deliveryId)
				.orElseThrow(() -> AppException.notFound("Delivery not found"));
		Order order = source.getOrder();
		if (order == null) throw AppException.badRequest("No order attached to this delivery");

		UUID backorderId = deliveryRepo.findAllByOrderIdWithOrder(order.getId()).stream()
				.filter(d -> d.getOdooBackorderId() != null && !d.getId().equals(deliveryId))
				.map(Delivery::getId)
				.findFirst()
				.orElseThrow(() -> AppException.badRequest(
						"No backorder pending. A backorder is created automatically once the partial delivery syncs to the ERP."));
		return dispatchService.getDeliveryDetail(backorderId);
	}

	/**
	 * Creates the backorder as a NEW shipment (delivery) under the SAME order — no cloned order,
	 * no erp_order_id workaround. The picking identity lives on the delivery. Idempotent on the
	 * Odoo backorder picking id. Called automatically from the ERP partial-sync result.
	 */
	public UUID createBackorderShipment(UUID orderId, Integer backorderPickingId, String backorderBlNumber, UUID sourceDeliveryId) {
		return self.doCreateBackorderShipment(orderId, backorderPickingId, backorderBlNumber, sourceDeliveryId);
	}

	@Transactional
	public UUID doCreateBackorderShipment(UUID orderId, Integer backorderPickingId, String backorderBlNumber, UUID sourceDeliveryId) {
		// Idempotency: the same Odoo backorder picking must map to a single shipment (retries/redeliveries).
		if (backorderPickingId != null) {
			Delivery existing = deliveryRepo.findByOdooBackorderId(backorderPickingId).orElse(null);
			if (existing != null) {
				log.info("Backorder shipment already exists for odooBackorderId={} (deliveryId={}), skipping create",
						backorderPickingId, existing.getId());
				return existing.getId();
			}
		}
		Order order = orderRepo.findById(orderId)
				.orElseThrow(() -> AppException.notFound("Order not found: " + orderId));

		// C4 — REFUSED ≠ backorder. A backorder re-delivers the *remainder*, which only makes sense
		// for lines the customer still wants but didn't get (short-shipped / out of stock). A line the
		// customer REFUSED at the door must NOT be re-delivered. If every undelivered unit on the order
		// is refused (no genuine short-ship remains), we skip the backorder entirely — even though Odoo
		// produced a backorder picking — and leave it for the dispatcher to handle as a return if needed.
		if (!hasBackorderEligibleRemainder(order)) {
			log.info("Skipping backorder for orderId={} — the undelivered remainder is entirely REFUSED (no short-ship to re-deliver)", orderId);
			return null;
		}

		// The remainder ships from the same depot; items stay on the order (remaining = ordered − delivered).
		Delivery backorder = Delivery.builder()
				.order(order)
				.blNumber(backorderBlNumber)
				.odooBackorderId(backorderPickingId)
				.sourceDepotId(order.getSourceDepotId())
				.status(DeliveryStatus.UNSCHEDULED)
				.createdAt(LocalDateTime.now())
				.build();
		backorder = deliveryRepo.save(backorder);

		appendHistory(backorder,
				DeliveryStatus.UNSCHEDULED,
				"SYSTEM",
				Role.SYSTEM,
				"BACKORDER_CREATED",
				Map.of("sourceDeliveryId", sourceDeliveryId != null ? sourceDeliveryId.toString() : "",
						"blNumber", backorderBlNumber != null ? backorderBlNumber : ""));

		eventPublisher.publishBackorderCreated(order, backorder.getId(), backorderBlNumber);
		log.info("Backorder shipment created — orderId={} backorderDeliveryId={} odooBackorderId={} bl={}",
				orderId, backorder.getId(), backorderPickingId, backorderBlNumber);
		return backorder.getId();
	}

	/**
	 * Disposition-code re-delivery (enterprise model): when a visit fails because the customer refused
	 * goods for a DEFECT (damaged / wrong item / postponed) — they still want the product, just not this
	 * unit/timing — create a NEW replacement shipment to re-deliver a good unit. The failed visit stays
	 * FAILED (the attempt truly didn't deliver); this is the "reship" task that follows it.
	 *
	 * <p>Unlike a backorder, this is ASM-driven (no Odoo backorder picking) — the goods were physically
	 * brought back, so no new ERP stock move is implied here. Idempotent: skips if a pending replacement
	 * already exists for the order. Returns the replacement delivery id, or null if not warranted.
	 */
	public UUID createReplacementShipment(UUID orderId, UUID sourceDeliveryId) {
		return self.doCreateReplacementShipment(orderId, sourceDeliveryId);
	}

	@Transactional
	public UUID doCreateReplacementShipment(UUID orderId, UUID sourceDeliveryId) {
		Order order = orderRepo.findById(orderId).orElse(null);
		if (order == null) return null;

		// Only warranted when at least one line was explicitly REFUSED for a DEFECT (damaged / wrong
		// item / postponed) — the customer wants a good unit re-delivered. A plain failure (client
		// absent, no item outcomes) or an outright refusal must NOT spawn a replacement.
		if (!hasRefusedDefectLine(order)) {
			return null;
		}
		// Idempotency: don't stack replacement shipments — one open UNSCHEDULED sibling is enough.
		boolean alreadyPending = deliveryRepo.findAllByOrderIdWithOrder(orderId).stream()
				.anyMatch(d -> d.getStatus() == DeliveryStatus.UNSCHEDULED && !d.getId().equals(sourceDeliveryId));
		if (alreadyPending) {
			log.info("Replacement shipment skipped — a pending delivery already exists for orderId={}", orderId);
			return null;
		}

		Delivery replacement = Delivery.builder()
				.order(order)
				.sourceDepotId(order.getSourceDepotId())
				.status(DeliveryStatus.UNSCHEDULED)
				.createdAt(LocalDateTime.now())
				.build();
		replacement = deliveryRepo.save(replacement);

		appendHistory(replacement, DeliveryStatus.UNSCHEDULED, "SYSTEM", Role.SYSTEM,
				"REPLACEMENT_CREATED",
				Map.of("sourceDeliveryId", sourceDeliveryId != null ? sourceDeliveryId.toString() : "",
						"reason", "REFUSED_DEFECT"));

		// Single, clear notification for the re-delivery (not a failed+backorder pair).
		eventPublisher.publishRedeliveryScheduled(order, replacement.getId());
		log.info("Replacement shipment created (refused-defect re-delivery) — orderId={} replacementDeliveryId={}",
				orderId, replacement.getId());
		return replacement.getId();
	}

	/**
	 * Reasons under which a REFUSED line still warrants a re-delivery: the customer wants the product,
	 * just not THIS unit/timing (damaged, wrong item/size, postponed). A pure rejection ("don't want it
	 * anymore", i.e. CLIENT_REJECTED or no reason) is NOT re-delivered.
	 */
	private static final java.util.Set<String> REDELIVERABLE_REFUSAL_REASONS =
			java.util.Set.of("DAMAGED", "WRONG_ITEM", "POSTPONED");

	/**
	 * True if at least one line was explicitly REFUSED for a re-deliverable defect reason. Stricter than
	 * {@link #hasBackorderEligibleRemainder} (which also accepts plain short-ships): used on the FAILED
	 * path, where a client-absent failure has undelivered lines with no REFUSED outcome and must NOT
	 * trigger a replacement.
	 */
	private boolean hasRefusedDefectLine(Order order) {
		if (order.getItems() == null) return false;
		for (OrderItem item : order.getItems()) {
			if (item == null) continue;
			boolean refused = "REFUSED".equalsIgnoreCase(item.getOutcome());
			boolean defect = item.getReason() != null
					&& REDELIVERABLE_REFUSAL_REASONS.contains(item.getReason().toUpperCase());
			if (refused && defect) return true;
		}
		return false;
	}

	/**
	 * V1.4 / C4 — Returns true if the order has at least one undelivered line that should be re-delivered
	 * (a backorder is warranted). A line qualifies when {@code quantityDone < quantity} AND it is not a
	 * pure refusal:
	 * <ul>
	 *   <li>short-ship / out-of-stock (not refused)        → re-deliver;</li>
	 *   <li>refused for a defect/timing (DAMAGED/WRONG_ITEM/POSTPONED) → re-deliver a good unit;</li>
	 *   <li>refused outright (CLIENT_REJECTED or no reason) → do NOT re-deliver.</li>
	 * </ul>
	 * If no line qualifies, no backorder is created (even if Odoo produced a backorder picking).
	 */
	static boolean hasBackorderEligibleRemainder(Order order) {
		if (order.getItems() == null || order.getItems().isEmpty()) {
			// No line detail to reason about → keep the legacy behavior (allow the backorder).
			return true;
		}
		for (OrderItem item : order.getItems()) {
			if (item == null) continue;
			int planned = item.getQuantity() != null ? item.getQuantity() : 0;
			int done = item.getQuantityDone() != null ? item.getQuantityDone() : 0;
			if (done >= planned) continue; // fully delivered → nothing to re-deliver

			boolean refused = "REFUSED".equalsIgnoreCase(item.getOutcome());
			boolean wantsReplacement = item.getReason() != null
					&& REDELIVERABLE_REFUSAL_REASONS.contains(item.getReason().toUpperCase());
			// Re-deliver unless it is a pure refusal (refused with no replacement-worthy reason).
			if (!refused || wantsReplacement) {
				return true;
			}
		}
		return false;
	}
    private Map<UUID, RouteInfo> loadRouteInfoMap(List<Delivery> deliveries) {
        List<UUID> deliveryIds = deliveries.stream()
                .map(Delivery::getId)
                .filter(Objects::nonNull)
                .toList();

        if (deliveryIds.isEmpty()) {
            return Map.of();
        }

        return routeStopRepository.findAllByDeliveryIdInWithRoute(deliveryIds).stream()
                .filter(routeStop -> routeStop.getRoute() != null)
                .collect(Collectors.toMap(
                        com.asm.delivery.entity.RouteStop::getDeliveryId,
                        routeStop -> new RouteInfo(routeStop.getRoute().getId(), routeStop.getRoute().getName()),
                        (existing, replacement) -> existing
                ));
    }
        private record RouteInfo(UUID routeId, String routeName) {}

        private AdminOpsExceptionsResponse.ExceptionItem mapActionResult(Delivery delivery,
                                                                                                                                                  String severity,
                                                                                                                                                  String motif,
                                                                                                                                                  String comment) {
                Order order = delivery.getOrder();
                // DELETED: Blocking transportPort.getDriver() inside @Transactional.
                RouteInfo routeInfo = loadRouteInfoMap(List.of(delivery)).get(delivery.getId());
                String zoneName = null;
                if (order != null && order.getZoneId() != null) {
                        zoneName = zoneRepository.findById(order.getZoneId()).map(Zone::getName).orElse(null);
                }
                return AdminOpsExceptionsResponse.ExceptionItem.builder()
                                .deliveryId(delivery.getId())
                                .orderId(order != null ? order.getId() : null)
                                .routeId(routeInfo != null ? routeInfo.routeId() : null)
                                .routeName(routeInfo != null ? routeInfo.routeName() : null)
                                .status(delivery.getStatus())
                                .failureCode(delivery.getFailureCode() != null ? delivery.getFailureCode().name() : null)
                                .motif(motif)
                                .driverId(delivery.getDriverId())
                                .driverName(delivery.getDriverId() != null ? delivery.getDriverId().toString().substring(0, 8) : null)
                                .clientName(order != null ? order.getClientName() : null)
                                .city(order != null ? order.getDropoffCity() : null)
                                .zoneName(zoneName)
                                .severity(severity)
                                .comment(comment)
                                .createdAt(delivery.getCreatedAt())
                                .updatedAt(delivery.getUpdatedAt())
                                .build();
        }
        private void appendHistory(Delivery delivery, DeliveryStatus status, String changedBy, Role role, String eventKey, Map<String, Object> params) {
                String jsonParams = "{}";
                try {
                        jsonParams = objectMapper.writeValueAsString(params != null ? params : Map.of());
                } catch (Exception ignored) {}
                
                historyRepo.save(DeliveryStatusHistory.builder()
                                .deliveryId(delivery.getId())
                                .status(status)
                                .changedBy(changedBy)
                                .changedByRole(role)
                                .eventKey(eventKey)
                                .eventParams(jsonParams)
                                .build());
        }
        private ActorInfo resolveActor(UserPrincipal principal) {
                if (principal == null) {
                        return new ActorInfo("SYSTEM", Role.SYSTEM);
                }

                String actorName = StringUtils.hasText(principal.getDisplayName())
                                ? principal.getDisplayName().trim()
                                : (StringUtils.hasText(principal.getUserId()) ? principal.getUserId().trim() : "SYSTEM");

                Role role;
                try {
                        role = StringUtils.hasText(principal.getRole())
                                        ? Role.valueOf(principal.getRole().trim().toUpperCase(Locale.ROOT))
                                        : Role.ADMIN;
                } catch (IllegalArgumentException ex) {
                        role = Role.ADMIN;
                }
                return new ActorInfo(actorName, role);
        }


        private String shortDeliveryId(UUID deliveryId) {
                if (deliveryId == null) {
                        return "UNKNOWN";
                }
                String raw = deliveryId.toString();
                return raw.length() <= 8 ? raw : raw.substring(0, 8);
        }
        private void assertPinned(Delivery delivery) {
            Order order = delivery.getOrder();
            if (order == null || order.getDropoffLat() == null || order.getDropoffLng() == null) {
                String ref = order != null && org.springframework.util.StringUtils.hasText(order.getErpOrderId())
                        ? order.getErpOrderId() : delivery.getId().toString().substring(0, 8);
                throw AppException.badRequest(
                    "ORDER_NOT_PINNED",
                    "Order " + ref + " is not pinned. Please pin the address on the map before adding to route draft.",
                    Map.of("reference", ref)
                );
            }
        }

        private void assertReassignAllowed(Delivery delivery) {
                if (!REASSIGN_ALLOWED_STATUSES.contains(delivery.getStatus())) {
                        throw AppException.badRequest("Reassign is allowed only for UNSCHEDULED, SCHEDULED, PICKED_UP, or IN_TRANSIT deliveries");
                }
        }

        private void assertReplanAllowed(Delivery delivery) {
                if (!REPLAN_ALLOWED_STATUSES.contains(delivery.getStatus())) {
                        throw AppException.badRequest("Replan is allowed only for SCHEDULED, FAILED, or PARTIALLY_DELIVERED deliveries");
                }
        }

        private void removeStopFromCurrentRoute(UUID deliveryId) {
                routeStopRepository.findActiveByDeliveryIdWithRoute(deliveryId).ifPresent(stop -> {
                        Route route = stop.getRoute();
                        if (route == null) return;
                        
                        UUID previousRouteId = route.getId();
                        if (route.getStatus() == RouteStatus.VALIDATED || route.getStatus() == RouteStatus.IN_PROGRESS) {
                                // Soft-delete on active routes — keep audit trail
                                stop.setStatus(RouteStopStatus.REMOVED_REPLANNED);
                                stop.setRemovedAt(LocalDateTime.now());
                                stop.setRemovedReason("REPLANNED");
                                stop.setRemovedBy("ADMIN");
                                routeStopRepository.save(stop);
                                
                                // Notify driver in real-time
                                routeWebSocketService.notifyDriver(route.getDriverId(), "STOP_REMOVED", route.getId(), route.getName());
                        } else {
                                // Draft routes — hard delete
                                routeStopRepository.delete(stop);
                        }
                        repackStopOrder(previousRouteId);
                });
        }

        private Set<UUID> moveStopToDriverRoute(Delivery delivery, UUID targetDriverId, UUID targetRouteId, Integer insertAtOrder, String actorName, java.time.LocalTime requestedStartTime, java.time.LocalTime requestedEndTime) {
                Set<UUID> affectedRouteIds = new LinkedHashSet<>();
                Optional<RouteStop> currentStopOpt = routeStopRepository.findActiveByDeliveryIdWithRoute(delivery.getId());
                Route sourceRoute = null;

                if (currentStopOpt.isPresent()) {
                        RouteStop currentStop = currentStopOpt.get();
                        sourceRoute = currentStop.getRoute();
                        
                        if (sourceRoute != null) {
                            if (targetDriverId.equals(sourceRoute.getDriverId()) && (targetRouteId == null || targetRouteId.equals(sourceRoute.getId()))) {
                                    return affectedRouteIds; // Already exactly where it needs to be
                            }

                            UUID sourceRouteId = sourceRoute.getId();
                            affectedRouteIds.add(sourceRouteId);
                            routeStopRepository.delete(currentStop);
                            repackStopOrder(sourceRouteId);
                        }
                }

                Route targetRoute;
                if (targetRouteId != null) {
                        targetRoute = routeRepository.findById(targetRouteId)
                                .orElseThrow(() -> AppException.notFound("Target route not found"));
                        if (!targetDriverId.equals(targetRoute.getDriverId())) {
                                throw AppException.badRequest("Target route does not belong to the selected driver");
                        }
                } else {
                        targetRoute = findOrCreateRouteForDriver(targetDriverId, sourceRoute, delivery, actorName);
                }

                affectedRouteIds.add(targetRoute.getId());
                List<RouteStop> targetStops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(targetRoute.getId());
                
                // Determine final windows
                java.time.LocalTime finalStartTime = requestedStartTime != null ? requestedStartTime : (currentStopOpt.isPresent() ? currentStopOpt.get().getStartTimeWindow() : null);
                java.time.LocalTime finalEndTime = requestedEndTime != null ? requestedEndTime : (currentStopOpt.isPresent() ? currentStopOpt.get().getEndTimeWindow() : null);

                // Chronological check only for DRAFT routes — active routes accept stops without strict ordering
                if (finalStartTime != null && !targetStops.isEmpty() && targetRoute.getStatus() == RouteStatus.DRAFT) {
                    int pos = (insertAtOrder != null) ? insertAtOrder : targetStops.size() + 1;
                    
                    // Check against previous stop (if any)
                    if (pos > 1) {
                        RouteStop prev = targetStops.get(pos - 2);
                        java.time.LocalTime prevRef = prev.getEndTimeWindow() != null ? prev.getEndTimeWindow() 
                                          : (prev.getEtaAt() != null ? prev.getEtaAt().toLocalTime() : targetRoute.getPlannedStartTime());
                        if (prevRef != null && finalStartTime.isBefore(prevRef)) {
                            throw AppException.badRequest(
                                "ROUTE_TIME_CONFLICT",
                                "Time conflict: Previous stop ends at " + prevRef + ". Requested start time (" + finalStartTime + ") is invalid.",
                                Map.of("limitTime", prevRef.toString(), "requestedTime", finalStartTime.toString(), "conflictType", "PREVIOUS")
                            );
                        }
                    }
                    
                    // Check against next stop (if any)
                    if (pos <= targetStops.size()) {
                        RouteStop next = targetStops.get(pos - 1);
                        java.time.LocalTime nextRef = next.getStartTimeWindow() != null ? next.getStartTimeWindow() 
                                          : (next.getEtaAt() != null ? next.getEtaAt().toLocalTime() : targetRoute.getPlannedEndTime());
                        if (nextRef != null && finalEndTime != null && finalEndTime.isAfter(nextRef)) {
                            throw AppException.badRequest(
                                "ROUTE_TIME_CONFLICT",
                                "Time conflict: Next stop starts at " + nextRef + ". Requested end time (" + finalEndTime + ") is invalid.",
                                Map.of("limitTime", nextRef.toString(), "requestedTime", finalEndTime.toString(), "conflictType", "NEXT")
                            );
                        }
                    }
                }

                // Extend Route boundaries if the newly placed window is outside Current bounds
                boolean routeModified = false;
                if (finalStartTime != null && targetRoute.getPlannedStartTime() != null && finalStartTime.isBefore(targetRoute.getPlannedStartTime())) {
                        targetRoute.setPlannedStartTime(finalStartTime);
                        routeModified = true;
                }
                if (finalEndTime != null && targetRoute.getPlannedEndTime() != null && finalEndTime.isAfter(targetRoute.getPlannedEndTime())) {
                        targetRoute.setPlannedEndTime(finalEndTime);
                        routeModified = true;
                }
                if (routeModified) {
                        routeRepository.save(targetRoute);
                }

                int nextOrder;
                if (insertAtOrder != null && insertAtOrder >= 1 && insertAtOrder <= targetStops.size() + 1) {
                        nextOrder = insertAtOrder;
                        // Shift subsequent stops down
                        for (RouteStop s : targetStops) {
                                if (s.getStopOrder() >= nextOrder) {
                                        s.setStopOrder(s.getStopOrder() + 1);
                                        routeStopRepository.save(s);
                                }
                        }
                } else {
                        nextOrder = targetStops.size() + 1;
                }

                routeStopRepository.save(RouteStop.builder()
                                .route(targetRoute)
                                .deliveryId(delivery.getId())
                                .stopOrder(nextOrder)
                                .status(RouteStopStatus.PENDING)
                                .notes(currentStopOpt.isPresent() ? "Moved by dispatch via reassign" : "Assigned by dispatch from pool")
                                .startTimeWindow(finalStartTime)
                                .endTimeWindow(finalEndTime)
                                .bufferMinutes(currentStopOpt.isPresent() && currentStopOpt.get().getBufferMinutes() != null ? currentStopOpt.get().getBufferMinutes() : 10)
                                .build());

                return affectedRouteIds;
        }

        private Route findOrCreateRouteForDriver(UUID driverId, Route sourceRoute, Delivery delivery, String actorName) {
                LocalDate date = sourceRoute != null ? sourceRoute.getDate() : LocalDate.now();
                List<RouteStatus> activeStatuses = List.of(RouteStatus.DRAFT, RouteStatus.VALIDATED, RouteStatus.IN_PROGRESS);
                List<Route> routes = routeRepository.findByDriverIdAndDateAndStatusIn(driverId, date, activeStatuses);
                if (!routes.isEmpty()) {
                        return routes.get(0);
                }

                String creator = StringUtils.hasText(actorName) ? actorName : "SYSTEM";
                RouteStatus inheritedStatus = (sourceRoute != null && (sourceRoute.getStatus() == RouteStatus.VALIDATED
                        || sourceRoute.getStatus() == RouteStatus.IN_PROGRESS))
                        ? RouteStatus.VALIDATED : RouteStatus.DRAFT;
                
                String city = sourceRoute != null ? sourceRoute.getCity() : (delivery.getOrder() != null ? delivery.getOrder().getDropoffCity() : null);

                Route draftRoute = Route.builder()
                                .name("Dispatch route " + date + " " + driverId.toString().substring(0, 8))
                                .driverId(driverId)
                                .vehicleId(null)
                                .date(date)
                                .plannedStartTime(sourceRoute != null && sourceRoute.getPlannedStartTime() != null ? sourceRoute.getPlannedStartTime() : LocalTime.of(8, 0))
                                .plannedEndTime(sourceRoute != null && sourceRoute.getPlannedEndTime() != null ? sourceRoute.getPlannedEndTime() : LocalTime.of(18, 0))
                                .depotId(sourceRoute != null ? sourceRoute.getDepotId() : null)
                                .city(city)
                                .status(inheritedStatus)
                                .createdBy(creator)
                                .build();
                return routeRepository.save(draftRoute);
        }

        private void repackStopOrder(UUID routeId) {
                List<RouteStop> activeStops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId)
                        .stream()
                        .filter(s -> s.getStatus() != RouteStopStatus.REMOVED_REPLANNED
                                  && s.getStatus() != RouteStopStatus.REMOVED_CANCELLED)
                        .toList();
                for (int i = 0; i < activeStops.size(); i++) {
                        activeStops.get(i).setStopOrder(i + 1);
                }
                if (!activeStops.isEmpty()) {
                        routeStopRepository.saveAll(activeStops);
                }
        }
        private record ActorInfo(String name, Role role) {}


}
