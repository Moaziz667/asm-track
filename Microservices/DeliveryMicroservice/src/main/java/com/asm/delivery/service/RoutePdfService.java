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
            float pageW = PDRectangle.A4.getWidth();
            float margin = 50f;

            // ── Branded header bar ────────────────────────────────────────────
            stream.setNonStrokingColor(1.0f, 0.341f, 0.133f);
            stream.addRect(0, 800f, pageW, 42f);
            stream.fill();

            stream.setNonStrokingColor(1f, 1f, 1f);
            writeLine(stream, 14, true, margin, 822f, "ASM Track");
            writeLine(stream, 11, true, 350f, 822f, "FEUILLE DE ROUTE");

            stream.setNonStrokingColor(0.929f, 0.231f, 0.031f);
            stream.addRect(0, 798f, pageW, 2f);
            stream.fill();
            stream.setNonStrokingColor(0f, 0f, 0f);

            float y = 778f;

            // Route metadata block
            y = writeLine(stream, 11, true, margin, y, sanitize(nullSafe(route.getName())));
            y = writeLine(stream, 9, false, margin, y - 2,
                    "Date : " + (route.getDate() != null ? route.getDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) : "-")
                    + "   Horaires : " + formatTime(route.getPlannedStartTime()) + " - " + formatTime(route.getPlannedEndTime())
                    + "   Ville : " + sanitize(nullSafe(route.getCity())));
            y = writeLine(stream, 9, false, margin, y - 2,
                    "Chauffeur : " + (driver != null ? sanitize(driver.getName()) : "-")
                    + "   Vehicule : " + (vehicle != null ? sanitize(nullSafe(vehicle.getName()) + " (" + nullSafe(vehicle.getPlate()) + ")") : "-"));
            y -= 10;

            // "ARRETS" section label
            stream.setNonStrokingColor(1.0f, 0.341f, 0.133f);
            y = writeLine(stream, 10, true, margin, y, "ARRETS");
            stream.setNonStrokingColor(0f, 0f, 0f);

            // Header row background
            stream.setNonStrokingColor(0.96f, 0.96f, 0.96f);
            stream.addRect(margin, y - 13f, pageW - 2 * margin, 13f);
            stream.fill();
            stream.setNonStrokingColor(0f, 0f, 0f);
            y = writeLine(stream, 9, true, margin, y - 2, "#   Client                           Ville             Statut");

            boolean odd = false;
            for (RouteStop stop : stops) {
                Delivery delivery = deliveriesById.get(stop.getDeliveryId());
                String client  = delivery != null && delivery.getOrder() != null ? sanitize(delivery.getOrder().getClientName()) : "Client inconnu";
                String city    = delivery != null && delivery.getOrder() != null ? sanitize(delivery.getOrder().getDropoffCity()) : "-";
                String address = delivery != null && delivery.getOrder() != null ? sanitize(delivery.getOrder().getDropoffAddress()) : "-";
                String statusLabel = stop.getStatus() != null ? stop.getStatus().name() : "-";

                if (y < 90f) {
                    stream.close();
                    page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    stream = new PDPageContentStream(doc, page);
                    y = 800f;
                    stream.setNonStrokingColor(1.0f, 0.341f, 0.133f);
                    y = writeLine(stream, 10, true, margin, y, "ARRETS (suite)");
                    stream.setNonStrokingColor(0f, 0f, 0f);
                    odd = false;
                }

                // Alternate row shading
                if (odd) {
                    stream.setNonStrokingColor(0.98f, 0.98f, 0.98f);
                    stream.addRect(margin, y - 36f, pageW - 2 * margin, 36f);
                    stream.fill();
                    stream.setNonStrokingColor(0f, 0f, 0f);
                }
                odd = !odd;

                y = writeLine(stream, 10, true, margin, y - 6,
                        padR(String.valueOf(stop.getStopOrder()), 4)
                        + padR(client, 33)
                        + padR(city, 18)
                        + statusLabel);
                y = writeLine(stream, 8, false, margin + 12, y - 2, address);
                if (stop.getArrivedAt() != null) {
                    y = writeLine(stream, 8, false, margin + 12, y - 1,
                            "Arrivee : " + stop.getArrivedAt().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                            + (stop.getCompletedAt() != null ? "   Termine : " + stop.getCompletedAt().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) : ""));
                } else {
                    y -= (8 + 3f);
                }
                y -= 3;
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

    private static String sanitize(String s) {
        if (s == null) return "-";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            sb.append(c < 256 ? c : '?');
        }
        return sb.toString();
    }

    private static String padR(String s, int n) {
        if (s == null) s = "";
        if (s.length() > n) return s.substring(0, n);
        return String.format("%-" + n + "s", s);
    }
}
