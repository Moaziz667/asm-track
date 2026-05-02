package com.asm.delivery.service;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import lombok.RequiredArgsConstructor;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.renderer.category.StandardBarPainter;
import org.jfree.data.category.DefaultCategoryDataset;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;

@Service
@RequiredArgsConstructor
public class DriverPerformancePdfService extends BasePdfService {

    private final DeliveryRepository deliveryRepository;
    private final TransportPort      transportPort;

    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("dd/MM");

    public byte[] generate(UUID driverId, String period, LocalDate from, LocalDate to) {
        DriverDTO driver = transportPort.getDriver(driverId.toString());
        if (driver == null) throw AppException.notFound("Chauffeur introuvable: " + driverId);

        LocalDateTime now   = LocalDateTime.now();
        LocalDateTime start = resolveStart(period, from, now);
        LocalDateTime end   = to != null ? to.atTime(23, 59, 59) : now;

        // Driver deliveries in period
        List<Delivery> allStatuses = List.of(
                DeliveryStatus.DELIVERED, DeliveryStatus.PARTIALLY_DELIVERED,
                DeliveryStatus.FAILED, DeliveryStatus.CANCELLED,
                DeliveryStatus.SCHEDULED, DeliveryStatus.IN_TRANSIT,
                DeliveryStatus.PICKED_UP, DeliveryStatus.UNSCHEDULED);

        List<Delivery> history = deliveryRepository.findHistoryForDriver(driverId,
                List.of(DeliveryStatus.DELIVERED, DeliveryStatus.PARTIALLY_DELIVERED,
                        DeliveryStatus.FAILED, DeliveryStatus.CANCELLED));
        List<Delivery> active  = deliveryRepository.findActiveForDriver(driverId,
                List.of(DeliveryStatus.SCHEDULED, DeliveryStatus.IN_TRANSIT, DeliveryStatus.PICKED_UP));

        List<Delivery> combined = new ArrayList<>();
        combined.addAll(history);
        combined.addAll(active);

        List<Delivery> filtered = combined.stream()
                .filter(d -> {
                    LocalDateTime ref = d.getCompletedAt() != null ? d.getCompletedAt() : d.getCreatedAt();
                    return ref != null && !ref.isBefore(start) && !ref.isAfter(end);
                })
                .collect(Collectors.toList());

        // KPI computation
        long total     = filtered.size();
        long delivered = filtered.stream().filter(d -> d.getStatus() == DeliveryStatus.DELIVERED || d.getStatus() == DeliveryStatus.PARTIALLY_DELIVERED).count();
        long failed    = filtered.stream().filter(d -> d.getStatus() == DeliveryStatus.FAILED).count();
        double successRate  = total == 0 ? 0 : (double) delivered / total * 100.0;

        double avgDelay = filtered.stream()
                .filter(d -> d.getCompletedAt() != null && d.getRouteEtaAt() != null)
                .mapToLong(d -> Math.max(0, java.time.Duration.between(d.getRouteEtaAt(), d.getCompletedAt()).toMinutes()))
                .average().orElse(0.0);

        // Fleet average success rate for comparison
        List<Delivery> allDeliveries = deliveryRepository.findAll();
        List<Delivery> fleetFiltered = allDeliveries.stream()
                .filter(d -> {
                    LocalDateTime ref = d.getCompletedAt() != null ? d.getCompletedAt() : d.getCreatedAt();
                    return ref != null && !ref.isBefore(start) && !ref.isAfter(end);
                }).toList();
        long fleetTotal     = fleetFiltered.size();
        long fleetDelivered = fleetFiltered.stream().filter(d -> d.getStatus() == DeliveryStatus.DELIVERED || d.getStatus() == DeliveryStatus.PARTIALLY_DELIVERED).count();
        double fleetRate    = fleetTotal == 0 ? 0 : (double) fleetDelivered / fleetTotal * 100.0;

        // 7-day daily volume for trend chart
        Map<String, Long> dailyVolume = new TreeMap<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate day = LocalDate.now().minusDays(i);
            dailyVolume.put(day.format(SHORT_DATE), 0L);
        }
        filtered.stream()
                .filter(d -> d.getCreatedAt() != null && d.getCreatedAt().isAfter(now.minusDays(7)))
                .forEach(d -> dailyVolume.merge(d.getCreatedAt().toLocalDate().format(SHORT_DATE), 1L, Long::sum));

        String periodLabel = buildPeriodLabel(period, from, start, end);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document  doc    = newA4Document();
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new ReportPageEvent("PERFORMANCE CHAUFFEUR", safe(driver.getName())));
            doc.open();

            // ── Driver identity block ─────────────────────────────────────────
            PdfPTable identity = new PdfPTable(new float[]{1, 1});
            identity.setWidthPercentage(100);
            identity.setSpacingAfter(8f);

            PdfPCell nameCell = new PdfPCell();
            nameCell.setBorder(Rectangle.NO_BORDER);
            nameCell.addElement(new Paragraph(safe(driver.getName()), bold(14)));
            nameCell.addElement(new Paragraph("Tél. : " + safe(driver.getPhone()), regular(9)));
            nameCell.addElement(new Paragraph("Période : " + periodLabel, muted(8)));

            PdfPCell fleetCell = new PdfPCell();
            fleetCell.setBorder(Rectangle.NO_BORDER);
            fleetCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            fleetCell.addElement(buildComparisonBar(successRate, fleetRate));

            identity.addCell(nameCell);
            identity.addCell(fleetCell);
            doc.add(identity);

            // ── KPI grid ──────────────────────────────────────────────────────
            doc.add(sectionLabel("INDICATEURS CLÉS"));
            PdfPTable kpiGrid = new PdfPTable(new float[]{1, 1, 1, 1, 1});
            kpiGrid.setWidthPercentage(100);
            kpiGrid.setSpacingAfter(12f);
            kpiGrid.addCell(wrapKpi(kpiBox("VOLUME",         String.valueOf(total))));
            kpiGrid.addCell(wrapKpi(kpiBox("LIVRÉES",        String.valueOf(delivered))));
            kpiGrid.addCell(wrapKpi(kpiBox("ÉCHOUÉES",       String.valueOf(failed))));
            kpiGrid.addCell(wrapKpi(kpiBox("TAUX SUCCÈS",    String.format("%.1f%%", successRate))));
            kpiGrid.addCell(wrapKpi(kpiBox("RETARD MOY.",    fmtDuration(avgDelay))));
            doc.add(kpiGrid);

            // ── 7-day trend chart ─────────────────────────────────────────────
            doc.add(sectionLabel("VOLUME (7 DERNIERS JOURS)"));
            byte[] chartPng = buildTrendChart(dailyVolume);
            if (chartPng != null) {
                Image chartImg = Image.getInstance(chartPng);
                chartImg.setWidthPercentage(100);
                chartImg.setSpacingAfter(12f);
                doc.add(chartImg);
            }

            // ── Delivery history table ────────────────────────────────────────
            doc.add(sectionLabel("HISTORIQUE DES LIVRAISONS"));
            PdfPTable histTable = new PdfPTable(new float[]{1.2f, 2.5f, 1.5f, 1f, 1.2f});
            histTable.setWidthPercentage(100);
            histTable.setHeaderRows(1);
            histTable.addCell(hdrCell("Date"));
            histTable.addCell(hdrCell("Client"));
            histTable.addCell(hdrCell("Ville"));
            histTable.addCell(hdrCell("Statut"));
            histTable.addCell(hdrCellR("Retard"));

            List<Delivery> recent = filtered.stream()
                    .filter(d -> d.getOrder() != null)
                    .sorted(Comparator.comparing(
                            d -> d.getCompletedAt() != null ? d.getCompletedAt() : d.getCreatedAt(),
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(30)
                    .toList();

            boolean alt = false;
            for (Delivery d : recent) {
                LocalDateTime ref = d.getCompletedAt() != null ? d.getCompletedAt() : d.getCreatedAt();
                String date    = ref != null ? ref.format(DT_FR) : "-";
                String client  = d.getOrder() != null ? safe(d.getOrder().getClientName()) : "-";
                String city    = d.getOrder() != null ? safe(d.getOrder().getDropoffCity()) : "-";
                String statut  = statusFr(d.getStatus());
                long   delay   = d.getCompletedAt() != null && d.getRouteEtaAt() != null
                                   ? Math.max(0, java.time.Duration.between(d.getRouteEtaAt(), d.getCompletedAt()).toMinutes()) : 0;
                String delayStr = delay > 0 ? "+" + delay + " min" : "—";

                histTable.addCell(cellAlt(date, alt));
                histTable.addCell(cellAlt(client, alt));
                histTable.addCell(cellAlt(city, alt));
                histTable.addCell(cellAlt(statut, alt));
                histTable.addCell(cellRAlt(delayStr, alt));
                alt = !alt;
            }

            doc.add(histTable);
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw AppException.serviceUnavailable("Erreur génération rapport chauffeur: " + e.getMessage());
        }
    }

    // ── Chart builder ─────────────────────────────────────────────────────────

    private static byte[] buildTrendChart(Map<String, Long> dailyVolume) {
        try {
            DefaultCategoryDataset ds = new DefaultCategoryDataset();
            dailyVolume.forEach((day, count) -> ds.addValue(count, "Volume", day));

            JFreeChart chart = ChartFactory.createBarChart(
                    null, null, null, ds, PlotOrientation.VERTICAL, false, false, false);
            chart.setBackgroundPaint(Color.WHITE);
            chart.setBorderVisible(false);

            CategoryPlot plot = chart.getCategoryPlot();
            plot.setBackgroundPaint(Color.WHITE);
            plot.setOutlinePaint(null);
            plot.setRangeGridlinePaint(new Color(230, 230, 230));
            plot.setDomainGridlinesVisible(false);

            BarRenderer renderer = (BarRenderer) plot.getRenderer();
            renderer.setSeriesPaint(0, new Color(255, 87, 34));
            renderer.setShadowVisible(false);
            renderer.setBarPainter(new StandardBarPainter());

            Font axisFont = new Font("SansSerif", Font.PLAIN, 9);
            CategoryAxis domain = plot.getDomainAxis();
            domain.setTickLabelFont(axisFont);
            domain.setAxisLineVisible(false);
            domain.setTickMarksVisible(false);

            NumberAxis range = (NumberAxis) plot.getRangeAxis();
            range.setTickLabelFont(axisFont);
            range.setAxisLineVisible(false);
            range.setStandardTickUnits(NumberAxis.createIntegerTickUnits());

            BufferedImage img = chart.createBufferedImage(515, 150);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    // Inline comparison bar: driver rate vs fleet average
    private static Element buildComparisonBar(double driverRate, double fleetRate) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);

        PdfPCell title = new PdfPCell(new Phrase("COMPARAISON FLOTTE", muted(7)));
        title.setBorder(Rectangle.NO_BORDER);
        title.setPaddingBottom(2f);

        Paragraph vals = new Paragraph();
        vals.add(new Chunk("Ce chauffeur : ", muted(8)));
        vals.add(new Chunk(String.format("%.1f%%", driverRate), bold(9)));
        vals.add(new Chunk("   Flotte : ", muted(8)));
        vals.add(new Chunk(String.format("%.1f%%", fleetRate), regular(9)));
        PdfPCell valCell = new PdfPCell(vals);
        valCell.setBorder(Rectangle.NO_BORDER);

        t.addCell(title);
        t.addCell(valCell);
        return t;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static PdfPCell wrapKpi(PdfPTable inner) {
        PdfPCell c = new PdfPCell(inner);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(3f);
        return c;
    }

    private static LocalDateTime resolveStart(String period, LocalDate from, LocalDateTime now) {
        if (from != null) return from.atStartOfDay();
        return switch (period == null ? "day" : period.toLowerCase()) {
            case "all"   -> LocalDate.of(2000, 1, 1).atStartOfDay();
            case "week"  -> LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay();
            case "month" -> LocalDate.now().withDayOfMonth(1).atStartOfDay();
            default      -> LocalDate.now().atStartOfDay();
        };
    }

    private static String buildPeriodLabel(String period, LocalDate from, LocalDateTime start, LocalDateTime end) {
        if (from != null) return "Du " + start.format(DATE_FR) + " au " + end.format(DATE_FR);
        return switch (period == null ? "day" : period.toLowerCase()) {
            case "all"   -> "Toute la période";
            case "week"  -> "Semaine du " + start.format(DATE_FR);
            case "month" -> "Mois de " + start.format(DATE_FR);
            default      -> "Aujourd'hui · " + start.format(DATE_FR);
        };
    }

    private static String statusFr(DeliveryStatus s) {
        if (s == null) return "-";
        return switch (s) {
            case DELIVERED            -> "Livré";
            case PARTIALLY_DELIVERED  -> "Partiel";
            case FAILED               -> "Échoué";
            case CANCELLED            -> "Annulé";
            case IN_TRANSIT           -> "En route";
            case PICKED_UP            -> "Ramassé";
            case SCHEDULED            -> "Planifié";
            case UNSCHEDULED          -> "Non planifié";
            default                   -> s.name();
        };
    }
}
