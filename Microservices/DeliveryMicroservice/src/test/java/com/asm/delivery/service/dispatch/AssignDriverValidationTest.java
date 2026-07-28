package com.asm.delivery.service.dispatch;

import com.asm.delivery.dto.request.AssignDeliveryRequest;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.DriverDeliveryService;
import com.asm.delivery.transport.TransportPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The admin assign path takes the driver id from the request body, unlike the driver's own
 * self-accept where it comes from the verified JWT. It used to forward that id to {@code accept()}
 * unchecked, so any UUID was accepted and the delivery was left SCHEDULED against a driver that does
 * not exist — unworkable, and with no unassign endpoint to recover it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssignDriverValidationTest {

    @Mock private TransportPort transportPort;
    @Mock private DriverDeliveryService driverDeliveryService;

    @InjectMocks private DispatchService dispatchService;

    private final UUID deliveryId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();

    private AssignDeliveryRequest request(UUID id) {
        AssignDeliveryRequest r = new AssignDeliveryRequest();
        r.setDriverId(id);
        return r;
    }

    @Test
    void rejectsUnknownDriverWithoutTouchingDeliveryState() {
        when(transportPort.driverExists(driverId.toString())).thenReturn(false);

        assertThatThrownBy(() -> dispatchService.assignDelivery(deliveryId, request(driverId), (UserPrincipal) null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("Chauffeur introuvable");

        verify(driverDeliveryService, never()).accept(any(), any(), any());
    }

    @Test
    void rejectsNullDriverId() {
        assertThatThrownBy(() -> dispatchService.assignDelivery(deliveryId, request(null), (UserPrincipal) null))
                .isInstanceOf(AppException.class);

        verify(driverDeliveryService, never()).accept(any(), any(), any());
        verify(transportPort, never()).driverExists(any());
    }

    /** A DriverService outage must propagate as 503, not be swallowed into "driver not found". */
    @Test
    void propagatesDriverServiceOutage() {
        when(transportPort.driverExists(driverId.toString()))
                .thenThrow(AppException.serviceUnavailable("DRIVER_SERVICE_UNAVAILABLE", "down"));

        assertThatThrownBy(() -> dispatchService.assignDelivery(deliveryId, request(driverId), (UserPrincipal) null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo("DRIVER_SERVICE_UNAVAILABLE");

        verify(driverDeliveryService, never()).accept(any(), any(), any());
    }

    @Test
    void acceptsAKnownDriver() {
        when(transportPort.driverExists(driverId.toString())).thenReturn(true);

        // getDeliveryDetail runs afterwards and needs far more collaborators than this focused test
        // wires up; reaching it already proves the guard let the assignment through.
        try {
            dispatchService.assignDelivery(deliveryId, request(driverId), (UserPrincipal) null);
        } catch (Exception ignored) {
            // fall through — the assertion below is what this test is about
        }
        verify(driverDeliveryService).accept(eq(deliveryId), eq(driverId), any());
    }
}
