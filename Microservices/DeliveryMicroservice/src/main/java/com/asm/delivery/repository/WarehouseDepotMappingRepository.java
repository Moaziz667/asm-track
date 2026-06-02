package com.asm.delivery.repository;

import com.asm.delivery.entity.WarehouseDepotMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WarehouseDepotMappingRepository extends JpaRepository<WarehouseDepotMapping, UUID> {

    Optional<WarehouseDepotMapping> findByWarehouseCode(String warehouseCode);

    boolean existsByWarehouseCode(String warehouseCode);
}
