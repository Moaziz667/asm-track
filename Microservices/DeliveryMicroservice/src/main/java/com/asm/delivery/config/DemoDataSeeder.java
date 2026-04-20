package com.asm.delivery.config;

import com.asm.delivery.entity.*;
import com.asm.delivery.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@Profile("!prod") // Active on default/dev to generate seed data for demo
@RequiredArgsConstructor
@Slf4j
public class DemoDataSeeder implements CommandLineRunner {

    private final VehicleRepository vehicleRepository;
    private final DepotRepository depotRepository;
    private final ZoneRepository zoneRepository;
    private final OrderRepository orderRepository;
    private final DeliveryRepository deliveryRepository;
    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        long deliveryCount = deliveryRepository.count();
        if (deliveryCount > 0) {
            log.info("Database already seeded ({} deliveries found)", deliveryCount);
            return;
        }

        log.info("Generating demo data for PFE Defense...");

        // 1. Zones
        Zone nord = zoneRepository.save(Zone.builder().name("Grand Tunis Nord").color("#3b82f6").isActive(true).build());
        Zone sud = zoneRepository.save(Zone.builder().name("Grand Tunis Sud").color("#ef4444").isActive(true).build());
        Zone est = zoneRepository.save(Zone.builder().name("Banlieue Est").color("#10b981").isActive(true).build());

        List<Zone> zones = List.of(nord, sud, est);

        // 2. Depots
        Depot principal = depotRepository.save(Depot.builder()
                .name("Entrepôt Central Charguia")
                .address("Z.I Charguia 1, Tunis")
                .latitude(36.8402)
                .longitude(10.2033)
                .isActive(true)
                .build());

        // 3. Vehicles (20)
        List<Vehicle> vehicles = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            vehicles.add(vehicleRepository.save(Vehicle.builder()
                    .name("Vehicule " + i)
                    .plate(String.format("19%d TUN %d", i % 9 + 1, 1000 + i))
                    .make(i % 2 == 0 ? "Renault" : "Fiat")
                    .model(i % 2 == 0 ? "Kangoo" : "Doblo")
                    .type(VehicleType.VAN)
                    .payloadKg(500 + (i * 10))
                    .volumeM3(3.5)
                    .active(true)
                    .build()));
        }

        // 4. Drivers (20 UUIDs)
        List<UUID> driverIds = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            driverIds.add(UUID.randomUUID());
        }

        // 5. Orders & Deliveries (200 total to be "huge", let's do 25 per driver = 500?)
        // The user asked for "huge like 20 in every thing", so 20 routes, 20 drivers, 20 vehicles, 400 deliveries
        List<Delivery> allDeliveries = new ArrayList<>();

        for (int i = 1; i <= 400; i++) {
            Order order = Order.builder()
                    .erpOrderId("ORD" + 10000 + i)
                    .source(OrderSource.ODOO)
                    .clientName("Client " + i)
                    .clientPhone("+216 9" + (i % 9) + " 123 456")
                    .dropoffAddress(i + ", Rue de l'Exemple")
                    .dropoffCity("Tunis")
                    .dropoffLat(BigDecimal.valueOf(36.8 + (Math.random() * 0.1)))
                    .dropoffLng(BigDecimal.valueOf(10.1 + (Math.random() * 0.1)))
                    .zoneId(zones.get(i % zones.size()).getId())
                    .status(com.asm.delivery.entity.OrderStatus.PENDING)
                    .priority(i % 10 == 0 ? OrderPriority.HIGH : OrderPriority.NORMAL)
                    .totalAmount(BigDecimal.valueOf(100 + i * 2.5))
                    .totalWeightKg(BigDecimal.valueOf(2.5 + (i % 3)))
                    .scheduledAt(LocalDateTime.now().plusDays(i % 3))
                    .items(new ArrayList<>())
                    .deliveryInstructions("Livrer avant 14h")
                    .build();

            order = orderRepository.save(order);

            Delivery delivery = Delivery.builder()
                    .order(order)
                    .status(DeliveryStatus.UNSCHEDULED)
                    .build();

            allDeliveries.add(deliveryRepository.save(delivery));
        }

        // 6. Routes (20 routes, each with 15 stops)
        int deliveryCursor = 0;
        for (int i = 1; i <= 20; i++) {
            Route route = Route.builder()
                    .name("Tournée PFE " + i)
                    .status(RouteStatus.DRAFT)
                    .driverId(driverIds.get(i - 1))
                    .vehicleId(vehicles.get(i - 1).getId())
                    .date(LocalDate.now())
                    .createdBy("SYSTEM")
                    .build();

            route = routeRepository.save(route);

            for (int s = 1; s <= 15; s++) {
                if (deliveryCursor >= allDeliveries.size()) break;
                Delivery d = allDeliveries.get(deliveryCursor++);
                
                RouteStop stop = RouteStop.builder()
                        .route(route)
                        .deliveryId(d.getId())
                        .stopOrder(s)
                        .status(RouteStopStatus.PENDING)
                        .build();
                        
                routeStopRepository.save(stop);
            }
        }

        log.info("Demo data generation complete! Generated 400 deliveries, 20 routes, 20 drivers, 20 vehicles, 3 zones, 1 depot.");
    }
}
