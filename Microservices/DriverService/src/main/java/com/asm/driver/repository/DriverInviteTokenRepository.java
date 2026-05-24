package com.asm.driver.repository;

import com.asm.driver.entity.DriverInviteToken;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface DriverInviteTokenRepository extends JpaRepository<DriverInviteToken, UUID> {
    Optional<DriverInviteToken> findByToken(UUID token);
    void deleteByDriverId(UUID driverId);
}
