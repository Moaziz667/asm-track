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
    private final com.asm.delivery.service.route.PickupStopReconciler pickupStopReconciler;
    private final com.asm.delivery.service.HandoffService handoffService;
    private final com.asm.delivery.sla.SlaStateService slaStateService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private ExceptionResolutionService self;

    @org.springframework.beans.factory.annotation.Autowired
    public void setSelf(@org.springframework.context.annotation.Lazy ExceptionResolutionService self) {
        this.self = self;
    }

	/**
	 * Declare an exception handled, for the ones the platform cannot close by itself.
	 *
	 * <p>Almost every exception retires on its own — a partial whose reliquat has been re-imported, a
	 * failure that has been re-attempted. What is left is what nothing can deduce: a customer who
	 * cancelled by telephone, refused the remainder for good, an address that does not exist. Those
	 * used to sit on the desk for good, and a count that only climbs is a count nobody reads.
	 *
	 * <p>Only on a finished attempt. On anything still in the field the exception <em>is</em> the
	 * work — an unplanned order gets planned, a late driver gets called — and offering to close it
	 * would be offering to hide the job instead of doing it.
	 */
	@Transactional
	public AdminOpsExceptionsResponse.ExceptionItem acknowledgeException(UUID deliveryId, UserPrincipal principal) {
		Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
				.orElseThrow(() -> AppException.notFound("Delivery not found"));

		DeliveryStatus status = delivery.getStatus();
		if (status != DeliveryStatus.FAILED && status != DeliveryStatus.PARTIALLY_DELIVERED) {
			throw AppException.badRequest(
					"Seule une livraison en echec ou partielle peut etre marquee comme traitee.");
		}

		delivery.setOpsAcknowledgedAt(LocalDateTime.now());
		delivery.setOpsAcknowledgedBy(principal != null && principal.getUserId() != null
				? UUID.fromString(principal.getUserId()) : null);
		deliveryRepo.save(delivery);

		// Logged even though the acknowledgement carries no reason: who and when is what an audit
		// needs to reconstruct a desk that went quiet.
		try {
			auditLogService.logAction(principal, "OPS_EXCEPTION_ACKNOWLEDGED", "DELIVERY",
					deliveryId.toString(), java.util.Map.of("status", status.name()));
		} catch (Exception e) {
			log.warn("OPS_ACK_AUDIT_FAILED deliveryId={} reason={}", deliveryId, e.getMessage());
		}

		log.info("OPS_EXCEPTION_ACKNOWLEDGED deliveryId={} status={} by={}",
				deliveryId, status, principal != null ? principal.getUserId() : null);
		return mapActionResult(delivery, "INFO", "ACKNOWLEDGED", "Exception marked as handled");
	}

	public AdminOpsExceptionsResponse.ExceptionItem reassignException(UUID deliveryId,
																							  AdminExceptionReassignRequest request,
																							  UserPrincipal principal) {
		// Resolve driver display names UP FRONT — the remote driver-service lookup must not
		// run inside doReassign's @Transactional (it would hold a DB connection across a
		// network call). We snapshot the names here so the audit/history reads
		// "Bilel Driver → Mohamed Driver" instead of raw UUID fragments.
		UUID currentDriverId = deliveryRepo.findById(deliveryId).map(Delivery::getDriverId).orElse(null);
		String fromDriverName = resolveDriverName(currentDriverId);
		String toDriverName = resolveDriverName(request.getDriverId());

		// 1. Transactional Mutation
		Set<UUID> affectedRouteIds = self.doReassign(deliveryId, request, principal, fromDriverName, toDriverName);

		// 2. Post-Transaction Optimization (Outside DB Lock).
		// Best-effort: the reassign is already committed, and ETA/geometry recompute is advisory (a
		// depot-less draft, OSRM hiccup, etc. must not turn a successful reassign into a 400). Honest
		// state — the stop IS placed; we just log if the cosmetic recalc couldn't run.
		for (UUID routeId : affectedRouteIds) {
			if (routeId == null) continue;
			try {
				routeOptimizationService.recalculate(routeId);
			} catch (Exception e) {
				log.warn("Post-reassign recalc skipped for route {} (reassign still committed): {}", routeId, e.getMessage());
			}
		}

		// 3. Return final state — must use join-fetch variant so Order proxy is
		//    initialized before mapActionResult() accesses order.getZoneId() outside a session.
		Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
				.orElseThrow(() -> AppException.notFound("Delivery not found"));
		return mapActionResult(delivery, "WARNING", "REASSIGNED", "Delivery reassigned successfully");
	}

	/** Best-effort driver display name. Returns null on any failure so callers fall back
	 *  to a short UUID — never blocks or throws (used for audit/history labels only). */
	private String resolveDriverName(UUID driverId) {
		if (driverId == null) return null;
		try {
			var d = transportPort.getDriver(driverId.toString());
			return d != null ? d.getName() : null;
		} catch (Exception e) {
			return null;
		}
	}

	@Transactional
	public Set<UUID> doReassign(UUID deliveryId,
								AdminExceptionReassignRequest request,
								UserPrincipal principal,
								String fromDriverName,
								String toDriverName) {
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

		// An in-field parcel (already picked up) changes hands by handoff, so it never rewinds to
		// SCHEDULED — it stays AWAITING_HANDOFF until the receiver confirms (or expiry reverts it).
		// Otherwise: DRAFT target → UNSCHEDULED (Draft Planning); VALIDATED/IN_PROGRESS → SCHEDULED.
		if (wasInField) {
			delivery.setStatus(DeliveryStatus.AWAITING_HANDOFF);
		} else if (targetRouteStatus == RouteStatus.DRAFT) {
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
		// Prefer the resolved names (snapshotted before the transaction); fall back to a
		// short UUID fragment only if the driver-service lookup failed.
		String previousDriverName = (fromDriverName != null && !fromDriverName.isBlank())
				? fromDriverName
				: (previousDriverId != null ? previousDriverId.toString().substring(0, 8) : "UNKNOWN");
		String targetDriverName = (toDriverName != null && !toDriverName.isBlank())
				? toDriverName
				: request.getDriverId().toString().substring(0, 8);
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

		// Capacity guard: now that the stop sits on the target route, reject the move if it
		// overloads the vehicle — unless the dispatcher explicitly forced it. validateCapacity
		// is a no-op for DRAFT routes / no vehicle, so plain planning is unaffected.
		if (targetRoute != null) {
			dispatchService.validateCapacity(targetRoute, request.isAcknowledgeOverload());
		}

		// Multi-depot integrity: reconcile PICKUP stops on every route the move touched — drop a depot
		// load the SOURCE no longer needs, add the load the TARGET now needs. In this transaction so a
		// broken plan can never commit; per-route status dispatch handles a live source + draft target
		// in one pass. A handed-off in-field parcel keeps pickedUpAt, so it never pulls a spurious pickup.
		for (UUID affectedRouteId : affectedRouteIds) {
			if (affectedRouteId == null) continue;
			routeRepository.findById(affectedRouteId).ifPresent(pickupStopReconciler::reconcile);
		}

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
		} else if (previousDriverId == null) {
			// Direct assignment (no prior driver) — not a reassignment. Emit a clean
			// "assigned to X" event instead of "reassigned UNKNOWN → X".
			appendHistory(delivery,
							DeliveryStatus.SCHEDULED,
							actor.name(),
							actor.role(),
							"DELIVERY_ASSIGNED",
							Map.of("targetDriver", targetDriverName,
								   "reason", request.getNote() != null ? request.getNote() : ""));
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
                        delivery.getOrder().setErpSyncStatus("PENDING_SYNC");
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
                // Capture the source route BEFORE the stop leaves it so we can drop a now-orphaned depot load
                // (a SCHEDULED remote-depot delivery being replanned may be the last one needing its pickup).
                UUID replanSourceRouteId = routeStopRepository.findActiveByDeliveryIdWithRoute(delivery.getId())
                                .map(s -> s.getRoute() != null ? s.getRoute().getId() : null).orElse(null);
                removeStopFromCurrentRoute(delivery.getId());
                if (replanSourceRouteId != null) {
                        routeRepository.findById(replanSourceRouteId).ifPresent(pickupStopReconciler::reconcile);
                }

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

        // Remove the associated route stop (soft-delete). Capture the route so its PICKUP stops can be
        // reconciled after — cancelling the last delivery from a remote depot orphans that depot's load.
        UUID cancelRouteId = routeStopRepository.findActiveByDeliveryId(deliveryId).map(stop -> {
            Route route = stop.getRoute();
            if (route != null && (route.getStatus() == RouteStatus.VALIDATED || route.getStatus() == RouteStatus.IN_PROGRESS)) {
                stop.setStatus(RouteStopStatus.REMOVED_CANCELLED);
                stop.setRemovedAt(LocalDateTime.now());
                stop.setRemovedReason(reason != null ? reason : "CANCELLED");
                stop.setRemovedBy(com.asm.delivery.web.ActorContext.changedBy());
                routeStopRepository.save(stop);
                String clientName = order != null ? order.getClientName() : null;
                String erpOrderId = order != null ? order.getErpOrderId() : null;
                routeWebSocketService.notifyDriverStopRemoved(
                    route.getDriverId(), route.getId(), route.getName(),
                    clientName, erpOrderId, reason);
                return route.getId();
            } else if (route != null && route.getStatus() == RouteStatus.DRAFT) {
                routeStopRepository.delete(stop);
                return route.getId();
            }
            return null;
        }).orElse(null);
        if (cancelRouteId != null) {
            routeRepository.findById(cancelRouteId).ifPresent(pickupStopReconciler::reconcile);
        }

        appendHistory(delivery, DeliveryStatus.CANCELLED, com.asm.delivery.web.ActorContext.changedBy(), com.asm.delivery.web.ActorContext.role(),
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
                                stop.setRemovedBy(com.asm.delivery.web.ActorContext.changedBy());
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
                            // Soft-delete on active source routes so the closure report can trace the
                            // reassign (keeps a REMOVED_REPLANNED tombstone). Hard-delete only on DRAFT —
                            // pure planning has no history. Mirrors removeStopFromCurrentRoute (replan path).
                            if (sourceRoute.getStatus() == RouteStatus.VALIDATED || sourceRoute.getStatus() == RouteStatus.IN_PROGRESS) {
                                // Reassign (moved to another driver) — distinct from replan (back to pool).
                                // Status stays REMOVED_REPLANNED (the tombstone enum); the reason records
                                // the real intent so the report/PDF/audit read "réassigné", not "replanifié".
                                currentStop.setStatus(RouteStopStatus.REMOVED_REPLANNED);
                                currentStop.setRemovedAt(LocalDateTime.now());
                                currentStop.setRemovedReason("REASSIGNED");
                                currentStop.setRemovedBy(com.asm.delivery.web.ActorContext.changedBy());
                                routeStopRepository.save(currentStop);
                            } else {
                                routeStopRepository.delete(currentStop);
                            }
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
                List<RouteStop> allStops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(targetRoute.getId());
                // Real sequence only — soft-deleted stops are audit rows, not positions.
                List<RouteStop> realStops = allStops.stream()
                        .filter(s -> !REMOVED_STOP_STATUSES.contains(s.getStatus()))
                        .toList();

                // Determine final windows (requested → else inherit the source stop's window).
                java.time.LocalTime finalStartTime = requestedStartTime != null ? requestedStartTime : (currentStopOpt.isPresent() ? currentStopOpt.get().getStartTimeWindow() : null);
                java.time.LocalTime finalEndTime = requestedEndTime != null ? requestedEndTime : (currentStopOpt.isPresent() ? currentStopOpt.get().getEndTimeWindow() : null);

                // An inverted window (end before start) is nonsensical and corrupts the SLA/report/insertion
                // math. Guard it here — the single choke point for BOTH the one-click and the configurable
                // (insertAtOrder) paths — so no client can persist it (the drawer mirrors this check).
                if (finalStartTime != null && finalEndTime != null && finalStartTime.isAfter(finalEndTime)) {
                        throw AppException.badRequest("TIME_WINDOW_INVALID",
                                "Fenêtre invalide : la fin (" + finalEndTime + ") précède le début (" + finalStartTime + ").",
                                Map.of("start", finalStartTime.toString(), "end", finalEndTime.toString()));
                }

                // Decide where the stop lands — manual start/end windows only, no ETA (ADR-028).
                int targetOrder;
                if (insertAtOrder != null) {
                        // Configurable path: the dispatcher chose the position. Enforce only the immovable
                        // floor — a stop can never precede an already-completed one (livré/partiel/échec).
                        // Window overlaps are allowed here: a deliberate human choice, and the SLA still
                        // flags any resulting retard.
                        int floorOrder = 1;
                        for (RouteStop s : realStops) {
                                if (COMPLETED_STOP_STATUSES.contains(s.getStatus())) floorOrder = s.getStopOrder() + 1;
                        }
                        if (insertAtOrder < floorOrder) {
                                throw AppException.badRequest("INSERT_BEFORE_COMPLETED",
                                        "Cannot insert before an already-completed stop — earliest allowed position is " + floorOrder + ".",
                                        Map.of("minOrder", String.valueOf(floorOrder), "requestedOrder", String.valueOf(insertAtOrder), "conflictType", "PAST"));
                        }
                        targetOrder = insertAtOrder;
                } else {
                        // One-click path: auto-place. Any conflict (overlap or a window in the frozen past)
                        // means we refuse so the UI can escalate to the configurable editor — we never
                        // silently append a doomed stop at the end.
                        InsertionPlan plan = resolveInsertion(realStops, finalStartTime, finalEndTime);
                        if (plan.hasConflict()) {
                                throw AppException.badRequest(plan.conflictCode(), plan.conflictMessage(), plan.conflictDetails());
                        }
                        targetOrder = plan.index() < realStops.size()
                                ? realStops.get(plan.index()).getStopOrder()
                                : (realStops.isEmpty() ? 1 : realStops.get(realStops.size() - 1).getStopOrder() + 1);
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

                // Shift real stops at/after the target order down to make room (no-op when appending).
                for (RouteStop s : realStops) {
                        if (s.getStopOrder() >= targetOrder) {
                                s.setStopOrder(s.getStopOrder() + 1);
                                routeStopRepository.save(s);
                        }
                }

                int nextOrder = targetOrder;

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

        // Stops that represent a physical visit / recorded outcome — the route's frozen past
        // (livré / partiel / échec). A new stop can never be inserted before one of these.
        private static final Set<RouteStopStatus> COMPLETED_STOP_STATUSES = Set.of(
                RouteStopStatus.COMPLETED, RouteStopStatus.PARTIAL,
                RouteStopStatus.FAILED, RouteStopStatus.FAILED_ATTEMPT);

        // Soft-deleted stops — audit rows, not part of the live sequence.
        private static final Set<RouteStopStatus> REMOVED_STOP_STATUSES = Set.of(
                RouteStopStatus.REMOVED_REPLANNED, RouteStopStatus.REMOVED_CANCELLED);

        /** Where a stop should land. {@code index} is the 0-based insertion point into the *real* stop
         *  list (0 = front of the pending tail, size = append). When {@code conflictCode != null} the
         *  placement collides — the one-click path refuses and hands the dispatcher the configurable
         *  editor instead of silently creating a doomed stop. */
        private record InsertionPlan(int index, String conflictCode, String conflictMessage, Map<String, Object> conflictDetails) {
                boolean hasConflict() { return conflictCode != null; }
                static InsertionPlan ok(int index) { return new InsertionPlan(index, null, null, null); }
        }

        /**
         * Decide where a stop with window [{@code startW},{@code endW}] belongs in {@code realStops}
         * (already filtered of REMOVED_*), using ONLY the manual start/end windows — no ETA (ADR-028):
         *  1. <b>Floor</b>: never before the last completed stop (livré/partiel/échec) — the past is frozen.
         *  2. <b>Order</b> by start window, tie-broken by the earliest end window (EDD / Jackson's rule —
         *     the tighter deadline goes first). Stops with no start window are "anytime" and don't force a slot.
         *  3. <b>Conflict</b>: the window can only be honoured before the floor, or the chosen slot overlaps a
         *     neighbour (previous stop still open at our start, or we run past the next stop's start).
         * The SLA engine still measures every stop against its own window, so a residual overlap surfaces as
         * a retard rather than being hidden.
         */
        private InsertionPlan resolveInsertion(List<RouteStop> realStops, LocalTime startW, LocalTime endW) {
                int n = realStops.size();

                // 1. Floor — insertion index must be >= the slot right after the last completed stop.
                int floorIdx = 0;
                for (int i = 0; i < n; i++) {
                        if (COMPLETED_STOP_STATUSES.contains(realStops.get(i).getStatus())) floorIdx = i + 1;
                }

                // 2. Window-ordered index (start window, then EDD on end), clamped to the floor.
                int idx = n;
                if (startW != null) {
                        for (int i = floorIdx; i < n; i++) {
                                LocalTime s = realStops.get(i).getStartTimeWindow();
                                if (s == null) continue; // anytime — doesn't force a position
                                LocalTime e = realStops.get(i).getEndTimeWindow();
                                boolean after = s.isAfter(startW)
                                        || (s.equals(startW) && e != null && endW != null && e.isAfter(endW));
                                if (after) { idx = i; break; }
                        }
                }
                if (idx < floorIdx) idx = floorIdx;

                // 3a. Floor conflict — the window closes before the last completed stop (a window in the past).
                if (startW != null && endW != null && floorIdx > 0) {
                        RouteStop lastDone = realStops.get(floorIdx - 1);
                        LocalTime doneRef = lastDone.getEndTimeWindow() != null ? lastDone.getEndTimeWindow() : lastDone.getStartTimeWindow();
                        if (doneRef != null && endW.isBefore(doneRef)) {
                                return new InsertionPlan(idx, "INSERT_BEFORE_COMPLETED",
                                        "This delivery's window (" + startW + "–" + endW + ") closes before the last completed stop ("
                                          + doneRef + "). It can't be honoured on this route — change the window or pick another driver.",
                                        Map.of("limitTime", doneRef.toString(), "requestedTime", endW.toString(), "conflictType", "PAST"));
                        }
                }

                // 3b. Overlap with the immediate neighbours at the chosen slot.
                if (startW != null && idx - 1 >= 0 && idx - 1 < n) {
                        RouteStop prev = realStops.get(idx - 1);
                        if (prev.getEndTimeWindow() != null && startW.isBefore(prev.getEndTimeWindow())) {
                                return new InsertionPlan(idx, "ROUTE_TIME_CONFLICT",
                                        "Overlaps the previous stop, which stays open until " + prev.getEndTimeWindow() + ".",
                                        Map.of("limitTime", prev.getEndTimeWindow().toString(), "requestedTime", startW.toString(), "conflictType", "PREVIOUS"));
                        }
                }
                if (endW != null && idx >= 0 && idx < n) {
                        RouteStop next = realStops.get(idx);
                        if (next.getStartTimeWindow() != null && endW.isAfter(next.getStartTimeWindow())) {
                                return new InsertionPlan(idx, "ROUTE_TIME_CONFLICT",
                                        "Overlaps the next stop, which starts at " + next.getStartTimeWindow() + ".",
                                        Map.of("limitTime", next.getStartTimeWindow().toString(), "requestedTime", endW.toString(), "conflictType", "NEXT"));
                        }
                }

                return InsertionPlan.ok(idx);
        }

        private static final java.util.regex.Pattern SEQ_ROUTE_NAME = java.util.regex.Pattern.compile("^R(\\d+)$");

        /** Next sequential route code (R001, R002, …) — mirrors RoutePlanningService.nextRouteName so an
         *  auto-created dispatch draft gets the same clean code as a manually built route, never a raw
         *  machine label ("Dispatch route <date> <uuid8>"). */
        private String nextSequentialRouteName() {
                int max = 0;
                for (String n : routeRepository.findGeneratedRouteNames()) {
                        if (n == null) continue;
                        var mt = SEQ_ROUTE_NAME.matcher(n.trim());
                        if (mt.matches()) {
                                try { max = Math.max(max, Integer.parseInt(mt.group(1))); } catch (NumberFormatException ignored) { }
                        }
                }
                return String.format("R%03d", max + 1);
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
                                .name(nextSequentialRouteName())
                                .driverId(driverId)
                                .vehicleId(null)
                                .date(date)
                                .plannedStartTime(sourceRoute != null && sourceRoute.getPlannedStartTime() != null ? sourceRoute.getPlannedStartTime() : LocalTime.of(8, 0))
                                .plannedEndTime(sourceRoute != null && sourceRoute.getPlannedEndTime() != null ? sourceRoute.getPlannedEndTime() : LocalTime.of(18, 0))
                                // Home the fresh route at the moved parcel's OWN depot, not the source route's:
                                // a single-box brouillon for a WH2 parcel should start at WH2 (zero pickup detour),
                                // not inherit WH1 from the route it left. Falls back to the source depot when unknown.
                                .depotId(delivery.getSourceDepotId() != null ? delivery.getSourceDepotId()
                                                : (sourceRoute != null ? sourceRoute.getDepotId() : null))
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
