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

    @Value("${driver.service.url:http://driver-service:8086}")
    private String driverServiceUrl;

    @Value("${internal.secret:asm-internal-2026}")
    private String internalSecret;

    @Bean
    public TransportPort transportPort() {
        String p = provider.trim().toLowerCase();
        log.info("TransportPort provider: {}", p);
        return switch (p) {
            case "lalamove" -> new LalamoveAdapter();
            case "yassir"   -> new YassirAdapter();
            case "mock"     -> new MockTransportAdapter();
            default         -> new InternalTransportAdapter(driverServiceUrl, internalSecret);
        };
    }
}
