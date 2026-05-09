package com.asm.delivery.repository;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    @Query("SELECT DISTINCT d.companyId FROM Delivery d WHERE d.companyId IS NOT NULL")
    List<UUID> findAllCompanyIds();

    List<Delivery> findByStatus(DeliveryStatus status);

    Optional<Delivery> findByOrderId(UUID orderId);

    @Query("SELECT d FROM Delivery d JOIN FETCH d.order WHERE d.order.id = :orderId")
    Optional<Delivery> findByOrderIdWithOrder(@Param("orderId") UUID orderId);

    /** Returns all deliveries waiting for a driver, joining order for full info. */
    @Query("SELECT d FROM Delivery d JOIN FETCH d.order WHERE d.status = :status ORDER BY d.order.priority DESC, d.createdAt ASC")
    List<Delivery> findAllWaitingWithOrder(@Param("status") DeliveryStatus status);

    /** Active delivery for a given driver (ASSIGNED, PICKED_UP, IN_TRANSIT). */
    @Query("SELECT d FROM Delivery d JOIN FETCH d.order WHERE d.driverId = :driverId AND d.status IN :statuses")
    List<Delivery> findActiveForDriver(@Param("driverId") UUID driverId, @Param("statuses") List<DeliveryStatus> statuses);

    @Query("SELECT d FROM Delivery d WHERE d.driverId IS NOT NULL AND d.status IN :statuses")
    List<Delivery> findActiveDeliveries(@Param("statuses") List<DeliveryStatus> statuses);

    @Query("SELECT d FROM Delivery d JOIN FETCH d.order WHERE d.id = :id")
    Optional<Delivery> findByIdWithOrder(@Param("id") UUID id);

    @Query("SELECT d FROM Delivery d JOIN FETCH d.order WHERE d.id IN :ids")
    List<Delivery> findAllByIdInWithOrder(@Param("ids") List<UUID> ids);

    /** Driver history - completed/failed/cancelled deliveries. */
    @Query("SELECT d FROM Delivery d JOIN FETCH d.order WHERE d.driverId = :driverId AND d.status IN :statuses ORDER BY d.updatedAt DESC")
    List<Delivery> findHistoryForDriver(@Param("driverId") UUID driverId, @Param("statuses") List<DeliveryStatus> statuses);

    /** Distinct driver IDs that have deliveries in this company (companyFilter applies). */
    @Query("SELECT DISTINCT d.driverId FROM Delivery d WHERE d.driverId IS NOT NULL AND d.status IN :statuses")
    Set<UUID> findDistinctDriverIds(@Param("statuses") List<DeliveryStatus> statuses);

    /** All deliveries for a driver scoped by company filter. */
    @Query("SELECT d FROM Delivery d WHERE d.driverId = :driverId AND d.status IN :statuses")
    List<Delivery> findByDriverIdAndStatuses(@Param("driverId") UUID driverId, @Param("statuses") List<DeliveryStatus> statuses);

    /** Atomic accept: sets driver and transitions UNSCHEDULED → SCHEDULED.
     *  Returns 1 if successful, 0 if already taken (race condition). */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
        UPDATE deliveries
        SET status = 'SCHEDULED', driver_id = :driverId,
            assigned_at = NOW(), updated_at = NOW()
        WHERE id = :id AND status = 'UNSCHEDULED'
          AND (:companyId IS NULL OR company_id = :companyId)
        """, nativeQuery = true)
    int atomicAccept(@Param("id") UUID id,
                     @Param("driverId") UUID driverId,
                     @Param("companyId") UUID companyId);
}
