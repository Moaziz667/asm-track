package com.asm.delivery.service;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Route;
import com.asm.delivery.entity.RouteStop;
import com.asm.delivery.entity.Vehicle;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.CompanyRepository;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.storage.MinioStorageService;
import com.asm.delivery.repository.RouteRepository;
import com.asm.delivery.repository.RouteStopRepository;
import com.asm.delivery.repository.VehicleRepository;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RoutePdfService extends BasePdfService {

    private final RouteRepository        routeRepository;
    private final RouteStopRepository    routeStopRepository;
    private final DeliveryRepository     deliveryRepository;
    private final VehicleRepository      vehicleRepository;
    private final TransportPort          transportPort;
    private final CompanyRepository      companyRepository;
    private final MinioStorageService    minioStorageService;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    public byte[] generate(UUID routeId) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> AppException.notFound("Route not found"));

        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId);

        Map<UUID, Delivery> deliveriesById = new HashMap<>();
        for (Delivery d : deliveryRepository.findAllByIdInWithOrder(
                stops.stream().map(RouteStop::getDeliveryId).toList())) {
            deliveriesById.put(d.getId(), d);
        }

        DriverDTO driver  = route.getDriverId()  != null ? transportPort.getDriver(route.getDriverId().toString())      : null;
        Vehicle   vehicle = route.getVehicleId() != null ? vehicleRepository.findById(route.getVehicleId()).orElse(null) : null;

        long       totalStops = stops.size();
        BigDecimal totalCod   = stops.stream()
                .map(s -> deliveriesById.get(s.getDeliveryId()))
                .filter(d -> d != null && d.getOrder() != null
                        && Boolean.TRUE.equals(d.getOrder().getIsCod())
                        && d.getOrder().getTotalAmount() != null)
                .map(d -> d.getOrder().getTotalAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String subtitle = (route.getDate() != null ? route.getDate().format(DATE_FR) : "-")
                + "  ·  " + totalStops + " arrêts"
                + (totalCod.compareTo(BigDecimal.ZERO) > 0 ? "  ·  COD: " + totalCod.toPlainString() + " TND" : "");

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = newA4Document();
            PdfWriter pdfWriter = PdfWriter.getInstance(doc, out);

            ReportPageEvent event = pageEvent("FEUILLE DE ROUTE", subtitle,
                    route.getCompanyId(), companyRepository, minioStorageService);
            pdfWriter.setPageEvent(event);
            Color brand = event.getPrimaryColor();

            doc.open();

            // ── Document title ────────────────────────────────────────────────
            Paragraph title = new Paragraph("Feuille de Route", bold(18));
            title.setSpacingAfter(3f);
            doc.add(title);
            doc.add(new Paragraph(safe(route.getName()), muted(9)));

            // Brand divider
            PdfPTable divLine = new PdfPTable(1);
            divLine.setWidthPercentage(100);
            divLine.setSpacingBefore(8f);
            divLine.setSpacingAfter(10f);
            PdfPCell divCell = new PdfPCell(new Phrase(" "));
            divCell.setFixedHeight(2f);
            divCell.setBackgroundColor(brand);
            divCell.setBorder(Rectangle.NO_BORDER);
            divLine.addCell(divCell);
            doc.add(divLine);

            // ── Route info: 2 boxes side by side ─────────────────────────────
            PdfPTable infoRow = new PdfPTable(new float[]{1f, 1f});
            infoRow.setWidthPercentage(100);
            infoRow.setSpacingAfter(12f);

            infoRow.addCell(infoBox("TOURNÉE", new String[][]{
                {"Date",      route.getDate() != null ? route.getDate().format(DATE_FR) : "-"},
                {"Horaires",  fmt(route.getPlannedStartTime()) + " – " + fmt(route.getPlannedEndTime())},
                {"Ville",     safe(route.getCity())},
                {"Arrêts",    String.valueOf(totalStops)},
            }, brand));

            infoRow.addCell(infoBox("VÉHICULE & CHAUFFEUR", new String[][]{
                {"Chauffeur",  driver != null ? safe(driver.getName())   : "-"},
                {"Téléphone",  driver != null ? safe(driver.getPhone())  : "-"},
                {"Véhicule",   vehicle != null ? safe(vehicle.getName()) : "-"},
                {"Plaque",     vehicle != null ? safe(vehicle.getPlate()) : "-"},
            }, brand));

            doc.add(infoRow);

            // COD total highlight if applicable
            if (totalCod.compareTo(BigDecimal.ZERO) > 0) {
                doc.add(sectionLabel("TOTAL COD À ENCAISSER", brand));
                PdfPTable codRow = new PdfPTable(1);
                codRow.setWidthPercentage(40);
                codRow.setHorizontalAlignment(Element.ALIGN_LEFT);
                codRow.setSpacingAfter(8f);
                PdfPCell codCell = new PdfPCell(
                        new Phrase(totalCod.toPlainString() + " TND", colored(14, brand)));
                codCell.setPaddingTop(6f); codCell.setPaddingBottom(6f);
                codCell.setPaddingLeft(10f);
                codCell.setBorderColor(brand);
                codCell.setBorderWidth(1.5f);
                codCell.setBackgroundColor(tint(brand));
                codRow.addCell(codCell);
                doc.add(codRow);
            }

            // ── Stops table ───────────────────────────────────────────────────
            doc.add(sectionLabel("ARRÊTS", brand));

            PdfPTable table = new PdfPTable(new float[]{0.4f, 2.0f, 1.0f, 2.3f, 1.1f, 0.9f, 1.0f});
            table.setWidthPercentage(100);
            table.setHeaderRows(1);

            table.addCell(hdrCell("#", brand));
            table.addCell(hdrCell("Client", brand));
            table.addCell(hdrCell("Ville", brand));
            table.addCell(hdrCell("Adresse", brand));
            table.addCell(hdrCell("Créneau", brand));
            table.addCell(hdrCellR("COD (TND)", brand));
            table.addCell(hdrCell("Statut", brand));

            boolean alt = false;
            for (RouteStop stop : stops) {
                Delivery d    = deliveriesById.get(stop.getDeliveryId());
                String client  = d != null && d.getOrder() != null ? safe(d.getOrder().getClientName())     : "-";
                String city    = d != null && d.getOrder() != null ? safe(d.getOrder().getDropoffCity())    : "-";
                String address = d != null && d.getOrder() != null ? safe(d.getOrder().getDropoffAddress()) : "-";
                String cod     = d != null && d.getOrder() != null
                               && Boolean.TRUE.equals(d.getOrder().getIsCod())
                               && d.getOrder().getTotalAmount() != null
                               ? d.getOrder().getTotalAmount().toPlainString() : "-";

                table.addCell(cellAlt(String.valueOf(stop.getStopOrder()), alt));
                table.addCell(cellAlt(client,  alt));
                table.addCell(cellAlt(city,    alt));
                table.addCell(cellAlt(address, alt));
                table.addCell(cellAlt(buildWindow(stop), alt));
                table.addCell(cellRAlt(cod,    alt));
                table.addCell(cellAlt(statusLabel(stop), alt));
                alt = !alt;
            }
            doc.add(table);

            // ── Signature ─────────────────────────────────────────────────────
            doc.add(sectionLabel("SIGNATURE DU CHAUFFEUR", brand));
            PdfPTable sig = new PdfPTable(new float[]{1.2f, 2f});
            sig.setWidthPercentage(55);
            sig.setHorizontalAlignment(Element.ALIGN_LEFT);

            PdfPCell sigLbl = new PdfPCell(new Phrase("Nom et signature :", muted(8)));
            sigLbl.setPaddingTop(6f); sigLbl.setPaddingBottom(28f); sigLbl.setPaddingLeft(6f);
            sigLbl.setBorderColor(BORDER_GRAY);
            sigLbl.setBorderWidthTop(2f); sigLbl.setBorderColorTop(brand);

            PdfPCell sigBox = new PdfPCell(new Phrase(" "));
            sigBox.setPaddingBottom(28f);
            sigBox.setBorderColor(BORDER_GRAY);
            sigBox.setBorderWidthTop(2f); sigBox.setBorderColorTop(brand);

            sig.addCell(sigLbl);
            sig.addCell(sigBox);
            doc.add(sig);

            doc.close();
            return out.toByteArray();

        } catch (Exception e) {
            throw AppException.serviceUnavailable("Erreur génération PDF tournée: " + e.getMessage());
        }
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
            case "PENDING"            -> "En attente";
            case "SCHEDULED"          -> "Planifié";
            case "ASSIGNED"           -> "Assigné";
            case "PICKED_UP"          -> "Ramassé";
            case "IN_TRANSIT"         -> "En transit";
            case "ARRIVED"            -> "Arrivé";
            case "COMPLETED"          -> "Livré";
            case "FAILED"             -> "Échoué";
            case "PARTIAL"            -> "Partiel";
            case "REMOVED_REPLANNED"  -> "Replanifié";
            case "REMOVED_CANCELLED"  -> "Retiré";
            default                   -> stop.getStatus().name();
        };
    }
}
