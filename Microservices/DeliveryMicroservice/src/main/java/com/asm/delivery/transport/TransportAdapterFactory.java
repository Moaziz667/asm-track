package com.asm.delivery.transport;

import com.asm.delivery.transport.adapters.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class TransportAdapterFactory {

    @Value("${transport.provider:internal}")
    private String provider;

    @Bean
    public TransportPort transportPort(DriverInternalClient driverInternalClient) {
        String p = provider.trim().toLowerCase();
        log.info("TransportPort provider: {}", p);
        return switch (p) {
            case "mock"  -> new MockTransportAdapter();
            default      -> new InternalTransportAdapter(driverInternalClient);
        };
    }
}
