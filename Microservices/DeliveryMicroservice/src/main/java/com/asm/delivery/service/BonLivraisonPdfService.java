package com.asm.delivery.service;

import com.asm.delivery.entity.Company;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.OrderItem;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.CompanyRepository;
import com.asm.delivery.repository.DeliveryRepository;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Native ASM-generated delivery note (bon de livraison).
 *
 * <p>Rendered entirely from the platform's own data (order lines + company branding), so it is
 * <b>independent of the ERP version and vendor</b> — the same document works whether the order came
 * from Odoo, ERPNext, or any future provider. {@code blNumber} stays the ERP picking reference for
 * traceability, but the document itself is ours (no Odoo report fetch).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BonLivraisonPdfService extends BasePdfService {

    private final DeliveryRepository deliveryRepository;
    private final CompanyRepository companyRepository;
    private final CompanyBrandingResolver brandingResolver;

    public byte[] generate(UUID deliveryId) {
        Delivery delivery = deliveryRepository.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found: " + deliveryId));

        Order order = delivery.getOrder();
        if (order == null) {
            throw AppException.notFound("Commande introuvable pour la livraison " + deliveryId);
        }

        String blNo = firstNonBlank(delivery.getBlNumber(), order.getBlNumber(), order.resolveRef());
        Company company = companyRepository.findAllByActiveTrue().stream().findFirst().orElse(null);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = newA4Document();
            PdfWriter writer = PdfWriter.getInstance(doc, out);

            ReportPageEvent event = brandingResolver.resolve("BON DE LIVRAISON", "N° " + blNo);
            writer.setPageEvent(event);
            Color brand = event.getPrimaryColor();

            doc.open();

            // Title + references
            Paragraph title = new Paragraph("Bon de livraison", bold(18));
            title.setSpacingAfter(2f);
            doc.add(title);
            doc.add(new Paragraph("N° " + blNo
                    + (order.getErpExternalRef() != null ? "  ·  Commande " + order.getErpExternalRef() : ""),
                    muted(9)));
            doc.add(divider());

            // Émetteur | Destinataire
            PdfPTable parties = new PdfPTable(2);
            parties.setWidthPercentage(100);
            parties.setSpacingAfter(10f);
            parties.addCell(partyBox("ÉMETTEUR", emitterRows(company), brand));
            parties.addCell(partyBox("DESTINATAIRE", recipientRows(order), brand));
            doc.add(parties);

            // Dates line
            Paragraph dates = new Paragraph();
            dates.setSpacingAfter(10f);
            dates.add(new Chunk("Date d'émission : ", muted(9)));
            dates.add(new Chunk(DATE_FR.format(LocalDate.now()) + "        ", bold(9)));
            dates.add(new Chunk("Date de livraison : ", muted(9)));
            String delivered = delivery.getCompletedAt() != null ? DATE_FR.format(delivery.getCompletedAt())
                    : (order.getScheduledAt() != null ? DATE_FR.format(order.getScheduledAt()) : "-");
            dates.add(new Chunk(delivered, bold(9)));
            doc.add(dates);

            // Items table
            doc.add(sectionLabel("ARTICLES", brand));
            PdfPTable items = new PdfPTable(new float[]{1.0f, 3.4f, 0.7f, 0.9f});
            items.setWidthPercentage(100);
            items.setHeaderRows(1);
            items.addCell(hdrCell("Réf", brand));
            items.addCell(hdrCell("Désignation", brand));
            items.addCell(hdrCellR("Qté", brand));
            items.addCell(hdrCellR("Poids", brand));

            List<OrderItem> lines = order.getItems() != null ? order.getItems() : List.of();
            boolean alt = false;
            int totalQty = 0;
            double totalWeight = 0d;
            for (OrderItem it : lines) {
                int qty = it.getQuantity() != null ? it.getQuantity() : 0;
                double unitW = it.getUnitWeightKg() != null ? it.getUnitWeightKg().doubleValue() : 0d;
                double lineW = unitW * qty;
                totalQty += qty;
                totalWeight += lineW;
                items.addCell(cellAlt(safe(it.getSku()), alt));
                items.addCell(cellAlt(safe(it.getName()), alt));
                items.addCell(cellRAlt(String.valueOf(qty), alt));
                items.addCell(cellRAlt(String.format("%.2f kg", lineW), alt));
                alt = !alt;
            }
            doc.add(items);

            // Totals line
            Paragraph totals = new Paragraph();
            totals.setSpacingBefore(6f);
            totals.setSpacingAfter(14f);
            totals.add(new Chunk("Total articles  ", muted(9)));
            totals.add(new Chunk(totalQty + "        ", bold(9)));
            totals.add(new Chunk("Poids total  ", muted(9)));
            totals.add(new Chunk(String.format("%.2f kg", totalWeight) + "        ", bold(9)));
            if (order.getTotalAmount() != null && order.getTotalAmount().compareTo(BigDecimal.ZERO) > 0) {
                totals.add(new Chunk("Montant  ", muted(9)));
                totals.add(new Chunk(order.getTotalAmount().toPlainString() + " "
                        + (order.getCurrency() != null ? order.getCurrency() : "TND"), bold(9)));
            }
            doc.add(totals);

            // Delivery instructions
            if (order.getDeliveryInstructions() != null && !order.getDeliveryInstructions().isBlank()) {
                doc.add(sectionLabel("INSTRUCTIONS", brand));
                doc.add(new Paragraph(order.getDeliveryInstructions(), regular(9)));
            }

            // Signature zone (legal proof of receipt)
            doc.add(signatureBlock());

            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw AppException.serviceUnavailable("Erreur génération BL: " + e.getMessage());
        }
    }

    // ── Émetteur / Destinataire blocks ──────────────────────────────────────────

    private static String[][] emitterRows(Company c) {
        List<String[]> rows = new ArrayList<>();
        if (c == null) { rows.add(new String[]{"", "ASM Track"}); return rows.toArray(new String[0][]); }
        rows.add(new String[]{"", safe(c.getName())});
        String addr = c.getAddress();
        if (addr != null && c.getCity() != null && !addr.toLowerCase().contains(c.getCity().toLowerCase())) {
            addr = addr + ", " + c.getCity();
        } else if (addr == null && c.getCity() != null) {
            addr = c.getCity();
        }
        if (addr != null) rows.add(new String[]{"", addr});
        if (c.getPhone()   != null) rows.add(new String[]{"Tél", c.getPhone()});
        if (c.getTaxId()   != null) rows.add(new String[]{"MF", c.getTaxId()});
        if (c.getRegistrationNumber() != null) rows.add(new String[]{"RC", c.getRegistrationNumber()});
        return rows.toArray(new String[0][]);
    }

    private static String[][] recipientRows(Order o) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"", safe(o.getClientName())});
        String addr = o.getDropoffAddress();
        if (addr != null && o.getDropoffCity() != null && !addr.toLowerCase().contains(o.getDropoffCity().toLowerCase())) {
            addr = addr + ", " + o.getDropoffCity();
        }
        if (addr != null) rows.add(new String[]{"", addr});
        if (o.getClientPhone() != null) rows.add(new String[]{"Tél", o.getClientPhone()});
        return rows.toArray(new String[0][]);
    }

    /** Key/value box with a brand left-rule; blank keys render the value alone (no leading colon). */
    private static PdfPCell partyBox(String title, String[][] rows, Color brand) {
        PdfPCell outer = new PdfPCell();
        outer.setPaddingTop(8f); outer.setPaddingBottom(8f);
        outer.setPaddingLeft(10f); outer.setPaddingRight(10f);
        outer.setBorderColor(BORDER_GRAY);
        outer.setBorderWidthLeft(3f);
        outer.setBorderColorLeft(brand);

        Paragraph t = new Paragraph(title, colored(7, brand));
        t.setSpacingAfter(5f);
        outer.addElement(t);
        for (String[] row : rows) {
            Paragraph p = new Paragraph();
            if (row[0] != null && !row[0].isEmpty()) {
                p.add(new Chunk(row[0] + " : ", muted(8)));
            }
            p.add(new Chunk(safe(row[1]), bold(8)));
            p.setSpacingBefore(2f);
            outer.addElement(p);
        }
        return outer;
    }

    private static PdfPTable signatureBlock() {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.setSpacingBefore(24f);
        t.addCell(sigCell("Signature du livreur"));
        t.addCell(sigCell("Signature du destinataire (réception)"));
        return t;
    }

    private static PdfPCell sigCell(String label) {
        PdfPCell c = new PdfPCell();
        c.setFixedHeight(72f);
        c.setBorderColor(BORDER_GRAY);
        c.setPadding(6f);
        c.setVerticalAlignment(Element.ALIGN_BOTTOM);
        c.addElement(new Paragraph(label, muted(8)));
        return c;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "-";
    }
}
