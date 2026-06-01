package com.asm.driver.repository;

import com.asm.driver.entity.DriverInviteToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DriverInviteTokenRepository extends JpaRepository<DriverInviteToken, UUID> {

    Optional<DriverInviteToken> findByToken(UUID token);

    List<DriverInviteToken> findByDriverId(UUID driverId);

    void deleteByDriverId(UUID driverId);

    @Query("SELECT t FROM DriverInviteToken t WHERE t.expiresAt < :threshold")
    List<DriverInviteToken> findAllExpired(@Param("threshold") LocalDateTime threshold);

    @Modifying
    @Query("DELETE FROM DriverInviteToken t WHERE t.expiresAt < :threshold")
    int deleteAllExpired(@Param("threshold") LocalDateTime threshold);
}
