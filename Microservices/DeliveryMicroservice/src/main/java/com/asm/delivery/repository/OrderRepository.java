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

    boolean existsByErpOrderId(String erpOrderId);

    /** Idempotency key for per-delivery-note (bon de livraison) imports. */
    Optional<Order> findByBlNumber(String blNumber);

    boolean existsByBlNumber(String blNumber);

    List<Order> findTop100ByOdooSyncStatusInOrderByUpdatedAtAsc(List<String> statuses);

    /** Count of orders in a given ERP sync state — backs the System Health ERP sync card. */
    long countByOdooSyncStatus(String status);

    /** Oldest-first slice of orders in a given sync state (e.g. SYNC_FAILED) for the drill-down. */
    List<Order> findTop50ByOdooSyncStatusOrderByUpdatedAtAsc(String status);

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
           WHERE o.odooSyncStatus IN ('PENDING_RETRY', 'PENDING_CANCEL')
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

