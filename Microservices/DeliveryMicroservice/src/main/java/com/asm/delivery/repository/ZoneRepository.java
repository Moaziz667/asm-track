package com.asm.delivery.repository;

import com.asm.delivery.entity.Zone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ZoneRepository extends JpaRepository<Zone, UUID> {

    List<Zone> findAllByCompanyIdOrderByCreatedAtDesc(UUID companyId);

    List<Zone> findByCompanyId(UUID companyId);

    List<Zone> findByCompanyIdAndIsActiveTrueOrderByNameAsc(UUID companyId);

    /** Find first active zone whose postalCodes JSONB array contains the given code. */
    @Query(value = "SELECT * FROM zones WHERE is_active = true AND company_id = :companyId AND postal_codes @> CAST(json_build_array(:code) AS jsonb) LIMIT 1", nativeQuery = true)
    Optional<Zone> findActiveByPostalCodeMember(@Param("companyId") UUID companyId, @Param("code") String postalCode);

    /** Fallback: find first active zone whose cities JSONB array contains the given city name. */
    @Query(value = "SELECT * FROM zones WHERE is_active = true AND company_id = :companyId AND cities @> CAST(json_build_array(:city) AS jsonb) LIMIT 1", nativeQuery = true)
    Optional<Zone> findActiveByCityMember(@Param("companyId") UUID companyId, @Param("city") String city);

    /**
     * Batch: find all active zones that contain ANY of the given postal codes.
     * Used for multi-zone detection on routes.
     */
    @Query(value = """
            SELECT DISTINCT z.* FROM zones z
            WHERE z.is_active = true
              AND z.company_id = :companyId
              AND EXISTS (
                SELECT 1 FROM jsonb_array_elements_text(z.postal_codes) pc
                WHERE pc = ANY(:codes)
              )
            ORDER BY z.name
            """, nativeQuery = true)
    List<Zone> findActiveZonesByPostalCodes(@Param("companyId") UUID companyId, @Param("codes") String[] codes);
    
    Optional<Zone> findByCompanyIdAndId(UUID companyId, UUID id);
}
