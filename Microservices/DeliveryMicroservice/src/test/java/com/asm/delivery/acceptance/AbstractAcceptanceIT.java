package com.asm.delivery.acceptance;

import com.asm.delivery.config.SchemaMultiTenantConnectionProvider;
import com.asm.delivery.config.TenantIdentifierResolver;
import com.asm.delivery.config.TenantSchemaProvisioner;
import com.asm.delivery.entity.*;
import com.asm.delivery.erp.port.ErpPort;
import com.asm.delivery.repository.*;
import com.asm.delivery.security.TenantContext;
import com.asm.delivery.service.EventPublisher;
import com.asm.delivery.storage.MinioStorageService;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Base class for delivery-lifecycle acceptance tests.
 *
 * Boots the full Spring context against a throwaway Testcontainers Postgres, provisions a
 * tenant schema, and mocks every external boundary (MinIO, ERP adapter, driver-service Feign).
 * Each test gets a clean tenant context and a seeded Vehicle + Depot.
 */
@SpringBootTest
@ActiveProfiles("test")
abstract class AbstractAcceptanceIT {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withDatabaseName("acceptance_test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "5672");
        registry.add("spring.autoconfigure.exclude", () ->
                "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,"
              + "org.springframework.boot.autoconfigure.websocket.WebSocketAutoConfiguration,"
              + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
              + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
              + "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration");
    }

    private static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Autowired TenantSchemaProvisioner provisioner;
    @Autowired OrderRepository orderRepository;
    @Autowired DeliveryRepository deliveryRepository;
    @Autowired DeliveryStatusHistoryRepository historyRepository;
    @Autowired RouteRepository routeRepository;
    @Autowired VehicleRepository vehicleRepository;
    @Autowired DepotRepository depotRepository;
    @Autowired OutboxRepository outboxRepository;

    @MockBean ErpPort erpPort;
    @MockBean TransportPort transportPort;
    @MockBean MinioStorageService minioStorageService;
    @MockBean EventPublisher eventPublisher;

    protected Vehicle testVehicle;
    protected Depot testDepot;

    @BeforeEach
    void setUpTenant() {
        TenantContext.set(TENANT_ID);
        provisioner.deprovision(TENANT_ID);
        provisioner.provision(TENANT_ID);

        testDepot = depotRepository.save(Depot.builder()
                .name("Depot Principal")
                .address("Zone Industrielle, Sfax")
                .warehouseCode("WH001")
                .latitude(34.74).longitude(10.76)
                .isActive(true)
                .build());

        testVehicle = vehicleRepository.save(Vehicle.builder()
                .name("Van Alpha")
                .make("Renault").model("Master")
                .plate("123 TU 0001")
                .type(VehicleType.VAN)
                .payloadKg(1200)
                .active(true)
                .build());

        when(transportPort.getDriver(anyString())).thenReturn(DriverDTO.builder()
                .id(UUID.randomUUID().toString())
                .name("Test Driver").phone("+216 71 000 000")
                .active(true).build());

        when(minioStorageService.uploadFile(any(byte[].class), anyString(), anyString()))
                .thenReturn("https://minio.example.com/pod/test.jpg");
    }

    @AfterEach
    void tearDownTenant() {
        TenantContext.clear();
        provisioner.deprovision(TENANT_ID);
    }

    protected Order createTestOrder(String erpOrderId, String blNumber) {
        return orderRepository.save(Order.builder()
                .source(OrderSource.ODOO)
                .clientName("Client Test").clientPhone("+216 71 123 456")
                .dropoffAddress("Avenue Habib Bourguiba, Sfax")
                .dropoffCity("Sfax")
                .dropoffCountryCode("TN")
                .totalAmount(BigDecimal.valueOf(150.000))
                .currency("TND")
                .status(OrderStatus.PENDING)
                .erpOrderId(erpOrderId)
                .blNumber(blNumber)
                .items(List.of())
                .totalQuantity(2)
                .totalWeightKg(BigDecimal.valueOf(5.5))
                .build());
    }

    protected Delivery createTestDelivery(Order order) {
        return deliveryRepository.save(Delivery.builder()
                .order(order)
                .status(DeliveryStatus.UNSCHEDULED)
                .sourceDepotId(testDepot.getId())
                .build());
    }

    protected Route createTestRoute(UUID driverId) {
        return routeRepository.save(Route.builder()
                .name("Acceptance Route")
                .driverId(driverId)
                .vehicleId(testVehicle.getId())
                .date(java.time.LocalDate.now())
                .plannedStartTime(java.time.LocalTime.of(8, 0))
                .plannedEndTime(java.time.LocalTime.of(17, 0))
                .city("Sfax")
                .status(RouteStatus.DRAFT)
                .createdBy("acceptance-test")
                .depotId(testDepot.getId())
                .departureTime(LocalDateTime.now())
                .build());
    }
}
