package com.asm.delivery.repository;

import com.asm.delivery.entity.DeliveryReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface DeliveryReportRepository extends JpaRepository<DeliveryReport, UUID> {
}
