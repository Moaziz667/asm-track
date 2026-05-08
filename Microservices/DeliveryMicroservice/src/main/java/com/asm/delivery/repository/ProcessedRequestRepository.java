package com.asm.delivery.repository;

import com.asm.delivery.entity.ProcessedRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedRequestRepository extends JpaRepository<ProcessedRequest, String> {
}
