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
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class RoutePdfService extends BasePdfService {

    private final RouteRepository        routeRepository;
    private final RouteStopRepository    routeStopRepository;
    private final DeliveryRepository     deliveryRepository;
    private final VehicleRepository      vehicleRepository;
    private final TransportPort          transportPort;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    public byte[] generate(UUID routeId) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> AppException.notFound("Route not found"));

        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId);

        Map<UUID, Delivery> deliveriesById = new HashMap<>();
        List<UUID> deliveryIds = stops.stream().map(RouteStop::getDeliveryId).toList();
        if (!deliveryIds.isEmpty()) {
            for (Delivery d : deliveryRepository.findAllByIdInWithOrder(deliveryIds)) {
                deliveriesById.put(d.getId(), d);
            }
        }

        DriverDTO driver  = route.getDriverId() != null ? transportPort.getDriver(route.getDriverId().toString()) : null;
        Vehicle   vehicle = route.getVehicleId() != null ? vehicleRepository.findById(route.getVehicleId()).orElse(null) : null;

        // Compute totals for header summary
        long totalStops = stops.size();
        BigDecimal totalCod = stops.stream()
                .map(s -> deliveriesById.get(s.getDeliveryId()))
                .filter(d -> d != null && d.getOrder() != null && d.getOrder().getTotalAmount() != null)
                .map(d -> d.getOrder().getTotalAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String subtitle = (route.getDate() != null ? route.getDate().format(DATE_FR) : "-")
                + " · " + totalStops + " arrêts"
                + (totalCod.compareTo(BigDecimal.ZERO) > 0 ? " · COD total: " + totalCod.toPlainString() + " TND" : "");

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = newA4Document();
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new ReportPageEvent("FEUILLE DE ROUTE", subtitle));
            doc.open();

            // ── Route info block ──────────────────────────────────────────────
            doc.add(sectionLabel("INFORMATIONS DE LA TOURNÉE"));

            PdfPTable infoTable = new PdfPTable(new float[]{1, 2, 1, 2});
            infoTable.setWidthPercentage(100);
            infoTable.setSpacingAfter(10f);

            addInfoRow(infoTable, "Tournée",   safe(route.getName()));
            addInfoRow(infoTable, "Date",      route.getDate() != null ? route.getDate().format(DATE_FR) : "-");
            addInfoRow(infoTable, "Horaires",  fmt(route.getPlannedStartTime()) + " – " + fmt(route.getPlannedEndTime()));
            addInfoRow(infoTable, "Ville",     safe(route.getCity()));
            addInfoRow(infoTable, "Chauffeur", driver != null ? safe(driver.getName()) : "-");
            addInfoRow(infoTable, "Téléphone", driver != null ? safe(driver.getPhone()) : "-");
            addInfoRow(infoTable, "Véhicule",  vehicle != null ? safe(vehicle.getName()) + " · " + safe(vehicle.getPlate()) : "-");
            addInfoRow(infoTable, "Arrêts",    totalStops + (totalCod.compareTo(BigDecimal.ZERO) > 0 ? "   |   COD: " + totalCod.toPlainString() + " TND" : ""));

            doc.add(infoTable);

            // ── Stops table ───────────────────────────────────────────────────
            doc.add(sectionLabel("ARRÊTS"));

            PdfPTable table = new PdfPTable(new float[]{0.4f, 2.2f, 1.2f, 2.5f, 1.1f, 0.8f, 1.0f});
            table.setWidthPercentage(100);
            table.setHeaderRows(1);

            table.addCell(hdrCell("#"));
            table.addCell(hdrCell("Client"));
            table.addCell(hdrCell("Ville"));
            table.addCell(hdrCell("Adresse"));
            table.addCell(hdrCell("Créneau"));
            table.addCell(hdrCellR("COD (TND)"));
            table.addCell(hdrCell("Statut"));

            boolean alt = false;
            for (RouteStop stop : stops) {
                Delivery  d     = deliveriesById.get(stop.getDeliveryId());
                String client   = d != null && d.getOrder() != null ? safe(d.getOrder().getClientName())    : "-";
                String city     = d != null && d.getOrder() != null ? safe(d.getOrder().getDropoffCity())   : "-";
                String address  = d != null && d.getOrder() != null ? safe(d.getOrder().getDropoffAddress()): "-";
                String cod      = d != null && d.getOrder() != null && d.getOrder().getTotalAmount() != null
                                    ? d.getOrder().getTotalAmount().toPlainString() : "-";
                String creneau  = buildWindow(stop);
                String statut   = statusLabel(stop);

                table.addCell(cellAlt(String.valueOf(stop.getStopOrder()), alt));
                table.addCell(cellAlt(client, alt));
                table.addCell(cellAlt(city, alt));
                table.addCell(cellAlt(address, alt));
                table.addCell(cellAlt(creneau, alt));
                table.addCell(cellRAlt(cod, alt));
                table.addCell(cellAlt(statut, alt));
                alt = !alt;
            }

            doc.add(table);

            // ── Signature block ───────────────────────────────────────────────
            doc.add(sectionLabel("SIGNATURE DU CHAUFFEUR"));
            PdfPTable sig = new PdfPTable(new float[]{1, 2});
            sig.setWidthPercentage(60);
            sig.setHorizontalAlignment(Element.ALIGN_LEFT);
            PdfPCell sigLabel = new PdfPCell(new Phrase("Nom et signature :", regular(8)));
            sigLabel.setPadding(5f);
            sigLabel.setBorderColor(BORDER_GRAY);
            PdfPCell sigBox = new PdfPCell(new Phrase(" \n\n ", regular(8)));
            sigBox.setPadding(5f);
            sigBox.setBorderColor(BORDER_GRAY);
            sig.addCell(sigLabel);
            sig.addCell(sigBox);
            doc.add(sig);

            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw AppException.serviceUnavailable("Erreur génération PDF tournée: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void addInfoRow(PdfPTable t, String label, String value) {
        PdfPCell lbl = new PdfPCell(new Phrase(label, muted(8)));
        lbl.setPadding(4f); lbl.setBorderColor(BORDER_GRAY);
        PdfPCell val = new PdfPCell(new Phrase(safe(value), regular(8)));
        val.setPadding(4f); val.setBorderColor(BORDER_GRAY);
        t.addCell(lbl);
        t.addCell(val);
    }

    private static String fmt(java.time.LocalTime t) {
        return t != null ? t.format(TIME_FMT) : "-";
    }

    private static String buildWindow(RouteStop stop) {
        if (stop.getStartTimeWindow() != null && stop.getEndTimeWindow() != null) {
            return stop.getStartTimeWindow().format(TIME_FMT) + "–" + stop.getEndTimeWindow().format(TIME_FMT);
        }
        return "-";
    }

    private static String statusLabel(RouteStop stop) {
        if (stop.getStatus() == null) return "-";
        return switch (stop.getStatus().name()) {
            case "COMPLETED"  -> "Livré";
            case "FAILED"     -> "Échoué";
            case "IN_PROGRESS"-> "En cours";
            case "PENDING"    -> "En attente";
            default           -> stop.getStatus().name();
        };
    }
}
