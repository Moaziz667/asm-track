package com.asm.erpadapter.repository;

import com.asm.erpadapter.entity.ErpFieldMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ErpFieldMappingRepository extends JpaRepository<ErpFieldMapping, Long> {

    /** Every override for one tenant — loaded once per import, not once per field. */
    List<ErpFieldMapping> findByTenantIdAndProvider(UUID tenantId, String provider);

    Optional<ErpFieldMapping> findByTenantIdAndProviderAndCanonicalField(
            UUID tenantId, String provider, String canonicalField);

    /** The customer-defined extras, which land in the order's {@code custom_fields} bag. */
    List<ErpFieldMapping> findByTenantIdAndProviderAndCanonicalFieldIsNull(UUID tenantId, String provider);

    void deleteByTenantIdAndProviderAndCanonicalField(UUID tenantId, String provider, String canonicalField);
}
