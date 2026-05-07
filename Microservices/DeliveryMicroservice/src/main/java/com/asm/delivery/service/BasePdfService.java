package com.asm.delivery.service;

import com.asm.delivery.entity.Company;
import com.asm.delivery.repository.CompanyRepository;
import com.asm.delivery.security.UserPrincipal;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.awt.Color;
import java.io.IOException;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Shared PDF utilities for all report services.
 * Uses OpenPDF (lowagie) with WinAnsi-encoded Helvetica — full French character support.
 */
public abstract class BasePdfService {

    // ── Brand palette ─────────────────────────────────────────────────────────
    protected static final java.awt.Color BRAND_ORANGE   = new java.awt.Color(255, 87,  34);
    protected static final java.awt.Color BRAND_ORANGE_D = new java.awt.Color(220, 70,  20);
    protected static final java.awt.Color ROW_ALT        = new java.awt.Color(250, 250, 250);
    protected static final java.awt.Color BORDER_GRAY    = new java.awt.Color(220, 220, 220);
    protected static final java.awt.Color TEXT_MUTED     = new java.awt.Color(113, 113, 122);
    protected static final java.awt.Color BG_HEADER_ROW  = new java.awt.Color(245, 245, 245);

    // ── Page geometry ─────────────────────────────────────────────────────────
    protected static final float MARGIN_H  = 40f;   // horizontal
    protected static final float MARGIN_T  = 65f;   // top  (header bar = 50px + 15px gap)
    protected static final float MARGIN_B  = 45f;   // bottom (footer = 30px + 15px gap)

    protected static final DateTimeFormatter DATE_FR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    protected static final DateTimeFormatter DT_FR   = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    // ── Font factory ──────────────────────────────────────────────────────────
    private static BaseFont BF_REG;
    private static BaseFont BF_BOLD;

    static {
        try {
            BF_REG  = BaseFont.createFont(BaseFont.HELVETICA,      BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            BF_BOLD = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialise PDF base fonts", e);
        }
    }

    protected static Font regular(int size)  { return new Font(BF_REG,  size, Font.NORMAL, java.awt.Color.BLACK); }
    protected static Font bold(int size)      { return new Font(BF_BOLD, size, Font.NORMAL, java.awt.Color.BLACK); }
    protected static Font white(int size)     { return new Font(BF_BOLD, size, Font.NORMAL, java.awt.Color.WHITE); }
    protected static Font muted(int size)     { return new Font(BF_REG,  size, Font.NORMAL, TEXT_MUTED); }
    protected static Font orange(int size)    { return new Font(BF_BOLD, size, Font.NORMAL, BRAND_ORANGE); }
    protected static Font boldWhite(int size) { return new Font(BF_BOLD, size, Font.NORMAL, java.awt.Color.WHITE); }

    // ── Document bootstrap ────────────────────────────────────────────────────
    protected static Document newA4Document() {
        return new Document(PageSize.A4, MARGIN_H, MARGIN_H, MARGIN_T, MARGIN_B);
    }

    // ── Section heading ───────────────────────────────────────────────────────
    protected static Paragraph sectionLabel(String text) {
        Paragraph p = new Paragraph(text, orange(9));
        p.setSpacingBefore(12f);
        p.setSpacingAfter(4f);
        return p;
    }

    // ── Divider ───────────────────────────────────────────────────────────────
    protected static Paragraph divider() {
        Paragraph p = new Paragraph(" ");
        p.setSpacingBefore(2f);
        p.setSpacingAfter(2f);
        return p;
    }

    // ── Table cell helpers ────────────────────────────────────────────────────
    protected static PdfPCell hdrCell(String text) {
        PdfPCell c = new PdfPCell(new Phrase(text, bold(8)));
        c.setBackgroundColor(BG_HEADER_ROW);
        c.setPadding(5f);
        c.setBorderColor(BORDER_GRAY);
        c.setHorizontalAlignment(Element.ALIGN_LEFT);
        return c;
    }

    protected static PdfPCell hdrCellR(String text) {
        PdfPCell c = hdrCell(text);
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return c;
    }

    protected static PdfPCell cell(String text) {
        PdfPCell c = new PdfPCell(new Phrase(safe(text), regular(8)));
        c.setPadding(5f);
        c.setBorderColor(BORDER_GRAY);
        c.setHorizontalAlignment(Element.ALIGN_LEFT);
        return c;
    }

    protected static PdfPCell cellB(String text) {
        PdfPCell c = new PdfPCell(new Phrase(safe(text), bold(8)));
        c.setPadding(5f);
        c.setBorderColor(BORDER_GRAY);
        c.setHorizontalAlignment(Element.ALIGN_LEFT);
        return c;
    }

    protected static PdfPCell cellR(String text) {
        PdfPCell c = cell(text);
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return c;
    }

    protected static PdfPCell cellC(String text) {
        PdfPCell c = cell(text);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        return c;
    }

    protected static PdfPCell cellAlt(String text, boolean alt) {
        PdfPCell c = cell(text);
        if (alt) c.setBackgroundColor(ROW_ALT);
        return c;
    }

    protected static PdfPCell cellRAlt(String text, boolean alt) {
        PdfPCell c = cellR(text);
        if (alt) c.setBackgroundColor(ROW_ALT);
        return c;
    }

    // Orange KPI box: label on top, big value below
    protected static PdfPTable kpiBox(String label, String value) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        PdfPCell top = new PdfPCell(new Phrase(label, muted(7)));
        top.setBorder(Rectangle.NO_BORDER);
        top.setBackgroundColor(BG_HEADER_ROW);
        top.setPaddingTop(6f);
        top.setPaddingLeft(8f);
        top.setPaddingBottom(2f);
        PdfPCell bot = new PdfPCell(new Phrase(value, bold(16)));
        bot.setBorder(Rectangle.BOTTOM | Rectangle.LEFT | Rectangle.RIGHT);
        bot.setBorderColor(BORDER_GRAY);
        bot.setBackgroundColor(BG_HEADER_ROW);
        bot.setPaddingBottom(8f);
        bot.setPaddingLeft(8f);
        t.addCell(top);
        t.addCell(bot);
        return t;
    }

    // ── Company resolution ────────────────────────────────────────────────────

    protected static ReportPageEvent pageEvent(String docType, String subtitle,
                                               CompanyRepository companyRepo) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal
                && principal.getCompanyId() != null) {
            Company company = companyRepo.findById(UUID.fromString(principal.getCompanyId()))
                    .orElse(null);
            if (company != null) {
                return new ReportPageEvent(docType, subtitle, company.getName(), company.getLogoUrl());
            }
        }
        return new ReportPageEvent(docType, subtitle, "ASM Track", null);
    }

    // ── String safety ─────────────────────────────────────────────────────────
    protected static String safe(String s) {
        return s != null ? s : "-";
    }

    protected static String fmtDuration(double minutes) {
        if (minutes <= 0) return "-";
        int m = (int) Math.round(minutes);
        if (m < 60) return m + " min";
        return (m / 60) + "h" + String.format("%02d", m % 60);
    }

    // ── Header/footer page event ──────────────────────────────────────────────
    public static class ReportPageEvent extends PdfPageEventHelper {

        private final String  docType;
        private final String  subtitle;
        private final String  companyName;
        private final String  logoUrl;
        private       Image   logoImage;   // loaded once on first page

        public ReportPageEvent(String docType, String subtitle, String companyName, String logoUrl) {
            this.docType     = docType;
            this.subtitle    = subtitle;
            this.companyName = companyName != null ? companyName : "ASM Track";
            this.logoUrl     = logoUrl;
        }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            if (logoUrl != null && !logoUrl.isBlank()) {
                try {
                    logoImage = Image.getInstance(new URL(logoUrl));
                    logoImage.scaleToFit(80, 28);
                } catch (Exception e) {
                    logoImage = null; // fall back to text name
                }
            }
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb  = writer.getDirectContent();
            Rectangle      r   = document.getPageSize();
            float          pw  = r.getWidth();
            float          ph  = r.getHeight();

            // ── Orange header bar ─────────────────────────────────────────────
            cb.saveState();
            cb.setColorFill(new Color(255, 87, 34));
            cb.rectangle(0, ph - 50, pw, 50);
            cb.fill();

            cb.setColorFill(new Color(220, 70, 20));
            cb.rectangle(0, ph - 52, pw, 2);
            cb.fill();
            cb.restoreState();

            // Logo or company name — left
            if (logoImage != null) {
                try {
                    logoImage.setAbsolutePosition(MARGIN_H, ph - 43);
                    cb.addImage(logoImage);
                } catch (Exception e) {
                    ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                            new Phrase(companyName, boldWhite(13)), MARGIN_H, ph - 31, 0);
                }
            } else {
                ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                        new Phrase(companyName, boldWhite(13)), MARGIN_H, ph - 31, 0);
            }

            // Document type — right (white)
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                    new Phrase(docType, boldWhite(10)), pw - MARGIN_H, ph - 31, 0);

            // Subtitle (small white)
            if (subtitle != null && !subtitle.isBlank()) {
                ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                        new Phrase(subtitle, new Font(Font.HELVETICA, 7, Font.NORMAL, java.awt.Color.WHITE)),
                        MARGIN_H, ph - 43, 0);
            }

            // ── Footer ────────────────────────────────────────────────────────
            cb.saveState();
            cb.setColorStroke(new Color(220, 220, 220));
            cb.moveTo(MARGIN_H, 32);
            cb.lineTo(pw - MARGIN_H, 32);
            cb.stroke();
            cb.restoreState();

            String pageInfo = "Page " + writer.getPageNumber();
            String genDate  = "Généré le " + LocalDateTime.now().format(DT_FR);
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                    new Phrase(pageInfo, new Font(Font.HELVETICA, 7, Font.NORMAL, new java.awt.Color(113, 113, 122))),
                    MARGIN_H, 20, 0);
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                    new Phrase(genDate,  new Font(Font.HELVETICA, 7, Font.NORMAL, new java.awt.Color(113, 113, 122))),
                    pw - MARGIN_H, 20, 0);
        }
    }
}
