package com.asm.erpadapter.repository;

import com.asm.erpadapter.entity.ErpMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ErpMappingRepository extends JpaRepository<ErpMapping, Long> {

    Optional<ErpMapping> findByTenantIdAndCapability(UUID tenantId, String capability);

    List<ErpMapping> findByTenantId(UUID tenantId);

    void deleteByTenantIdAndCapability(UUID tenantId, String capability);
}
