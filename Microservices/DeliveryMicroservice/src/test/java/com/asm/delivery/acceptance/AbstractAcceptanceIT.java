package com.asm.delivery.acceptance;

import com.asm.tenant.jpa.SchemaMultiTenantConnectionProvider;
import com.asm.tenant.jpa.TenantIdentifierResolver;
import com.asm.delivery.config.TenantSchemaProvisioner;
import com.asm.delivery.entity.*;
import com.asm.delivery.erp.port.ErpPort;
import com.asm.delivery.repository.*;
import com.asm.tenant.TenantContext;
import com.asm.delivery.service.EventPublisher;
import com.asm.delivery.storage.MinioStorageService;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

@SpringBootTest
@ActiveProfiles("test")
abstract class AbstractAcceptanceIT {

    /**
     * DB source, resolved once for the whole suite (same switch as {@code AbstractPostgresIT}):
     * <ul>
     *   <li><b>{@code IT_DB_URL} set (CI):</b> the side-car Postgres — the GitLab docker executor has no
     *       Docker daemon of its own, so Testcontainers can't spawn a container there;</li>
     *   <li><b>otherwise (local dev):</b> a throwaway Testcontainers Postgres.</li>
     * </ul>
     * {@code null} when running against an external DB.
     */
    static final PostgreSQLContainer<?> POSTGRES;

    static {
        if (System.getenv("IT_DB_URL") == null) {
            POSTGRES = new PostgreSQLContainer<>("postgres:16").withDatabaseName("acceptance_test");
            POSTGRES.start();
        } else {
            POSTGRES = null;
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (POSTGRES != null) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> System.getenv("IT_DB_URL"));
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("IT_DB_USER", "delivery"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("IT_DB_PASS", "delivery"));
        }
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        // Keep RabbitAutoConfiguration: the app's RabbitMQConfig#rabbitTemplate needs a ConnectionFactory
        // bean. The CachingConnectionFactory connects lazily (only on first send), so with the listeners'
        // auto-startup off nothing touches a broker during the test — the context loads without RabbitMQ.
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("spring.rabbitmq.listener.direct.auto-startup", () -> "false");
        // Use the in-memory simple broker, NOT the STOMP relay: the app enables a TCP broker relay
        // (websocket.broker.relay.enabled=true) that opens an auto-reconnecting reactor-netty client to
        // :61613. With no broker in CI its non-daemon retry threads never die, so the test JVM can't exit
        // and the gradle task hangs until the pipeline timeout.
        registry.add("websocket.broker.relay.enabled", () -> "false");
        // Turn off the @Scheduled background jobs (outbox drain, SLA monitors, ERP auto-import, health
        // snapshots): their outbound calls (RabbitMQ / ERP adapter) would otherwise park a non-daemon
        // scheduler thread in CI (host resolves but never answers), keeping the test JVM alive forever.
        registry.add("app.scheduling.enabled", () -> "false");
        registry.add("spring.autoconfigure.exclude", () ->
                "org.springframework.boot.autoconfigure.websocket.WebSocketAutoConfiguration,"
              + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
              + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
              + "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration");
    }

    protected static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Autowired TenantSchemaProvisioner provisioner;
    @Autowired OrderRepository orderRepository;
    @Autowired DeliveryRepository deliveryRepository;
    @Autowired DeliveryStatusHistoryRepository historyRepository;
    @Autowired RouteRepository routeRepository;
    @Autowired VehicleRepository vehicleRepository;
    @Autowired DepotRepository depotRepository;
    @Autowired OutboxRepository outboxRepository;

    // @MockBean removes the real bean definition and registers a Mockito mock in its place, regardless of
    // the real bean's name or registration order — so the mock always wins injection (a @Primary @Bean does
    // not: the real TransportPort bean is also named "transportPort", and with bean-override enabled it shadowed
    // the mock). It also means the real MinioStorageService/EventPublisher are never instantiated, so their
    // broker/@PostConstruct wiring never runs during the test.
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
