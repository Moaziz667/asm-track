package com.asm.delivery.service;

import com.asm.delivery.dto.response.GeocodeSuggestionResponse;
import com.asm.delivery.entity.Depot;
import com.asm.delivery.erp.ErpWarehouseDTO;
import com.asm.delivery.erp.port.ErpPort;
import com.asm.delivery.repository.DepotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Characterization tests for the ERP → depot sync. Pins the mapping behavior after ErpPort was
 * typed (Map → ErpWarehouseDTO): a warehouse with coordinates is mirrored 1:1; a warehouse without
 * coordinates falls back to geocoding.
 */
@ExtendWith(MockitoExtension.class)
class DepotSyncServiceTest {

    @Mock ErpPort erpPort;
    @Mock DepotRepository depotRepository;
    @Mock GeocodingService geocodingService;

    private DepotSyncService service;

    @BeforeEach
    void setUp() {
        service = new DepotSyncService(erpPort, depotRepository, geocodingService);
        lenient().when(depotRepository.save(any(Depot.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void mirrorsWarehouseWithCoordinatesOneToOne() {
        ErpWarehouseDTO wh = ErpWarehouseDTO.builder()
                .erpWarehouseId("7").code("SFAX").name("Entrepôt Sfax")
                .address("Zone Industrielle").city("Sfax")
                .latitude(34.74).longitude(10.76).build();
        when(erpPort.getWarehouses()).thenReturn(List.of(wh));
        when(depotRepository.findByWarehouseCode("SFAX")).thenReturn(Optional.empty());

        DepotSyncService.SyncResult res = service.syncFromErp();

        ArgumentCaptor<Depot> captor = ArgumentCaptor.forClass(Depot.class);
        verify(depotRepository).save(captor.capture());
        Depot saved = captor.getValue();
        assertThat(saved.getWarehouseCode()).isEqualTo("SFAX");
        assertThat(saved.getName()).isEqualTo("Entrepôt Sfax");
        assertThat(saved.getErpWarehouseId()).isEqualTo("7");
        assertThat(saved.getLatitude()).isEqualTo(34.74);
        assertThat(saved.getLongitude()).isEqualTo(10.76);
        assertThat(res.created()).isEqualTo(1);
        assertThat(res.geocoded()).isZero();
        verifyNoInteractions(geocodingService);
    }

    @Test
    void geocodesWhenWarehouseHasNoCoordinates() {
        ErpWarehouseDTO wh = ErpWarehouseDTO.builder()
                .code("TUN").name("Tunis").address("Rue de Rome").city("Tunis").build();
        when(erpPort.getWarehouses()).thenReturn(List.of(wh));
        when(depotRepository.findByWarehouseCode("TUN")).thenReturn(Optional.empty());

        GeocodeSuggestionResponse geo = mock(GeocodeSuggestionResponse.class);
        when(geo.isFound()).thenReturn(true);
        when(geo.getLat()).thenReturn(36.8);
        when(geo.getLng()).thenReturn(10.18);
        when(geocodingService.geocodeGlobal(anyString())).thenReturn(geo);

        DepotSyncService.SyncResult res = service.syncFromErp();

        ArgumentCaptor<Depot> captor = ArgumentCaptor.forClass(Depot.class);
        verify(depotRepository).save(captor.capture());
        Depot saved = captor.getValue();
        assertThat(saved.getLatitude()).isEqualTo(36.8);
        assertThat(saved.getLongitude()).isEqualTo(10.18);
        assertThat(res.geocoded()).isEqualTo(1);
    }

    @Test
    void skipsWarehouseWithoutCode() {
        ErpWarehouseDTO wh = ErpWarehouseDTO.builder().name("no code").build();
        when(erpPort.getWarehouses()).thenReturn(List.of(wh));

        DepotSyncService.SyncResult res = service.syncFromErp();

        assertThat(res.total()).isEqualTo(1);
        assertThat(res.created()).isZero();
        verify(depotRepository, never()).save(any());
    }
}
