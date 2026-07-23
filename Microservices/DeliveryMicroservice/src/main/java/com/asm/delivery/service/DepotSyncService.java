package com.asm.delivery.service;

import com.asm.delivery.dto.response.GeocodeSuggestionResponse;
import com.asm.delivery.entity.Depot;
import com.asm.delivery.erp.ErpWarehouseDTO;
import com.asm.delivery.erp.TenantErpProviderResolver;
import com.asm.delivery.erp.port.ErpPort;
import com.asm.delivery.repository.DepotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * Populates the {@code depots} table from the ERP's warehouses (Odoo {@code stock.warehouse}).
 * Depots are no longer created by hand — this sync is the source of truth. Coordinates come
 * from Odoo when present, otherwise we geocode the warehouse address (Nominatim) and cache it.
 * Idempotent: upsert keyed by {@code warehouse_code}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DepotSyncService {

    private final ErpPort erpPort;
    private final DepotRepository depotRepository;
    private final GeocodingService geocodingService;
    private final TenantErpProviderResolver providerResolver;

    /** Sync result for the admin UI. */
    public record SyncResult(int total, int created, int updated, int geocoded, int missingCoords) {}

    @Transactional
    public SyncResult syncFromErp() {
        List<ErpWarehouseDTO> warehouses = erpPort.getWarehouses();
        int created = 0, updated = 0, geocoded = 0, missingCoords = 0;

        for (ErpWarehouseDTO w : warehouses) {
            String code = w.getCode();
            if (!StringUtils.hasText(code)) continue;

            Depot depot = depotRepository.findByWarehouseCode(code).orElseGet(Depot::new);
            boolean isNew = depot.getId() == null;

            String newAddress = w.getAddress();
            String newCity = w.getCity();
            boolean addressChanged = !java.util.Objects.equals(depot.getAddress(), newAddress);

            depot.setWarehouseCode(code);
            depot.setErpWarehouseId(w.getErpWarehouseId());
            depot.setProvider(providerResolver.resolve());
            depot.setName(StringUtils.hasText(w.getName()) ? w.getName() : code);
            depot.setAddress(newAddress);
            if (depot.getIsActive() == null) depot.setIsActive(true);

            Double lat = w.getLatitude();
            Double lng = w.getLongitude();
            if (lat == null || lng == null) {
                // Re-geocode if the depot is new, the address changed in Odoo, or we have no coords yet.
                if (isNew || addressChanged || depot.getLatitude() == null || depot.getLongitude() == null) {
                    double[] coords = geocode(newAddress, newCity);
                    if (coords != null) {
                        lat = coords[0];
                        lng = coords[1];
                        geocoded++;
                    }
                }
            }
            if (lat != null && lng != null) {
                depot.setLatitude(lat);
                depot.setLongitude(lng);
            }
            if (depot.getLatitude() == null || depot.getLongitude() == null) {
                missingCoords++;
                log.warn("Depot sync: warehouse '{}' has no coordinates (Odoo empty + geocode failed)", code);
            }

            depotRepository.save(depot);
            if (isNew) created++; else updated++;
        }

        SyncResult result = new SyncResult(warehouses.size(), created, updated, geocoded, missingCoords);
        log.info("Depot sync from ERP: {}", result);
        return result;
    }

    private double[] geocode(String address, String city) {
        String query = StringUtils.hasText(address)
                ? (StringUtils.hasText(city) ? address + ", " + city : address)
                : city;
        if (!StringUtils.hasText(query)) return null;
        // Use geocodeGlobal so depot addresses from any country can be resolved
        GeocodeSuggestionResponse geo = geocodingService.geocodeGlobal(query);
        if (geo != null && geo.isFound() && geo.getLat() != null && geo.getLng() != null) {
            return new double[]{geo.getLat(), geo.getLng()};
        }
        return null;
    }
}
