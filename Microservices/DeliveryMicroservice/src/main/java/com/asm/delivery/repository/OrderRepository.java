package com.asm.delivery.repository;

import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findByClientIdOrderByCreatedAtDesc(String clientId);

    List<Order> findByClientIdAndStatusNotInOrderByCreatedAtDesc(String clientId, List<OrderStatus> terminalStatuses);

    Optional<Order> findByErpOrderId(String erpOrderId);

    /** Sale-order reference (e.g. "S00110") used by ERP sync + inbound reconciliation. */
    Optional<Order> findByErpExternalRef(String erpExternalRef);

    /** All orders sharing a sale-order reference (original + backorders + multi-depot splits),
     *  oldest first — used to group shipments and pick the group root for parentOrderId linkage. */
    List<Order> findByErpExternalRefOrderByCreatedAtAsc(String erpExternalRef);

    /**
     * The most recent order created under each sale-order reference asked about.
     *
     * <p>Answers "has the remainder gone back into the pipeline" for a whole page of exceptions in
     * one query. A partial delivery stops being a dispatcher's problem the moment its reliquat has
     * been re-imported as its own order: the work moved on, and an alert still pointing at the
     * finished attempt is pointing at the past.
     *
     * <p>Returns the timestamp rather than a yes/no, because the caller has the only reference that
     * settles it — when this attempt ended. An order created after that is a follow-up; the siblings
     * of a multi-depot split were all created before the attempt even started, and comparing counts
     * would have mistaken one for the other.
     */
    @Query("""
           SELECT o.erpExternalRef, MAX(o.createdAt) FROM Order o
           WHERE o.erpExternalRef IN :refs
           GROUP BY o.erpExternalRef
           """)
    List<Object[]> latestOrderPerRef(@Param("refs") java.util.Collection<String> refs);

    boolean existsByErpOrderId(String erpOrderId);

    /** Idempotency key for per-delivery-note (bon de livraison) imports. */
    Optional<Order> findByBlNumber(String blNumber);

    boolean existsByBlNumber(String blNumber);

    List<Order> findTop100ByErpSyncStatusInOrderByUpdatedAtAsc(List<String> statuses);

    /** Count of orders in a given ERP sync state — backs the System Health ERP sync card. */
    long countByErpSyncStatus(String status);

    /** Oldest-first slice of orders in a given sync state (e.g. SYNC_FAILED) for the drill-down. */
    List<Order> findTop50ByErpSyncStatusOrderByUpdatedAtAsc(String status);

    /**
     * Find orders whose ERP sync has failed and are due for a retry attempt.
     *
     * <p>Selects orders where:
     * <ul>
     *   <li>Sync status is {@code PENDING_RETRY} or {@code PENDING_CANCEL}</li>
     *   <li>The retry backoff window has elapsed ({@code nextSyncRetryAt <= now})</li>
     *   <li>Retry count is below {@code maxRetries} (not permanently failed)</li>
     * </ul>
     */
    @Query("""
           SELECT o FROM Order o
           WHERE o.erpSyncStatus IN ('PENDING_RETRY', 'PENDING_CANCEL')
             AND (o.nextSyncRetryAt IS NULL OR o.nextSyncRetryAt <= :now)
             AND o.syncRetryCount < :maxRetries
           ORDER BY o.nextSyncRetryAt ASC NULLS FIRST
           """)
    List<Order> findOrdersPendingSync(@Param("now") LocalDateTime now,
                                      @Param("maxRetries") int maxRetries);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT o.erpOrderId FROM Order o WHERE o.erpOrderId IS NOT NULL")
    Set<String> findAllErpOrderIds();

    @Query("SELECT o.blNumber FROM Order o WHERE o.blNumber IS NOT NULL")
    Set<String> findAllBlNumbers();

    /** Orders that never got auto-located — used to re-run geocoding on demand. */
    @Query("SELECT o.id FROM Order o WHERE o.dropoffLat IS NULL OR o.dropoffLng IS NULL")
    List<UUID> findIdsMissingCoordinates();


}

