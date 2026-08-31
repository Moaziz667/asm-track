package com.asm.delivery.service.route;

import com.asm.delivery.dto.request.CreateRouteRequest;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.Route;
import com.asm.delivery.entity.RouteStatus;
import com.asm.delivery.entity.RouteStop;
import com.asm.delivery.entity.RouteStopStatus;
import com.asm.delivery.entity.RouteStopType;
import com.asm.delivery.entity.Vehicle;
import com.asm.delivery.entity.VehicleStatus;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.RouteRepository;
import com.asm.delivery.repository.RouteStopRepository;
import com.asm.delivery.repository.VehicleRepository;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the rules that decide whether a route is legal to build, extend or validate.
 *
 * <p>These checks are the only thing standing between a dispatcher's screen and a round that cannot
 * be driven: a van loaded past its payload, a driver committed to two routes at once, stops whose
 * windows overlap. They are exercised from four call sites in {@link RoutePlanningService} (create,
 * update, addStop, validate), so a regression here surfaces late and in a different place each time
 * — which is precisely why they are pinned here rather than only through the acceptance suite.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RouteValidatorTest {

    @Mock RouteRepository routeRepository;
    @Mock RouteStopRepository routeStopRepository;
    @Mock VehicleRepository vehicleRepository;
    @Mock DeliveryRepository deliveryRepository;
    @Mock TransportPort transportPort;

    @InjectMocks RouteValidator validator;

    private UUID routeId;
    private UUID vehicleId;
    private UUID driverId;
    private UUID homeDepot;
    private UUID remoteDepot;
    private LocalDate day;

    @BeforeEach
    void setUp() {
        routeId     = UUID.randomUUID();
        vehicleId   = UUID.randomUUID();
        driverId    = UUID.randomUUID();
        homeDepot   = UUID.randomUUID();
        remoteDepot = UUID.randomUUID();
        day         = LocalDate.of(2026, 6, 15);
    }

    // ── Driver availability ─────────────────────────────────────────────────────

    @Test
    void rejectsAnInactiveDriver() {
        when(transportPort.getDriver(driverId.toString())).thenReturn(driver(false));

        assertThatThrownBy(() -> validator.ensureDriverActive(driverId))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("inactive");
    }

    @Test
    void acceptsAnActiveDriver() {
        when(transportPort.getDriver(driverId.toString())).thenReturn(driver(true));

        assertThatCode(() -> validator.ensureDriverActive(driverId)).doesNotThrowAnyException();
    }

    /**
     * A DriverService outage must not block planning. The driver's active flag is a courtesy check,
     * not a safety one — refusing every route while a neighbouring service reboots would cost more
     * than the rare case it guards.
     */
    @Test
    void aDriverServiceOutageDoesNotBlockPlanning() {
        when(transportPort.getDriver(driverId.toString()))
                .thenThrow(new IllegalStateException("driver-service unreachable"));

        assertThatCode(() -> validator.ensureDriverActive(driverId)).doesNotThrowAnyException();
    }

    // ── Vehicle availability ────────────────────────────────────────────────────

    @Test
    void rejectsAnInactiveVehicle() {
        Vehicle v = vehicle(1000);
        v.setActive(false);
        when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> validator.ensureVehicleAvailable(vehicleId))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("inactive");
    }

    @Test
    void rejectsAVehicleThatIsNotAvailable() {
        Vehicle v = vehicle(1000);
        v.setVehicleStatus(VehicleStatus.IN_MAINTENANCE);
        when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> validator.ensureVehicleAvailable(vehicleId))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("not available");
    }

    // ── Schedule conflicts ──────────────────────────────────────────────────────

    @Test
    void rejectsADriverAlreadyCommittedOnAnOverlappingWindow() {
        when(routeRepository.findAllByDriverIdAndDate(driverId, day))
                .thenReturn(List.of(existingRoute(RouteStatus.VALIDATED, LocalTime.of(8, 0), LocalTime.of(12, 0))));

        assertThatThrownBy(() -> validator.ensureNoScheduleConflict(
                driverId, day, LocalTime.of(11, 0), LocalTime.of(15, 0), null))
                .isInstanceOf(AppException.class);
    }

    @Test
    void allowsADriverOnTwoRoundsThatDoNotOverlap() {
        when(routeRepository.findAllByDriverIdAndDate(driverId, day))
                .thenReturn(List.of(existingRoute(RouteStatus.VALIDATED, LocalTime.of(8, 0), LocalTime.of(12, 0))));

        assertThatCode(() -> validator.ensureNoScheduleConflict(
                driverId, day, LocalTime.of(13, 0), LocalTime.of(17, 0), null))
                .doesNotThrowAnyException();
    }

    /**
     * A draft is a dispatcher thinking aloud, not a commitment. Letting it reserve a driver would
     * make an abandoned draft silently lock a resource for the rest of the day.
     */
    @Test
    void aDraftRouteDoesNotReserveTheDriver() {
        when(routeRepository.findAllByDriverIdAndDate(driverId, day))
                .thenReturn(List.of(existingRoute(RouteStatus.DRAFT, LocalTime.of(8, 0), LocalTime.of(18, 0))));

        assertThatCode(() -> validator.ensureNoScheduleConflict(
                driverId, day, LocalTime.of(9, 0), LocalTime.of(11, 0), null))
                .doesNotThrowAnyException();
    }

    /** Re-validating a route must not make it collide with itself. */
    @Test
    void aRouteDoesNotConflictWithItself() {
        Route self = existingRoute(RouteStatus.VALIDATED, LocalTime.of(8, 0), LocalTime.of(18, 0));
        when(routeRepository.findAllByDriverIdAndDate(driverId, day)).thenReturn(List.of(self));

        assertThatCode(() -> validator.ensureNoScheduleConflict(
                driverId, day, LocalTime.of(9, 0), LocalTime.of(11, 0), self.getId()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAVehicleAlreadyCommittedOnAnOverlappingWindow() {
        when(routeRepository.findAllByVehicleIdAndDate(vehicleId, day))
                .thenReturn(List.of(existingRoute(RouteStatus.IN_PROGRESS, LocalTime.of(8, 0), LocalTime.of(12, 0))));

        assertThatThrownBy(() -> validator.ensureNoVehicleConflict(
                vehicleId, day, LocalTime.of(10, 0), LocalTime.of(14, 0), null))
                .isInstanceOf(AppException.class);
    }

    // ── Payload along the load curve ────────────────────────────────────────────

    @Test
    void rejectsARouteHeavierThanTheVehicleCanCarry() {
        Route route = routeUnderTest();
        RouteStop a = deliveryStop(1);
        RouteStop b = deliveryStop(2);
        stubStops(a, b);
        stubVehicle(vehicle(500));
        stubDeliveries(delivery(a, homeDepot, 300), delivery(b, homeDepot, 300));

        assertThatThrownBy(() -> validator.assertRouteWeightWithinVehicleCapacity(route))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("Capacity Exceeded");
    }

    @Test
    void acceptsARouteThatFitsExactly() {
        Route route = routeUnderTest();
        RouteStop a = deliveryStop(1);
        stubStops(a);
        stubVehicle(vehicle(500));
        stubDeliveries(delivery(a, homeDepot, 500));

        assertThatCode(() -> validator.assertRouteWeightWithinVehicleCapacity(route))
                .doesNotThrowAnyException();
    }

    /**
     * The van is never asked to hold everything at once: what matters is the heaviest moment, not the
     * sum. Here the first parcel is dropped before the second is loaded at the remote depot, so a
     * 600 kg round fits in a 400 kg van. Summing the weights would have refused a legal route.
     */
    @Test
    void weighsTheHeaviestMomentAndNotTheDailyTotal() {
        Route route = routeUnderTest();
        RouteStop dropHome  = deliveryStop(1);              // 300 kg loaded at the home depot
        RouteStop loadRemote = pickupStop(remoteDepot, 2);  // then we go and pick up 300 kg more
        RouteStop dropRemote = deliveryStop(3);
        stubStops(dropHome, loadRemote, dropRemote);
        stubVehicle(vehicle(400));
        stubDeliveries(delivery(dropHome, homeDepot, 300), delivery(dropRemote, remoteDepot, 300));

        assertThatCode(() -> validator.assertRouteWeightWithinVehicleCapacity(route))
                .doesNotThrowAnyException();
    }

    /** Same two parcels, but the remote load happens before the first drop: the peak is now 600 kg. */
    @Test
    void catchesTheOverloadWhenBothLoadsRideTogether() {
        Route route = routeUnderTest();
        RouteStop loadRemote = pickupStop(remoteDepot, 1);
        RouteStop dropHome   = deliveryStop(2);
        RouteStop dropRemote = deliveryStop(3);
        stubStops(loadRemote, dropHome, dropRemote);
        stubVehicle(vehicle(400));
        stubDeliveries(delivery(dropHome, homeDepot, 300), delivery(dropRemote, remoteDepot, 300));

        assertThatThrownBy(() -> validator.assertRouteWeightWithinVehicleCapacity(route))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("Capacity Exceeded");
    }

    /** A vehicle with no declared payload cannot be judged; planning proceeds rather than guessing. */
    @Test
    void aVehicleWithoutADeclaredPayloadIsNotJudged() {
        Route route = routeUnderTest();
        RouteStop a = deliveryStop(1);
        stubStops(a);
        stubVehicle(vehicle(null));
        stubDeliveries(delivery(a, homeDepot, 9_000));

        assertThatCode(() -> validator.assertRouteWeightWithinVehicleCapacity(route))
                .doesNotThrowAnyException();
    }

    @Test
    void aRouteWithoutAVehicleCannotBeWeighed() {
        Route route = routeUnderTest();
        route.setVehicleId(null);
        stubStops(deliveryStop(1));

        assertThatThrownBy(() -> validator.assertRouteWeightWithinVehicleCapacity(route))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("vehicle is required");
    }

    /**
     * Removed stops are history, not cargo. The filter happens before the database is queried, so
     * the assertion is on what the validator asks for: a cancelled stop's parcel is never even
     * fetched, and therefore cannot weigh on the van.
     */
    @Test
    void aRemovedStopIsNeitherFetchedNorWeighed() {
        Route route = routeUnderTest();
        RouteStop kept    = deliveryStop(1);
        RouteStop removed = deliveryStop(2);
        removed.setStatus(RouteStopStatus.REMOVED_CANCELLED);
        stubStops(kept, removed);
        stubVehicle(vehicle(400));
        stubDeliveries(delivery(kept, homeDepot, 300));

        assertThatCode(() -> validator.assertRouteWeightWithinVehicleCapacity(route))
                .doesNotThrowAnyException();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> asked = ArgumentCaptor.forClass(List.class);
        verify(deliveryRepository).findAllByIdInWithOrder(asked.capture());
        assertThat(asked.getValue())
                .containsExactly(kept.getDeliveryId())
                .doesNotContain(removed.getDeliveryId());
    }

    // ── Stop windows ────────────────────────────────────────────────────────────

    @Test
    void rejectsAWindowThatEndsBeforeItStarts() {
        List<CreateRouteRequest.StopConfig> stops =
                List.of(stopConfig(LocalTime.of(10, 0), LocalTime.of(9, 0)));

        assertThatThrownBy(() -> validator.validateStopChronology(stops, LocalTime.of(8, 0)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("End time must be after start time");
    }

    @Test
    void rejectsAWindowThatOpensBeforeThePreviousStopCloses() {
        List<CreateRouteRequest.StopConfig> stops = List.of(
                stopConfig(LocalTime.of(9, 0), LocalTime.of(11, 0)),
                stopConfig(LocalTime.of(10, 0), LocalTime.of(12, 0)));

        assertThatThrownBy(() -> validator.validateStopChronology(stops, LocalTime.of(8, 0)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("Stop #2");
    }

    @Test
    void rejectsAStopWithoutAWindow() {
        List<CreateRouteRequest.StopConfig> stops = List.of(stopConfig(null, null));

        assertThatThrownBy(() -> validator.validateStopChronology(stops, LocalTime.of(8, 0)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("required");
    }

    @Test
    void acceptsWindowsThatFollowOneAnother() {
        List<CreateRouteRequest.StopConfig> stops = List.of(
                stopConfig(LocalTime.of(9, 0), LocalTime.of(10, 0)),
                stopConfig(LocalTime.of(10, 0), LocalTime.of(11, 0)),
                stopConfig(LocalTime.of(11, 30), LocalTime.of(12, 0)));

        assertThatCode(() -> validator.validateStopChronology(stops, LocalTime.of(8, 0)))
                .doesNotThrowAnyException();
    }

    /** The first stop may not open before the round itself departs. */
    @Test
    void rejectsAFirstStopThatOpensBeforeTheRouteDeparts() {
        List<CreateRouteRequest.StopConfig> stops =
                List.of(stopConfig(LocalTime.of(7, 0), LocalTime.of(9, 0)));

        assertThatThrownBy(() -> validator.validateStopChronology(stops, LocalTime.of(8, 0)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("Stop #1");
    }

    @Test
    void rejectsAnUnparseableWindowOnUpdate() {
        var config = new com.asm.delivery.dto.request.UpdateRouteRequest.StopConfig();
        config.setStartTimeWindow("9h du matin");
        config.setEndTimeWindow("11:00");

        assertThatThrownBy(() -> validator.validateUpdateStopChronology(List.of(config), LocalTime.of(8, 0)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("Invalid time format");
    }

    // ── Builders ────────────────────────────────────────────────────────────────

    private DriverDTO driver(boolean active) {
        DriverDTO d = new DriverDTO();
        d.setId(driverId.toString());
        d.setActive(active);
        return d;
    }

    private Vehicle vehicle(Integer payloadKg) {
        Vehicle v = new Vehicle();
        v.setId(vehicleId);
        v.setPlate("TU-1234");
        v.setPayloadKg(payloadKg);
        v.setActive(true);
        v.setVehicleStatus(VehicleStatus.AVAILABLE);
        return v;
    }

    private Route routeUnderTest() {
        Route r = new Route();
        r.setId(routeId);
        r.setDate(day);
        r.setDriverId(driverId);
        r.setVehicleId(vehicleId);
        r.setDepotId(homeDepot);
        r.setStatus(RouteStatus.DRAFT);
        return r;
    }

    private Route existingRoute(RouteStatus status, LocalTime start, LocalTime end) {
        Route r = new Route();
        r.setId(UUID.randomUUID());
        r.setDate(day);
        r.setDriverId(driverId);
        r.setVehicleId(vehicleId);
        r.setStatus(status);
        r.setPlannedStartTime(start);
        r.setPlannedEndTime(end);
        return r;
    }

    private RouteStop deliveryStop(int order) {
        RouteStop s = new RouteStop();
        s.setId(UUID.randomUUID());
        s.setDeliveryId(UUID.randomUUID());
        s.setStopType(RouteStopType.DELIVERY);
        s.setStatus(RouteStopStatus.PENDING);
        s.setStopOrder(order);
        return s;
    }

    private RouteStop pickupStop(UUID depotId, int order) {
        RouteStop s = new RouteStop();
        s.setId(UUID.randomUUID());
        s.setStopType(RouteStopType.PICKUP);
        s.setSourceDepotId(depotId);
        s.setStatus(RouteStopStatus.PENDING);
        s.setStopOrder(order);
        return s;
    }

    private Delivery delivery(RouteStop stop, UUID sourceDepot, int weightKg) {
        Order order = new Order();
        order.setTotalWeightKg(BigDecimal.valueOf(weightKg));
        Delivery d = new Delivery();
        d.setId(stop.getDeliveryId());
        d.setSourceDepotId(sourceDepot);
        d.setOrder(order);
        return d;
    }

    private CreateRouteRequest.StopConfig stopConfig(LocalTime start, LocalTime end) {
        CreateRouteRequest.StopConfig c = new CreateRouteRequest.StopConfig();
        c.setStartTimeWindow(start);
        c.setEndTimeWindow(end);
        return c;
    }

    private void stubStops(RouteStop... stops) {
        when(routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId)).thenReturn(List.of(stops));
    }

    private void stubVehicle(Vehicle v) {
        when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.of(v));
    }

    private void stubDeliveries(Delivery... deliveries) {
        when(deliveryRepository.findAllByIdInWithOrder(anyList())).thenReturn(List.of(deliveries));
    }
}
