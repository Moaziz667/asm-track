package com.asm.delivery.transport;

import com.asm.delivery.exception.AppException;
import com.asm.delivery.transport.adapters.DriverInternalClient;
import com.asm.delivery.transport.adapters.InternalTransportAdapter;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalTransportAdapterTest {

    @Mock
    private DriverInternalClient client;

    private InternalTransportAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new InternalTransportAdapter(client);
    }

    private static FeignException notFound() {
        Request request = Request.create(Request.HttpMethod.GET, "/internal/drivers/x",
                Map.of(), null, StandardCharsets.UTF_8, new RequestTemplate());
        return FeignException.errorStatus("getDriver",
                feign.Response.builder().status(404).request(request).build());
    }

    @Test
    void driverExists_trueWhenDriverServiceReturnsIt() {
        when(client.getDriver("d1")).thenReturn(DriverDTO.builder().id("d1").build());
        assertTrue(adapter.driverExists("d1"));
    }

    @Test
    void driverExists_falseOn404() {
        when(client.getDriver("ghost")).thenThrow(notFound());
        assertFalse(adapter.driverExists("ghost"));
    }

    /**
     * Spring Cloud CircuitBreaker wraps every Feign call, so a 404 reaches us as
     * NoFallbackAvailableException with the real cause underneath — never as a bare
     * FeignException.NotFound. Matching only the unwrapped type reported "unknown driver" as
     * "DriverService is down".
     */
    @Test
    void driverExists_falseWhen404IsWrappedByTheCircuitBreaker() {
        when(client.getDriver("ghost")).thenThrow(
                new org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException(
                        "No fallback available.", notFound()));

        assertFalse(adapter.driverExists("ghost"));
    }

    /**
     * The distinction that matters: an unreachable DriverService must not be reported as "the driver
     * does not exist", which would reject every valid assignment during an outage.
     */
    @Test
    void driverExists_throwsWhenDriverServiceIsUnreachable() {
        when(client.getDriver("d1")).thenThrow(new RuntimeException("connection refused"));

        AppException ex = assertThrows(AppException.class, () -> adapter.driverExists("d1"));
        assertEquals("DRIVER_SERVICE_UNAVAILABLE", ex.getErrorCode());
    }

    /** getDriver keeps its lenient fallback — it backs reads, not writes. */
    @Test
    void getDriver_stillDegradesToNullOnFailure() {
        when(client.getDriver("d1")).thenThrow(new RuntimeException("boom"));
        assertNull(adapter.getDriver("d1"));
    }
}
