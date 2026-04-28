package com.asm.delivery.service;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.OrderItem;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BonLivraisonPdfService {

    private final DeliveryRepository deliveryRepository;
    private final TransportPort transportPort;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public byte[] generate(UUID deliveryId) {
        Delivery delivery = deliveryRepository.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found: " + deliveryId));

        Order order = delivery.getOrder();

        String driverName = "-";
        if (delivery.getDriverId() != null) {
            try {
                DriverDTO driver = transportPort.getDriver(delivery.getDriverId().toString());
                if (driver != null && driver.getName() != null) driverName = driver.getName();
            } catch (Exception e) {
                log.warn("Could not fetch driver name for {}: {}", delivery.getDriverId(), e.getMessage());
            }
        }

        String ref = order != null
                ? (order.getErpOrderId() != null ? order.getErpOrderId() : ns(order.getErpExternalRef()))
                : deliveryId.toString().substring(0, 8).toUpperCase();

        String clientName  = order != null ? ns(order.getClientName())  : "-";
        String clientPhone = order != null ? ns(order.getClientPhone()) : "-";
        String address     = order != null ? ns(order.getDropoffAddress()) : "-";
        String city        = order != null ? ns(order.getDropoffCity()) : "-";
        BigDecimal total   = order != null ? order.getTotalAmount() : null;
        List<OrderItem> items = order != null && order.getItems() != null ? order.getItems() : List.of();

        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            float pageW = PDRectangle.A4.getWidth();   // 595
            float margin = 50f;

            // ── Branded header bar ────────────────────────────────────────────
            cs.setNonStrokingColor(1.0f, 0.341f, 0.133f);   // #FF5722
            cs.addRect(0, 800f, pageW, 42f);
            cs.fill();

            // Company name (white)
            cs.setNonStrokingColor(1f, 1f, 1f);
            line(cs, 14, true, margin, 822f, "ASM Track");

            // Document type right-aligned (white)
            line(cs, 11, true, 370f, 822f, "BON DE LIVRAISON");

            // Thin accent line below header
            cs.setNonStrokingColor(0.929f, 0.231f, 0.031f); // #ED3B08
            cs.addRect(0, 798f, pageW, 2f);
            cs.fill();

            // Reset to black
            cs.setNonStrokingColor(0f, 0f, 0f);

            float y = 778f;

            // Ref + date under header
            y = line(cs, 9, false, margin, y, "Ref : " + ref
                    + "     Date : " + LocalDateTime.now().format(DATE_FMT));
            y -= 10;

            // ── Client ────────────────────────────────────────────────────────
            // Section label in brand color
            cs.setNonStrokingColor(1.0f, 0.341f, 0.133f);
            y = line(cs, 10, true, margin, y, "CLIENT");
            cs.setNonStrokingColor(0f, 0f, 0f);
            y = line(cs, 10, false, margin, y - 2, "Nom:        " + clientName);
            y = line(cs, 10, false, margin, y - 2, "Tel.:       " + clientPhone);
            y = line(cs, 10, false, margin, y - 2, "Adresse:    " + address);
            y = line(cs, 10, false, margin, y - 2, "Ville:      " + city);
            y -= 8;

            // ── Driver ────────────────────────────────────────────────────────
            cs.setNonStrokingColor(1.0f, 0.341f, 0.133f);
            y = line(cs, 10, true, margin, y, "LIVREUR");
            cs.setNonStrokingColor(0f, 0f, 0f);
            y = line(cs, 10, false, margin, y - 2, "Nom: " + driverName);
            y -= 8;

            // ── Items table header ────────────────────────────────────────────
            cs.setNonStrokingColor(1.0f, 0.341f, 0.133f);
            y = line(cs, 10, true, margin, y, "ARTICLES");
            cs.setNonStrokingColor(0f, 0f, 0f);

            // Table header row background
            cs.setNonStrokingColor(0.96f, 0.96f, 0.96f);
            cs.addRect(margin, y - 14f, pageW - 2 * margin, 14f);
            cs.fill();
            cs.setNonStrokingColor(0f, 0f, 0f);

            y = line(cs, 9, true, margin, y - 2,
                    padR("Designation", 32) + padL("Qte", 6) + padL("P.U.", 12) + padL("Total", 12));
            y -= 2;

            // ── Items rows ────────────────────────────────────────────────────
            for (OrderItem item : items) {
                if (y < 100f) {
                    cs.close();
                    page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    cs = new PDPageContentStream(doc, page);
                    y = 800f;
                }
                int qty     = item.getQuantity() != null ? item.getQuantity() : 0;
                BigDecimal up = item.getUnitPrice();
                BigDecimal rowTotal = up != null ? up.multiply(BigDecimal.valueOf(qty)) : null;

                String row = padR(ns(item.getName()), 32)
                           + padL(String.valueOf(qty), 6)
                           + padL(up != null ? up.toPlainString() : "-", 12)
                           + padL(rowTotal != null ? rowTotal.toPlainString() : "-", 12);
                y = line(cs, 9, false, margin, y - 2, row);
            }

            y -= 6;

            // ── Grand total ──────────────────────────────────────────────────
            y -= 4;
            if (total != null) {
                cs.setNonStrokingColor(1.0f, 0.341f, 0.133f);
                cs.addRect(margin, y - 14f, pageW - 2 * margin, 14f);
                cs.fill();
                cs.setNonStrokingColor(1f, 1f, 1f);
                y = line(cs, 11, true, margin, y - 2, "TOTAL  " + total.toPlainString() + " TND");
                cs.setNonStrokingColor(0f, 0f, 0f);
            }
            y -= 20;

            // ── Signature box ─────────────────────────────────────────────────
            cs.setStrokingColor(0.7f, 0.7f, 0.7f);
            cs.addRect(margin, y - 48f, 160f, 48f);
            cs.stroke();
            cs.addRect(pageW - margin - 160f, y - 48f, 160f, 48f);
            cs.stroke();
            cs.setStrokingColor(0f, 0f, 0f);
            y = line(cs, 8, false, margin + 4, y - 4, "Signature du client");
            line(cs, 8, false, pageW - margin - 156f, y + (8 + 3f), "Signature du livreur");

            cs.close();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw AppException.serviceUnavailable("Failed to generate bon de livraison PDF");
        }
    }

    private static float line(PDPageContentStream cs, int size, boolean bold, float x, float y, String text) throws IOException {
        cs.beginText();
        cs.setFont(bold ? PDType1Font.HELVETICA_BOLD : PDType1Font.HELVETICA, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text == null ? "" : sanitize(text));
        cs.endText();
        return y - (size + 3f);
    }

    /** Strip non-WinAnsi characters that PDType1Font cannot encode. */
    private static String sanitize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            sb.append(c < 256 ? c : '?');
        }
        return sb.toString();
    }

    private static String ns(String v) { return v != null ? v : "-"; }

    private static String padR(String s, int len) {
        if (s == null) s = "";
        if (s.length() >= len) return s.substring(0, len);
        return s + " ".repeat(len - s.length());
    }

    private static String padL(String s, int len) {
        if (s == null) s = "";
        if (s.length() >= len) return s.substring(0, len);
        return " ".repeat(len - s.length()) + s;
    }
}
