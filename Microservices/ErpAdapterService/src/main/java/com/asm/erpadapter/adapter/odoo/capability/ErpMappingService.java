package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.entity.ErpMapping;
import com.asm.erpadapter.repository.ErpMappingRepository;
import com.asm.erpadapter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service layer for ERP capability mappings (customer overrides).
 *
 * <p>Provides a cache-friendly interface for the {@link CapabilityResolver}
 * to check customer-specific field/method overrides.
 *
 * <p>On create/delete, invalidates {@link CapabilityCache}
 * so changes take effect immediately.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErpMappingService {

    private final ErpMappingRepository repository;
    private final CapabilityCache capabilityCache;

    public Optional<ErpMapping> findByTenantAndCapability(UUID tenantId, String capability) {
        return repository.findByTenantIdAndCapability(tenantId, capability);
    }

    public List<ErpMapping> findAllByTenant(UUID tenantId) {
        return repository.findByTenantId(tenantId);
    }

    public ErpMapping createMapping(UUID tenantId, String capability, String mappingType,
                                     String odooName, String targetModel) {
        ErpMapping mapping = ErpMapping.builder()
                .tenantId(tenantId)
                .capability(capability)
                .mappingType(mappingType)
                .odooName(odooName)
                .targetModel(targetModel)
                .createdAt(LocalDateTime.now())
                .build();
        ErpMapping saved = repository.save(mapping);
        invalidateCaches(tenantId, capability);
        log.info("ErpMapping created: tenant={} capability={} type={} odooName={}",
                tenantId, capability, mappingType, odooName);
        return saved;
    }

    public void deleteMapping(UUID tenantId, String capability) {
        repository.deleteByTenantIdAndCapability(tenantId, capability);
        invalidateCaches(tenantId, capability);
        log.info("ErpMapping deleted: tenant={} capability={}", tenantId, capability);
    }

    /**
     * Invalidate CapabilityCache for a specific tenant+capability.
     * Sets TenantContext temporarily so the cache key resolves correctly,
     * preserving any previously-set tenant on the calling thread.
     */
    private void invalidateCaches(UUID tenantId, String capability) {
        UUID previous = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            capabilityCache.invalidate(capability);
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
