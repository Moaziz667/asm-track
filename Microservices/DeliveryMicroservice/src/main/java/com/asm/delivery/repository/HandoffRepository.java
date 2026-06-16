package com.asm.delivery.repository;

import com.asm.delivery.entity.Handoff;
import com.asm.delivery.entity.HandoffState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HandoffRepository extends JpaRepository<Handoff, UUID> {

    /** The one open handoff for a delivery (REQUESTED or IN_PROGRESS), if any. */
    @Query("SELECT h FROM Handoff h WHERE h.deliveryId = :deliveryId " +
           "AND h.state IN (com.asm.delivery.entity.HandoffState.REQUESTED, com.asm.delivery.entity.HandoffState.IN_PROGRESS) " +
           "ORDER BY h.requestedAt DESC")
    List<Handoff> findOpenByDeliveryId(@Param("deliveryId") UUID deliveryId);

    default Optional<Handoff> findActiveByDeliveryId(UUID deliveryId) {
        return findOpenByDeliveryId(deliveryId).stream().findFirst();
    }

    /** Open handoffs where this driver must hand the parcel over (sender view). */
    List<Handoff> findByFromDriverIdAndStateIn(UUID fromDriverId, List<HandoffState> states);

    /** Open handoffs where this driver must receive the parcel (receiver view). */
    List<Handoff> findByToDriverIdAndStateIn(UUID toDriverId, List<HandoffState> states);

    /** SLA sweeper: open handoffs requested before the cutoff that aren't yet flagged overdue. */
    @Query("SELECT h FROM Handoff h WHERE h.state IN " +
           "(com.asm.delivery.entity.HandoffState.REQUESTED, com.asm.delivery.entity.HandoffState.IN_PROGRESS) " +
           "AND h.requestedAt < :cutoff AND h.overdueNotifiedAt IS NULL")
    List<Handoff> findOverdue(@Param("cutoff") LocalDateTime cutoff);

    /** Open handoffs older than the cutoff (used for optional auto-cancel). */
    @Query("SELECT h FROM Handoff h WHERE h.state IN " +
           "(com.asm.delivery.entity.HandoffState.REQUESTED, com.asm.delivery.entity.HandoffState.IN_PROGRESS) " +
           "AND h.requestedAt < :cutoff")
    List<Handoff> findOpenOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /** Admin oversight feed: every open handoff (always) + terminal ones since the cutoff,
     *  so the history stays bounded instead of an unbounded findAll(). Newest first. */
    @Query("SELECT h FROM Handoff h WHERE h.state IN " +
           "(com.asm.delivery.entity.HandoffState.REQUESTED, com.asm.delivery.entity.HandoffState.IN_PROGRESS) " +
           "OR h.requestedAt >= :since ORDER BY h.requestedAt DESC")
    List<Handoff> findForAdmin(@Param("since") LocalDateTime since);
}
