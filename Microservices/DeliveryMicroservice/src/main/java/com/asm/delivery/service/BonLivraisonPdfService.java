package com.asm.delivery.service;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.OrderItem;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;

@Service
@RequiredArgsConstructor
@Slf4j
public class BonLivraisonPdfService extends BasePdfService {

    private final DeliveryRepository deliveryRepository;
    private final TransportPort      transportPort;

    public byte[] generate(UUID deliveryId) {
        Delivery delivery = deliveryRepository.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found: " + deliveryId));

        Order order = delivery.getOrder();

        String driverName = "-";
        if (delivery.getDriverId() != null) {
            try {
                DriverDTO d = transportPort.getDriver(delivery.getDriverId().toString());
                if (d != null && d.getName() != null) driverName = d.getName();
            } catch (Exception e) {
                log.warn("Driver fetch failed for {}: {}", delivery.getDriverId(), e.getMessage());
            }
        }

        String ref         = resolveRef(delivery, order);
        String clientName  = order != null ? safe(order.getClientName())    : "-";
        String clientPhone = order != null ? safe(order.getClientPhone())   : "-";
        String address     = order != null ? safe(order.getDropoffAddress()): "-";
        String city        = order != null ? safe(order.getDropoffCity())   : "-";
        BigDecimal total   = order != null ? order.getTotalAmount()         : null;
        List<OrderItem> items = order != null && order.getItems() != null ? order.getItems() : List.of();

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document  doc    = newA4Document();
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new ReportPageEvent("BON DE LIVRAISON", ref));
            doc.open();

            // ── Reference + QR code row ───────────────────────────────────────
            PdfPTable refRow = new PdfPTable(new float[]{3, 1});
            refRow.setWidthPercentage(100);
            refRow.setSpacingAfter(8f);

            PdfPCell refCell = new PdfPCell();
            refCell.setBorder(Rectangle.NO_BORDER);
            refCell.addElement(new Paragraph("Référence : " + ref, bold(10)));
            refCell.addElement(new Paragraph("Date : " + LocalDateTime.now().format(DT_FR), regular(8)));
            refRow.addCell(refCell);

            // QR code
            byte[] qrBytes = generateQr(ref);
            if (qrBytes != null) {
                Image qr = Image.getInstance(qrBytes);
                qr.scaleToFit(64, 64);
                PdfPCell qrCell = new PdfPCell(qr);
                qrCell.setBorder(Rectangle.NO_BORDER);
                qrCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                refRow.addCell(qrCell);
            } else {
                refRow.addCell(emptyCell());
            }
            doc.add(refRow);

            // ── COD box (most prominent element) ─────────────────────────────
            if (total != null && total.compareTo(BigDecimal.ZERO) > 0) {
                doc.add(buildCodBox(total));
            }

            // ── Two-column layout: CLIENT | LIVREUR ──────────────────────────
            doc.add(sectionLabel("DESTINATAIRE"));
            PdfPTable parties = new PdfPTable(new float[]{1, 1});
            parties.setWidthPercentage(100);
            parties.setSpacingAfter(8f);

            PdfPCell clientBox = buildInfoBox("CLIENT", new String[][]{
                {"Nom",      clientName},
                {"Tél.",     clientPhone},
                {"Adresse",  address},
                {"Ville",    city}
            });
            PdfPCell driverBox = buildInfoBox("LIVREUR", new String[][]{
                {"Nom",      driverName}
            });
            parties.addCell(clientBox);
            parties.addCell(driverBox);
            doc.add(parties);

            // ── Items table ───────────────────────────────────────────────────
            doc.add(sectionLabel("ARTICLES"));
            PdfPTable itemTable = new PdfPTable(new float[]{3.5f, 0.8f, 1.2f, 1.2f});
            itemTable.setWidthPercentage(100);
            itemTable.setHeaderRows(1);
            itemTable.addCell(hdrCell("Désignation"));
            itemTable.addCell(hdrCellR("Qté"));
            itemTable.addCell(hdrCellR("Prix unit."));
            itemTable.addCell(hdrCellR("Total"));

            boolean alt = false;
            for (OrderItem item : items) {
                int qty       = item.getQuantity() != null ? item.getQuantity() : 0;
                BigDecimal up = item.getUnitPrice();
                BigDecimal rt = up != null ? up.multiply(BigDecimal.valueOf(qty)) : null;
                itemTable.addCell(cellAlt(safe(item.getName()), alt));
                itemTable.addCell(cellRAlt(String.valueOf(qty), alt));
                itemTable.addCell(cellRAlt(up  != null ? up.toPlainString()  + " TND" : "-", alt));
                itemTable.addCell(cellRAlt(rt  != null ? rt.toPlainString()  + " TND" : "-", alt));
                alt = !alt;
            }

            // Total row at bottom of table
            if (total != null) {
                PdfPCell totalLbl = new PdfPCell(new Phrase("TOTAL", bold(9)));
                totalLbl.setColspan(3);
                totalLbl.setPadding(6f);
                totalLbl.setBorderColor(BORDER_GRAY);
                totalLbl.setHorizontalAlignment(Element.ALIGN_RIGHT);
                totalLbl.setBackgroundColor(BG_HEADER_ROW);

                PdfPCell totalVal = new PdfPCell(new Phrase(total.toPlainString() + " TND", bold(9)));
                totalVal.setPadding(6f);
                totalVal.setBorderColor(BORDER_GRAY);
                totalVal.setHorizontalAlignment(Element.ALIGN_RIGHT);
                totalVal.setBackgroundColor(BG_HEADER_ROW);

                itemTable.addCell(totalLbl);
                itemTable.addCell(totalVal);
            }
            doc.add(itemTable);

            // ── Signature boxes ───────────────────────────────────────────────
            doc.add(sectionLabel("SIGNATURES"));
            PdfPTable sigTable = new PdfPTable(new float[]{1, 1});
            sigTable.setWidthPercentage(100);
            sigTable.setSpacingBefore(4f);

            sigTable.addCell(sigBox("Signature du client"));
            sigTable.addCell(sigBox("Signature du livreur"));
            doc.add(sigTable);

            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw AppException.serviceUnavailable("Erreur génération bon de livraison: " + e.getMessage());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static Element buildCodBox(BigDecimal total) throws DocumentException {
        PdfPTable box = new PdfPTable(1);
        box.setWidthPercentage(100);
        box.setSpacingAfter(10f);

        PdfPCell lbl = new PdfPCell(new Phrase("MONTANT À ENCAISSER (COD)", new Font(Font.HELVETICA, 8, Font.BOLD, java.awt.Color.WHITE)));
        lbl.setBackgroundColor(BRAND_ORANGE);
        lbl.setPaddingTop(6f);
        lbl.setPaddingLeft(10f);
        lbl.setPaddingBottom(2f);
        lbl.setBorder(Rectangle.NO_BORDER);

        PdfPCell val = new PdfPCell(new Phrase(total.toPlainString() + " TND", new Font(Font.HELVETICA, 22, Font.BOLD, BRAND_ORANGE)));
        val.setBorderColor(BRAND_ORANGE);
        val.setBorderWidth(2f);
        val.setPaddingTop(4f);
        val.setPaddingLeft(10f);
        val.setPaddingBottom(8f);

        box.addCell(lbl);
        box.addCell(val);
        return box;
    }

    private static PdfPCell buildInfoBox(String title, String[][] rows) {
        PdfPCell outer = new PdfPCell();
        outer.setPadding(6f);
        outer.setBorderColor(BORDER_GRAY);

        outer.addElement(new Paragraph(title, orange(8)));
        for (String[] row : rows) {
            Paragraph p = new Paragraph();
            p.add(new Chunk(row[0] + ": ", muted(8)));
            p.add(new Chunk(safe(row[1]), regular(8)));
            p.setSpacingBefore(2f);
            outer.addElement(p);
        }
        return outer;
    }

    private static PdfPCell sigBox(String label) {
        PdfPCell c = new PdfPCell(new Phrase(label + "\n\n\n\n", regular(8)));
        c.setPadding(6f);
        c.setBorderColor(BORDER_GRAY);
        return c;
    }

    private static PdfPCell emptyCell() {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        return c;
    }

    private static String resolveRef(Delivery delivery, Order order) {
        if (order != null && order.getErpOrderId() != null && !order.getErpOrderId().isBlank())
            return order.getErpOrderId();
        if (order != null && order.getErpExternalRef() != null && !order.getErpExternalRef().isBlank())
            return order.getErpExternalRef();
        return delivery.getId().toString().substring(0, 8).toUpperCase();
    }

    private static byte[] generateQr(String content) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = Map.of(
                    EncodeHintType.MARGIN, 1,
                    EncodeHintType.CHARACTER_SET, "UTF-8"
            );
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, 120, 120, hints);
            BufferedImage img = MatrixToImageWriter.toBufferedImage(matrix);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            log.warn("QR generation failed: {}", e.getMessage());
            return null;
        }
    }
}
