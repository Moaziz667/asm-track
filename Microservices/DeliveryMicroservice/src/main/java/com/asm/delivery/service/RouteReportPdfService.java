package com.asm.delivery.service;

import com.asm.delivery.dto.response.RouteReportResponse;
import com.asm.delivery.entity.Route;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.RouteRepository;
import com.asm.delivery.service.route.RouteReportService;
import com.asm.delivery.storage.MinioStorageService;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Server-side PDF for the closure report (rapport de tournée).
 * Reuses BasePdfService helpers and the company-branded header/footer used by the
 * existing AnalyticsPdfService / RoutePdfService.
 */
@Service
@RequiredArgsConstructor
public class RouteReportPdfService extends BasePdfService {

    private final RouteRepository routeRepository;
    private final RouteReportService routeReportService;
    private final CompanyBrandingResolver brandingResolver;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    public byte[] generate(UUID routeId) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> AppException.notFound("Route not found"));
        RouteReportResponse r = routeReportService.load(route);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = newA4Document();
            PdfWriter writer = PdfWriter.getInstance(doc, out);

            String subtitle = r.getHeader().getDate() != null
                    ? r.getHeader().getDate().format(DATE_FR) : "-";
            if (r.getHeader().getDriverName() != null) subtitle += "  ·  " + r.getHeader().getDriverName();
            if (r.getKpis() != null) subtitle += "  ·  " + r.getKpis().getAttemptedStops() + " arrêts";

            ReportPageEvent event = brandingResolver.resolve("RAPPORT DE TOURNÉE", subtitle);
            writer.setPageEvent(event);
            Color brand = event.getPrimaryColor();

            doc.open();

            // Title
            Paragraph title = new Paragraph("Rapport de Tournée", bold(18));
            title.setSpacingAfter(3f);
            doc.add(title);
            doc.add(new Paragraph(safe(r.getHeader().getRouteName()), muted(9)));

            doc.add(divider());

            // ── Info boxes ────────────────────────────────────────────────────
            PdfPTable infoRow = new PdfPTable(new float[]{1f, 1f});
            infoRow.setWidthPercentage(100);
            infoRow.setSpacingAfter(12f);

            infoRow.addCell(infoBox("TOURNÉE", new String[][]{
                    {"Nom",         safe(r.getHeader().getRouteName())},
                    {"Date",        r.getHeader().getDate() != null ? r.getHeader().getDate().format(DATE_FR) : "-"},
                    {"Démarrée à",  r.getHeader().getStartedAt() != null ? r.getHeader().getStartedAt().format(DT_FR) : "-"},
                    {"Clôturée à",  r.getHeader().getClosedAt() != null ? r.getHeader().getClosedAt().format(DT_FR) : "-"},
                    {"Durée",       fmtMinutes(r.getHeader().getDurationMinutes())},
            }, brand));

            infoRow.addCell(infoBox("CHAUFFEUR & VÉHICULE", new String[][]{
                    {"Chauffeur",   safe(r.getHeader().getDriverName())},
                    {"Véhicule",    safe(r.getHeader().getVehicleType())},
                    {"Plaque",      safe(r.getHeader().getVehiclePlate())},
                    {"Dépôt",       safe(r.getHeader().getDepotName())},
                    {"Statut",      "Clôturée"},
            }, brand));

            doc.add(infoRow);

            // ── KPI grid (3×3) ────────────────────────────────────────────────
            doc.add(sectionLabel("INDICATEURS CLÉS", brand));
            var k = r.getKpis();
            PdfPTable kpiTable = new PdfPTable(3);
            kpiTable.setWidthPercentage(100);
            kpiTable.setSpacingAfter(12f);

            kpiTable.addCell(kpiBox("Taux de complétion",
                    fmt(k.getCompletionRate()) + " %", brand));
            kpiTable.addCell(kpiBox("Taux de ponctualité",
                    fmt(k.getOnTimeRate()) + " %", brand));
            kpiTable.addCell(kpiBox("Distance totale",
                    k.getTotalDistanceKm() != null ? fmt(k.getTotalDistanceKm()) + " km" : "-", brand));

            kpiTable.addCell(kpiBox("Durée active",
                    fmtMinutes(k.getActiveDurationMinutes()), brand));
            kpiTable.addCell(kpiBox("Retard cumulé",
                    k.getCumulativeDelayMinutes() != null ? fmtMinutes(k.getCumulativeDelayMinutes()) : "0m", brand));
            kpiTable.addCell(kpiBox("Retard démarrage",
                    fmtMinutes(k.getRouteStartDelayMinutes()), brand));

            kpiTable.addCell(kpiBox("Arrêts tentés",
                    k.getAttemptedStops() + " / " + k.getTotalStopsPlanned(), brand));
            kpiTable.addCell(kpiBox("Échecs",
                    String.valueOf(k.getFailedStops() + k.getFailedAttemptStops()), brand));
            kpiTable.addCell(kpiBox("Retirés",
                    String.valueOf(k.getReplannedStops() + k.getCancelledStopsCount()), brand));

            doc.add(kpiTable);

            // ── Status breakdown ──────────────────────────────────────────────
            doc.add(sectionLabel("RÉPARTITION DES ARRÊTS", brand));
            PdfPTable breakdown = new PdfPTable(new float[]{2f, 1f, 1f});
            breakdown.setWidthPercentage(60);
            breakdown.setHorizontalAlignment(Element.ALIGN_LEFT);
            breakdown.setSpacingAfter(12f);
            breakdown.addCell(hdrCell("Catégorie", brand));
            breakdown.addCell(hdrCellR("Nombre", brand));
            breakdown.addCell(hdrCellR("Pourcentage", brand));
            boolean alt = false;
            for (var b : r.getStatusBreakdown()) {
                breakdown.addCell(cellAlt(safe(b.getLabel()), alt));
                breakdown.addCell(cellRAlt(String.valueOf(b.getCount()), alt));
                breakdown.addCell(cellRAlt(b.getPercentage() + " %", alt));
                alt = !alt;
            }
            doc.add(breakdown);

            // ── Stops table ───────────────────────────────────────────────────
            doc.add(sectionLabel("DÉTAIL DES ARRÊTS", brand));
            PdfPTable stopsTbl = new PdfPTable(new float[]{0.3f, 1.4f, 1.7f, 0.85f, 0.7f, 0.6f, 1.25f, 0.45f});
            stopsTbl.setWidthPercentage(100);
            stopsTbl.setHeaderRows(1);
            stopsTbl.addCell(hdrCell("#", brand));
            stopsTbl.addCell(hdrCell("Client", brand));
            stopsTbl.addCell(hdrCell("Adresse", brand));
            stopsTbl.addCell(hdrCell("Créneau", brand));
            stopsTbl.addCell(hdrCell("Réalisé", brand));
            stopsTbl.addCell(hdrCellR("Retard", brand));
            stopsTbl.addCell(hdrCell("Statut / Motif", brand));
            stopsTbl.addCell(hdrCellR("POD", brand));

            alt = false;
            for (var s : r.getStops()) {
                // Pickup (multi-depot load) stops have no delivery — label them "Chargement", never "Livré".
                boolean pickup = s.getDeliveryId() == null;
                String addr = pickup ? "—"
                        : safe(s.getAddress()) + (s.getCity() != null ? ", " + s.getCity() : "");
                // Failure rows carry the motif so the closure document says WHY, not just "Échec".
                String statut = pickup ? "Chargement"
                        : outcomeLabel(s.getFinalStatus())
                          + ("FAILED".equals(s.getFinalStatus()) && s.getFailReason() != null ? " · " + s.getFailReason() : "");
                stopsTbl.addCell(cellAlt(String.valueOf(s.getStopOrder()), alt));
                stopsTbl.addCell(cellAlt(pickup ? "Chargement" : safe(s.getClientName()), alt));
                stopsTbl.addCell(cellAlt(addr, alt));
                stopsTbl.addCell(cellAlt(buildWindow(s), alt));
                stopsTbl.addCell(cellAlt(s.getCompletedAt() != null
                        ? s.getCompletedAt().format(TIME_FMT) : "—", alt));
                stopsTbl.addCell(cellRAlt(s.getDelayMinutes() != null
                        ? fmtMinutes(s.getDelayMinutes()) : "—", alt));
                stopsTbl.addCell(cellAlt(statut, alt));
                stopsTbl.addCell(cellRAlt(pickup ? "—" : (s.isHasPod() ? "Oui" : "—"), alt));
                alt = !alt;
            }
            doc.add(stopsTbl);

            // ── Movements ─────────────────────────────────────────────────────
            if (r.getMovements() != null && !r.getMovements().isEmpty()) {
                doc.add(sectionLabel("MOUVEMENTS ET EXCEPTIONS", brand));
                PdfPTable mv = new PdfPTable(new float[]{1f, 1.3f, 4f});
                mv.setWidthPercentage(100);
                mv.setHeaderRows(1);
                mv.addCell(hdrCell("Heure", brand));
                mv.addCell(hdrCell("Type", brand));
                mv.addCell(hdrCell("Détail", brand));
                alt = false;
                for (var m : r.getMovements()) {
                    mv.addCell(cellAlt(m.getAt() != null ? m.getAt().format(DT_FR) : "-", alt));
                    mv.addCell(cellAlt(movementLabel(m.getType()), alt));
                    mv.addCell(cellAlt(safe(m.getDetail()), alt));
                    alt = !alt;
                }
                doc.add(mv);
            }

            // ── Proof appendix — signatures / photos per delivered stop ───────
            if (r.getPodGallery() != null && !r.getPodGallery().isEmpty()) {
                PdfPTable proofTbl = new PdfPTable(3);
                proofTbl.setWidthPercentage(100);
                proofTbl.setSpacingBefore(4f);
                int shots = 0;
                for (var p : r.getPodGallery()) {
                    String caption = "#" + p.getStopOrder() + " · " + safe(p.getClientName());
                    for (String url : new String[]{p.getPhotoUrl(), p.getSignatureUrl(), p.getBonLivraisonUrl()}) {
                        if (url == null || url.isBlank()) continue;
                        try {
                            Image img = Image.getInstance(new java.net.URL(url));
                            img.scaleToFit(150f, 150f);
                            PdfPCell cell = new PdfPCell();
                            cell.setBorderColor(BORDER_GRAY);
                            cell.setPadding(5f);
                            cell.addElement(img);
                            cell.addElement(new Paragraph(caption, muted(7)));
                            proofTbl.addCell(cell);
                            shots++;
                        } catch (Exception ignore) { /* skip an unreadable proof, don't fail the PDF */ }
                    }
                }
                if (shots > 0) {
                    while (shots % 3 != 0) { proofTbl.addCell(noBorderCell()); shots++; }
                    doc.add(sectionLabel("PREUVES DE LIVRAISON", brand));
                    doc.add(proofTbl);
                }
            }

            // ── Footer note ───────────────────────────────────────────────────
            Paragraph footer = new Paragraph(
                    "Généré le " + (r.getGeneratedAt() != null ? r.getGeneratedAt().format(DT_FR) : "-")
                            + " · ASM Track", muted(8));
            footer.setSpacingBefore(16f);
            doc.add(footer);

            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw AppException.serviceUnavailable("Erreur génération PDF rapport: " + e.getMessage());
        }
    }

    private static String fmt(java.math.BigDecimal n) {
        return n != null ? n.toPlainString() : "-";
    }

    /** Duration/delay in minutes → "5h 33m" / "33m" / "-12m" (signed: negative = early). */
    private static String fmtMinutes(Integer mins) {
        if (mins == null) return "-";
        int a = Math.abs(mins);
        int h = a / 60, m = a % 60;
        String sign = mins < 0 ? "-" : "";
        return h > 0 ? sign + h + "h " + m + "m" : sign + m + "m";
    }

    private static String buildWindow(RouteReportResponse.StopRow s) {
        if (s.getStartTimeWindow() != null && s.getEndTimeWindow() != null) {
            return s.getStartTimeWindow().format(TIME_FMT) + "–" + s.getEndTimeWindow().format(TIME_FMT);
        }
        return "-";
    }

    /** Outcome column: delivery result regardless of timing. */
    private static String outcomeLabel(String finalStatus) {
        if ("COMPLETED".equals(finalStatus))         return "Livré";
        if ("PARTIAL".equals(finalStatus))           return "Partielle";
        if ("FAILED".equals(finalStatus))            return "Échec";
        if ("FAILED_ATTEMPT".equals(finalStatus))    return "Tenté";
        if ("REMOVED_REPLANNED".equals(finalStatus)) return "Replanifié";
        if ("REMOVED_CANCELLED".equals(finalStatus)) return "Annulé";
        return finalStatus != null ? finalStatus : "—";
    }

    /** Ponctualité column: timing relative to time window (COMPLETED/PARTIAL stops only). */
    private static String timingLabel(String finalStatus, String classification, Integer delayMinutes) {
        if (!"COMPLETED".equals(finalStatus) && !"PARTIAL".equals(finalStatus)) return "—";
        if ("ON_TIME".equals(classification))  return "À l'heure";
        if ("LATE".equals(classification))     return "En retard";
        if ("EARLY".equals(classification))    return "En avance";
        // Fallback for stale cached reports where classification is null
        if (delayMinutes == null)   return "À l'heure";
        if (delayMinutes > 0)       return "En retard";
        if (delayMinutes < -5)      return "En avance";
        return "À l'heure";
    }

    private static String movementLabel(String type) {
        if (type == null) return "—";
        return switch (type) {
            case "STOP_REMOVED_REPLANNED" -> "Replanifié";
            case "STOP_REMOVED_CANCELLED" -> "Annulé";
            case "HANDOFF_CONFIRMED"      -> "Transfert";
            case "STOP_FAILED"            -> "Échec";
            case "DELIVERY_CANCELLED"     -> "Annulation";
            default                        -> type;
        };
    }
}
