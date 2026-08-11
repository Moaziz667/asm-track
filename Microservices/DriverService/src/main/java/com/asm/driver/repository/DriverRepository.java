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
    List<Driver> findByAccountStatusAndOnlineStatus(DriverAccountStatus accountStatus, DriverOnlineStatus onlineStatus);
    /**
     * Drivers the app has gone quiet on: no GPS fix <em>and</em> no status change since the threshold.
     *
     * <p>The GPS clause alone was not enough. Going on duty sets the status but not
     * {@code lastLocationAt} — the position follows separately, best-effort, and fails or lags
     * exactly where a driver usually is when he starts: indoors at the depot. So a driver pressed
     * "en service", the map turned green, and the next sweep read a location fix from before his
     * shift and put him back offline within five minutes.
     *
     * <p>Requiring both means a driver is only offlined once nothing at all has come from him.
     */
    @Query("SELECT d FROM Driver d WHERE d.onlineStatus <> :offline " +
           "AND d.lastLocationAt < :threshold AND d.updatedAt < :threshold")
    List<Driver> findStaleForAutoOffline(
            @Param("offline") DriverOnlineStatus offline,
            @Param("threshold") LocalDateTime threshold);

    /** Single UPDATE to set all stale online/on-duty drivers to OFFLINE — avoids N+1 saves. */
    @Modifying
    @Query("UPDATE Driver d SET d.onlineStatus = :offline, d.updatedAt = :now " +
           "WHERE d.onlineStatus <> :offline AND d.lastLocationAt < :threshold " +
           "AND d.updatedAt < :threshold")
    int bulkOfflineStaleDrivers(
            @Param("offline") DriverOnlineStatus offline,
            @Param("now") LocalDateTime now,
            @Param("threshold") LocalDateTime threshold);
}
