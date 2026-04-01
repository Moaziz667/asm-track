package com.asm.delivery.service;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Route;
import com.asm.delivery.entity.RouteStop;
import com.asm.delivery.entity.Vehicle;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.RouteRepository;
import com.asm.delivery.repository.RouteStopRepository;
import com.asm.delivery.repository.VehicleRepository;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RoutePdfService {

    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final DeliveryRepository deliveryRepository;
    private final VehicleRepository vehicleRepository;
    private final TransportPort transportPort;

    public byte[] generate(UUID routeId) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> AppException.notFound("Route not found"));

        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId);
        List<UUID> deliveryIds = stops.stream().map(RouteStop::getDeliveryId).toList();
        Map<UUID, Delivery> deliveriesById = new HashMap<>();
        if (!deliveryIds.isEmpty()) {
            for (Delivery d : deliveryRepository.findAllByIdInWithOrder(deliveryIds)) {
                deliveriesById.put(d.getId(), d);
            }
        }

        DriverDTO driver = transportPort.getDriver(route.getDriverId().toString());
        Vehicle vehicle = route.getVehicleId() != null ? vehicleRepository.findById(route.getVehicleId()).orElse(null) : null;

        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDPageContentStream stream = new PDPageContentStream(doc, page);
            float y = 800f;

            y = writeLine(stream, 16, true, 50, y, "Feuille de Route");
            y = writeLine(stream, 11, false, 50, y - 4, "Route: " + nullSafe(route.getName()));
            y = writeLine(stream, 10, false, 50, y - 2, "Date: " + (route.getDate() != null ? route.getDate().toString() : "-"));
            y = writeLine(stream, 10, false, 50, y - 2, "Plage: " + formatTime(route.getPlannedStartTime()) + " - " + formatTime(route.getPlannedEndTime()));
            y = writeLine(stream, 10, false, 50, y - 2, "Zone: " + nullSafe(route.getZone()) + " | Ville: " + nullSafe(route.getCity()));
            y = writeLine(stream, 10, false, 50, y - 2, "Driver: " + (driver != null ? driver.getName() : route.getDriverId()));
            y = writeLine(stream, 10, false, 50, y - 2, "Vehicule: " + (vehicle != null ? (nullSafe(vehicle.getName()) + " (" + nullSafe(vehicle.getPlate()) + ")") : "-"));

            y = writeLine(stream, 12, true, 50, y - 10, "Stops");

            for (RouteStop stop : stops) {
                Delivery delivery = deliveriesById.get(stop.getDeliveryId());
                String client = delivery != null && delivery.getOrder() != null ? nullSafe(delivery.getOrder().getClientName()) : "Client inconnu";
                String city = delivery != null && delivery.getOrder() != null ? nullSafe(delivery.getOrder().getDropoffCity()) : "-";
                String address = delivery != null && delivery.getOrder() != null ? nullSafe(delivery.getOrder().getDropoffAddress()) : "-";

                if (y < 90f) {
                    stream.close();
                    page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    stream = new PDPageContentStream(doc, page);
                    y = 800f;
                    y = writeLine(stream, 12, true, 50, y, "Stops (suite)");
                }

                y = writeLine(stream, 10, true, 50, y - 6,
                        "#" + stop.getStopOrder() + " - " + client + " [" + stop.getStatus().name() + "]");
                y = writeLine(stream, 9, false, 58, y - 2, "Delivery: " + stop.getDeliveryId());
                y = writeLine(stream, 9, false, 58, y - 2, "Ville: " + city);
                y = writeLine(stream, 9, false, 58, y - 2, "Adresse: " + address);
                if (stop.getArrivedAt() != null) {
                    y = writeLine(stream, 9, false, 58, y - 2, "Arrivee: " + stop.getArrivedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                }
                if (stop.getCompletedAt() != null) {
                    y = writeLine(stream, 9, false, 58, y - 2, "Fin: " + stop.getCompletedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                }
                y -= 2;
            }

            stream.close();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw AppException.serviceUnavailable("Failed to generate route PDF");
        }
    }

    private static float writeLine(PDPageContentStream stream, int size, boolean bold, float x, float y, String text) throws IOException {
        stream.beginText();
        stream.setFont(bold ? PDType1Font.HELVETICA_BOLD : PDType1Font.HELVETICA, size);
        stream.newLineAtOffset(x, y);
        stream.showText(text == null ? "" : text);
        stream.endText();
        return y - (size + 3f);
    }

    private static String formatTime(java.time.LocalTime value) {
        return value != null ? value.toString() : "-";
    }

    private static String nullSafe(Object value) {
        return value != null ? value.toString() : "-";
    }
}
