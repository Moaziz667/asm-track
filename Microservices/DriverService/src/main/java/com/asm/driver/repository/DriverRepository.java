package com.asm.driver.repository;

import com.asm.driver.entity.Driver;
import com.asm.driver.entity.DriverOnlineStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.asm.driver.entity.DriverAccountStatus;

public interface DriverRepository extends JpaRepository<Driver, UUID> {
    Optional<Driver> findByPhone(String phone);
    Optional<Driver> findByEmail(String email);
    boolean existsByPhone(String phone);
    boolean existsByEmail(String email);
    List<Driver> findByAccountStatus(DriverAccountStatus accountStatus);
    /**
     * The safety net, not the rule.
     *
     * <p>Availability is decided by the realtime connection now (DriverPresenceTracker publishes it,
     * DriverPresenceConsumer applies it). This only catches what that missed: a session whose close
     * was never delivered, or one held while DeliveryService was restarted.
     *
     * <p>Three changes make it safe to leave running. It reads {@code lastSeenAt} as well as
     * {@code lastLocationAt}, so a connected driver parked at a customer is no longer swept for not
     * moving. It COALESCEs both, because a NULL is not "older than" anything — which is how a driver
     * who had never sent a position stayed online indefinitely while the ones who did got swept.
     * And it leaves ON_BREAK alone: that is the driver's own statement, and the connection is what
     * ends it.
     */
    @Query("SELECT d FROM Driver d WHERE d.onlineStatus = :online AND ("
         + "  COALESCE(d.lastSeenAt, d.lastLocationAt) IS NULL"
         + "  OR (COALESCE(d.lastSeenAt, d.lastLocationAt) < :threshold AND d.updatedAt < :threshold))")
    List<Driver> findStaleForAutoOffline(
            @Param("online") DriverOnlineStatus online,
            @Param("threshold") LocalDateTime threshold);

    /** Single UPDATE to set all stale online/on-duty drivers to OFFLINE — avoids N+1 saves. */
    @Modifying
    @Query("UPDATE Driver d SET d.onlineStatus = :offline, d.updatedAt = :now " +
           "WHERE d.onlineStatus = :online " +
           "AND (COALESCE(d.lastSeenAt, d.lastLocationAt) IS NULL " +
           "     OR (COALESCE(d.lastSeenAt, d.lastLocationAt) < :threshold AND d.updatedAt < :threshold))")
    int bulkOfflineStaleDrivers(
            @Param("offline") DriverOnlineStatus offline,
            @Param("online") DriverOnlineStatus online,
            @Param("now") LocalDateTime now,
            @Param("threshold") LocalDateTime threshold);
}
