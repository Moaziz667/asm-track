package com.asm.delivery.service;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.OrderItem;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.CompanyRepository;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.storage.MinioStorageService;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Color;
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

    private final DeliveryRepository  deliveryRepository;
    private final TransportPort       transportPort;
    private final CompanyRepository   companyRepository;
    private final MinioStorageService minioStorageService;

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
                log.warn("Driver fetch failed: {}", e.getMessage());
            }
        }

        String ref         = resolveRef(delivery, order);
        String clientName  = order != null ? safe(order.getClientName())     : "-";
        String clientPhone = order != null ? safe(order.getClientPhone())    : "-";
        String address     = order != null ? safe(order.getDropoffAddress()) : "-";
        String city        = order != null ? safe(order.getDropoffCity())    : "-";
        BigDecimal total   = order != null ? order.getTotalAmount()          : null;
        List<OrderItem> items = order != null && order.getItems() != null ? order.getItems() : List.of();
        int itemCount = items.size();

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document  doc    = newA4Document();
            PdfWriter writer = PdfWriter.getInstance(doc, out);

            ReportPageEvent event = pageEvent("BON DE LIVRAISON", ref, delivery.getCompanyId(), companyRepository, minioStorageService);
            writer.setPageEvent(event);

            Color brand = event.getPrimaryColor();
            String companyName = event.getCompanyName();

            doc.open();

            // ── TITLE ROW: Document title left | Barcode right ────────────────
            PdfPTable titleRow = new PdfPTable(new float[]{3f, 1.4f});
            titleRow.setWidthPercentage(100);
            titleRow.setSpacingAfter(6f);

            PdfPCell titleCell = new PdfPCell();
            titleCell.setBorder(Rectangle.NO_BORDER);
            titleCell.setPaddingBottom(4f);
            Paragraph titlePara = new Paragraph("Bon de Livraison", bold(20));
            titlePara.setSpacingAfter(3f);
            titleCell.addElement(titlePara);
            titleCell.addElement(new Paragraph("Réf : " + ref + "   ·   " +
                    LocalDateTime.now().format(DATE_FR), muted(8)));
            titleRow.addCell(titleCell);

            byte[] barcodeBytes = generateBarcode(ref);
            if (barcodeBytes != null) {
                Image barcode = Image.getInstance(barcodeBytes);
                barcode.scaleToFit(160, 44);
                PdfPCell barcodeCell = new PdfPCell(barcode);
                barcodeCell.setBorder(Rectangle.NO_BORDER);
                barcodeCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                barcodeCell.setVerticalAlignment(Element.ALIGN_BOTTOM);
                titleRow.addCell(barcodeCell);
            } else {
                titleRow.addCell(noBorderCell());
            }
            doc.add(titleRow);

            // ── Brand divider ─────────────────────────────────────────────────
            PdfPTable divLine = new PdfPTable(1);
            divLine.setWidthPercentage(100);
            divLine.setSpacingAfter(10f);
            PdfPCell divCell = new PdfPCell(new Phrase(" "));
            divCell.setFixedHeight(2f);
            divCell.setBackgroundColor(brand);
            divCell.setBorder(Rectangle.NO_BORDER);
            divLine.addCell(divCell);
            doc.add(divLine);

            // ── ROW 2: Company info (left) | Order summary (right) ────────────
            PdfPTable infoRow = new PdfPTable(new float[]{1f, 1f});
            infoRow.setWidthPercentage(100);
            infoRow.setSpacingAfter(10f);

            // Company info box (left) — logo area is in page header; show name + label
            PdfPCell companyCell = new PdfPCell();
            companyCell.setBorderColor(BORDER_GRAY);
            companyCell.setBorderWidthLeft(3f);
            companyCell.setBorderColorLeft(brand);
            companyCell.setPaddingTop(8f);
            companyCell.setPaddingBottom(8f);
            companyCell.setPaddingLeft(10f);
            companyCell.setPaddingRight(10f);
            Paragraph expLabel = new Paragraph("EXPÉDITEUR", colored(7, brand));
            expLabel.setSpacingAfter(5f);
            companyCell.addElement(expLabel);
            companyCell.addElement(new Paragraph(companyName, bold(10)));
            infoRow.addCell(companyCell);

            // Order summary box (right) — gray background
            infoRow.addCell(summaryBox(new String[][]{
                {"N° commande",  ref},
                {"Date",         LocalDateTime.now().format(DATE_FR)},
                {"Nb articles",  String.valueOf(itemCount)},
                {"Livreur",      driverName},
            }, brand));

            doc.add(infoRow);

            // ── ROW 3: Client info (left) | Delivery address (right) ──────────
            PdfPTable addrRow = new PdfPTable(new float[]{1f, 1f});
            addrRow.setWidthPercentage(100);
            addrRow.setSpacingAfter(10f);

            addrRow.addCell(infoBox("DESTINATAIRE", new String[][]{
                {"Nom",      clientName},
                {"Tél.",     clientPhone},
            }, brand));

            addrRow.addCell(infoBox("ADRESSE DE LIVRAISON", new String[][]{
                {"Adresse",  address},
                {"Ville",    city},
            }, brand));

            doc.add(addrRow);

            // ── COD box ───────────────────────────────────────────────────────
            if (total != null && total.compareTo(BigDecimal.ZERO) > 0) {
                doc.add(buildCodBox(total, brand));
            }

            // ── Items table ───────────────────────────────────────────────────
            doc.add(sectionLabel("ARTICLES", brand));

            PdfPTable itemTable = new PdfPTable(new float[]{3.5f, 0.7f, 1.3f, 1.3f});
            itemTable.setWidthPercentage(100);
            itemTable.setHeaderRows(1);
            itemTable.addCell(hdrCell("Désignation", brand));
            itemTable.addCell(hdrCellR("Qté", brand));
            itemTable.addCell(hdrCellR("Prix unit.", brand));
            itemTable.addCell(hdrCellR("Total", brand));

            boolean alt = false;
            for (OrderItem item : items) {
                int qty       = (item.getQuantityDone() != null && item.getQuantityDone() > 0)
                              ? item.getQuantityDone()
                              : (item.getQuantity() != null ? item.getQuantity() : 0);
                BigDecimal up = item.getUnitPrice();
                BigDecimal rt = up != null ? up.multiply(BigDecimal.valueOf(qty)) : null;
                itemTable.addCell(cellAlt(safe(item.getName()), alt));
                itemTable.addCell(cellRAlt(String.valueOf(qty), alt));
                itemTable.addCell(cellRAlt(up != null ? up.toPlainString() + " TND" : "-", alt));
                itemTable.addCell(cellRAlt(rt != null ? rt.toPlainString() + " TND" : "-", alt));
                alt = !alt;
            }

            // Total row
            if (total != null) {
                PdfPCell totalLbl = new PdfPCell(new Phrase("TOTAL", colored(9, brand)));
                totalLbl.setColspan(3);
                totalLbl.setPaddingTop(7f); totalLbl.setPaddingBottom(7f); totalLbl.setPaddingLeft(6f);
                totalLbl.setBorderColor(BORDER_GRAY);
                totalLbl.setHorizontalAlignment(Element.ALIGN_RIGHT);
                totalLbl.setBackgroundColor(tint(brand));

                PdfPCell totalVal = new PdfPCell(new Phrase(total.toPlainString() + " TND", bold(9)));
                totalVal.setPaddingTop(7f); totalVal.setPaddingBottom(7f); totalVal.setPaddingRight(6f);
                totalVal.setBorderColor(BORDER_GRAY);
                totalVal.setHorizontalAlignment(Element.ALIGN_RIGHT);
                totalVal.setBackgroundColor(tint(brand));

                itemTable.addCell(totalLbl);
                itemTable.addCell(totalVal);
            }
            doc.add(itemTable);

            // ── Signature boxes ───────────────────────────────────────────────
            doc.add(sectionLabel("SIGNATURES", brand));
            PdfPTable sigTable = new PdfPTable(new float[]{1f, 1f});
            sigTable.setWidthPercentage(100);
            sigTable.setSpacingBefore(4f);
            sigTable.addCell(sigBox("Signature du client", brand));
            sigTable.addCell(sigBox("Signature du livreur", brand));
            doc.add(sigTable);

            doc.close();
            return out.toByteArray();

        } catch (Exception e) {
            throw AppException.serviceUnavailable("Erreur génération bon de livraison: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Element buildCodBox(BigDecimal total, Color brand) {
        PdfPTable box = new PdfPTable(1);
        box.setWidthPercentage(100);
        box.setSpacingAfter(10f);

        PdfPCell lbl = new PdfPCell(new Phrase("MONTANT À ENCAISSER (COD)",
                new Font(Font.HELVETICA, 8, Font.BOLD, Color.WHITE)));
        lbl.setBackgroundColor(brand);
        lbl.setPaddingTop(6f); lbl.setPaddingLeft(12f); lbl.setPaddingBottom(2f);
        lbl.setBorder(Rectangle.NO_BORDER);

        PdfPCell val = new PdfPCell(new Phrase(total.toPlainString() + " TND",
                new Font(Font.HELVETICA, 22, Font.BOLD, brand)));
        val.setBorderColor(brand);
        val.setBorderWidth(1.5f);
        val.setPaddingTop(4f); val.setPaddingLeft(12f); val.setPaddingBottom(10f);
        val.setBackgroundColor(tint(brand));

        box.addCell(lbl);
        box.addCell(val);
        return box;
    }

    private static PdfPCell sigBox(String label, Color brand) {
        PdfPCell c = new PdfPCell();
        c.setPaddingTop(8f); c.setPaddingBottom(32f);
        c.setPaddingLeft(10f); c.setPaddingRight(10f);
        c.setBorderColor(BORDER_GRAY);
        c.setBorderWidthTop(2f);
        c.setBorderColorTop(brand);
        c.addElement(new Paragraph(label, muted(8)));
        return c;
    }

    private static String resolveRef(Delivery delivery, Order order) {
        if (order != null && order.getErpOrderId() != null && !order.getErpOrderId().isBlank())
            return order.getErpOrderId();
        if (order != null && order.getErpExternalRef() != null && !order.getErpExternalRef().isBlank())
            return order.getErpExternalRef();
        return delivery.getId().toString().substring(0, 8).toUpperCase();
    }

    private static byte[] generateBarcode(String content) {
        try {
            String safeContent = content.replaceAll("[^A-Za-z0-9 \\-.]", "").trim();
            if (safeContent.isBlank()) safeContent = "REF";
            Code128Writer writer = new Code128Writer();
            Map<EncodeHintType, Object> hints = Map.of(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = writer.encode(safeContent, BarcodeFormat.CODE_128, 280, 52, hints);
            BufferedImage img = MatrixToImageWriter.toBufferedImage(matrix);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            log.warn("Barcode generation failed: {}", e.getMessage());
            return null;
        }
    }
}
