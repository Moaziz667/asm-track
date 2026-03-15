package com.asm.appbackend.repository;

import com.asm.appbackend.entity.ClientOtp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ClientOtpRepository extends JpaRepository<ClientOtp, UUID> {
    Optional<ClientOtp> findFirstByPhoneAndUsedFalseOrderByCreatedAtDesc(String phone);
}
