package com.asm.delivery.service;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.Zone;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.ZoneRepository;
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
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;

@Service
@RequiredArgsConstructor
public class AnalyticsPdfService extends BasePdfService {

    private final DeliveryRepository deliveryRepository;
    private final ZoneRepository     zoneRepository;
    private final TransportPort      transportPort;

    public byte[] generate(String period, LocalDate from, LocalDate to) {
        LocalDateTime now   = LocalDateTime.now();
        LocalDateTime start = resolveStart(period, from, now);
        LocalDateTime end   = to != null ? to.atTime(23, 59, 59) : now;

        List<Delivery> all      = deliveryRepository.findAll();
        List<Delivery> filtered = all.stream()
                .filter(d -> {
                    LocalDateTime ref = d.getCompletedAt() != null ? d.getCompletedAt() : d.getCreatedAt();
                    return ref != null && !ref.isBefore(start) && !ref.isAfter(end);
                })
                .collect(Collectors.toList());

        long total     = filtered.size();
        long delivered = filtered.stream().filter(d -> d.getStatus() == DeliveryStatus.DELIVERED || d.getStatus() == DeliveryStatus.PARTIALLY_DELIVERED).count();
        long failed    = filtered.stream().filter(d -> d.getStatus() == DeliveryStatus.FAILED).count();
        long pending   = filtered.stream().filter(d -> d.getStatus() == DeliveryStatus.UNSCHEDULED || d.getStatus() == DeliveryStatus.SCHEDULED).count();
        long inTransit = filtered.stream().filter(d -> d.getStatus() == DeliveryStatus.IN_TRANSIT || d.getStatus() == DeliveryStatus.PICKED_UP).count();
        double successRate = total == 0 ? 0.0 : (double) delivered / total * 100.0;

        Map<String, String> zoneNameById = zoneRepository.findAll().stream()
                .collect(Collectors.toMap(z -> z.getId().toString(), Zone::getName));

        // Volume by hour (using completedAt)
        Map<Integer, Long> byHour = filtered.stream()
                .filter(d -> d.getCompletedAt() != null)
                .collect(Collectors.groupingBy(d -> d.getCompletedAt().getHour(), Collectors.counting()));

        // Failures by code
        Map<String, Long> byFailureCode = filtered.stream()
                .filter(d -> d.getStatus() == DeliveryStatus.FAILED && d.getFailureCode() != null)
                .collect(Collectors.groupingBy(d -> d.getFailureCode().name(), Collectors.counting()));

        // Driver ranking
        Map<UUID, Long> driverTotal     = new LinkedHashMap<>();
        Map<UUID, Long> driverDelivered = new LinkedHashMap<>();
        for (Delivery d : filtered) {
            if (d.getDriverId() == null) continue;
            driverTotal.merge(d.getDriverId(), 1L, Long::sum);
            if (d.getStatus() == DeliveryStatus.DELIVERED || d.getStatus() == DeliveryStatus.PARTIALLY_DELIVERED) {
                driverDelivered.merge(d.getDriverId(), 1L, Long::sum);
            }
        }

        // Fetch driver names
        Map<UUID, String> driverNames = new HashMap<>();
        for (UUID dId : driverTotal.keySet()) {
            try {
                DriverDTO dto = transportPort.getDriver(dId.toString());
                if (dto != null) driverNames.put(dId, dto.getName());
            } catch (Exception ignored) {}
        }

        String periodLabel = buildPeriodLabel(period, from, to, start, end);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document  doc    = newA4Document();
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new ReportPageEvent("RAPPORT D'ACTIVITÉ", periodLabel));
            doc.open();

            // ── Period header ─────────────────────────────────────────────────
            Paragraph periodPara = new Paragraph("Période : " + periodLabel, regular(9));
            periodPara.setSpacingAfter(6f);
            doc.add(periodPara);

            // ── KPI summary ───────────────────────────────────────────────────
            doc.add(sectionLabel("SYNTHÈSE"));
            PdfPTable kpiGrid = new PdfPTable(new float[]{1, 1, 1, 1, 1});
            kpiGrid.setWidthPercentage(100);
            kpiGrid.setSpacingAfter(12f);
            kpiGrid.addCell(wrapKpi(kpiBox("VOLUME TOTAL",    String.valueOf(total))));
            kpiGrid.addCell(wrapKpi(kpiBox("LIVRÉES",         String.valueOf(delivered))));
            kpiGrid.addCell(wrapKpi(kpiBox("ÉCHOUÉES",        String.valueOf(failed))));
            kpiGrid.addCell(wrapKpi(kpiBox("EN TRANSIT",      String.valueOf(inTransit))));
            kpiGrid.addCell(wrapKpi(kpiBox("TAUX SUCCÈS",     String.format("%.1f%%", successRate))));
            doc.add(kpiGrid);

            // ── Volume by hour chart ──────────────────────────────────────────
            doc.add(sectionLabel("VOLUME DE LIVRAISONS PAR HEURE"));
            byte[] chartPng = buildHourChart(byHour);
            if (chartPng != null) {
                com.lowagie.text.Image chartImg = com.lowagie.text.Image.getInstance(chartPng);
                chartImg.setWidthPercentage(100);
                chartImg.setSpacingAfter(12f);
                doc.add(chartImg);
            }

            // ── Failure breakdown ─────────────────────────────────────────────
            if (!byFailureCode.isEmpty()) {
                doc.add(sectionLabel("RÉPARTITION DES ÉCHECS"));
                PdfPTable failTable = new PdfPTable(new float[]{3, 1, 1});
                failTable.setWidthPercentage(100);
                failTable.setSpacingAfter(12f);
                failTable.setHeaderRows(1);
                failTable.addCell(hdrCell("Raison"));
                failTable.addCell(hdrCellR("Nb"));
                failTable.addCell(hdrCellR("%"));
                boolean alt = false;
                List<Map.Entry<String, Long>> sortedFails = byFailureCode.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).toList();
                for (Map.Entry<String, Long> e : sortedFails) {
                    double pct = failed == 0 ? 0 : (double) e.getValue() / failed * 100.0;
                    failTable.addCell(cellAlt(formatFailCode(e.getKey()), alt));
                    failTable.addCell(cellRAlt(e.getValue().toString(), alt));
                    failTable.addCell(cellRAlt(String.format("%.1f%%", pct), alt));
                    alt = !alt;
                }
                doc.add(failTable);
            }

            // ── Driver ranking ────────────────────────────────────────────────
            if (!driverTotal.isEmpty()) {
                doc.add(sectionLabel("CLASSEMENT CHAUFFEURS"));
                PdfPTable driverTable = new PdfPTable(new float[]{0.4f, 2.5f, 1f, 1f, 1.2f});
                driverTable.setWidthPercentage(100);
                driverTable.setHeaderRows(1);
                driverTable.addCell(hdrCell("#"));
                driverTable.addCell(hdrCell("Chauffeur"));
                driverTable.addCell(hdrCellR("Volume"));
                driverTable.addCell(hdrCellR("Livrées"));
                driverTable.addCell(hdrCellR("Taux succès"));

                List<UUID> ranked = driverTotal.entrySet().stream()
                        .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
                        .map(Map.Entry::getKey).toList();

                boolean alt = false;
                int rank = 1;
                for (UUID dId : ranked) {
                    long   vol  = driverTotal.getOrDefault(dId, 0L);
                    long   dlv  = driverDelivered.getOrDefault(dId, 0L);
                    double rate = vol == 0 ? 0 : (double) dlv / vol * 100.0;
                    driverTable.addCell(cellAlt(String.valueOf(rank++), alt));
                    driverTable.addCell(cellAlt(safe(driverNames.get(dId)), alt));
                    driverTable.addCell(cellRAlt(String.valueOf(vol),  alt));
                    driverTable.addCell(cellRAlt(String.valueOf(dlv),  alt));
                    driverTable.addCell(cellRAlt(String.format("%.1f%%", rate), alt));
                    alt = !alt;
                }
                doc.add(driverTable);
            }

            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw AppException.serviceUnavailable("Erreur génération rapport analytics: " + e.getMessage());
        }
    }

    // ── Chart builder ─────────────────────────────────────────────────────────

    private static byte[] buildHourChart(Map<Integer, Long> byHour) {
        try {
            DefaultCategoryDataset ds = new DefaultCategoryDataset();
            for (int h = 6; h <= 22; h++) {
                ds.addValue(byHour.getOrDefault(h, 0L), "Livraisons", h + "h");
            }
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
            renderer.setItemMargin(0.02);

            java.awt.Font axisFont = new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 9);
            CategoryAxis domainAxis = plot.getDomainAxis();
            domainAxis.setTickLabelFont(axisFont);
            domainAxis.setAxisLineVisible(false);
            domainAxis.setTickMarksVisible(false);

            NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
            rangeAxis.setTickLabelFont(axisFont);
            rangeAxis.setAxisLineVisible(false);
            rangeAxis.setStandardTickUnits(org.jfree.chart.axis.NumberAxis.createIntegerTickUnits());

            BufferedImage img = chart.createBufferedImage(515, 180);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static PdfPCell wrapKpi(PdfPTable inner) {
        PdfPCell c = new PdfPCell(inner);
        c.setBorder(com.lowagie.text.Rectangle.NO_BORDER);
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

    private static String buildPeriodLabel(String period, LocalDate from, LocalDate to,
                                           LocalDateTime start, LocalDateTime end) {
        if (from != null) {
            return "Du " + start.format(DATE_FR) + " au " + end.format(DATE_FR);
        }
        return switch (period == null ? "day" : period.toLowerCase()) {
            case "all"   -> "Toute la période";
            case "week"  -> "Semaine du " + start.format(DATE_FR);
            case "month" -> "Mois de " + start.format(DATE_FR);
            default      -> "Aujourd'hui · " + start.format(DATE_FR);
        };
    }

    private static String formatFailCode(String code) {
        if (code == null) return "-";
        return switch (code.toUpperCase()) {
            case "CLIENT_ABSENT"       -> "Client absent";
            case "ADDRESS_NOT_FOUND"   -> "Adresse introuvable";
            case "REFUSED_DELIVERY"    -> "Refus de livraison";
            case "COD_NOT_AVAILABLE"   -> "Montant COD indisponible";
            case "DAMAGED_PACKAGE"     -> "Colis endommagé";
            case "WRONG_ADDRESS"       -> "Adresse incorrecte";
            default                    -> code;
        };
    }
}
