package com.asm.delivery.service;

import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.storage.MinioStorageService;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.awt.Color;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public abstract class BasePdfService {

    // ── Neutral palette ───────────────────────────────────────────────────────
    protected static final Color FALLBACK_BRAND  = new Color(30,  80, 160);   // used when no company color
    protected static final Color ROW_ALT         = new Color(249, 250, 251);
    protected static final Color BORDER_GRAY     = new Color(220, 220, 220);
    protected static final Color TEXT_MUTED      = new Color(107, 114, 128);
    protected static final Color TEXT_DARK       = new Color(17,  24,  39);
    protected static final Color BG_LIGHT        = new Color(248, 249, 250);

    // ── Page geometry ─────────────────────────────────────────────────────────
    protected static final float MARGIN_H = 40f;
    protected static final float MARGIN_T = 72f;   // header = 60px + 12px gap
    protected static final float MARGIN_B = 48f;   // footer = 32px + 16px gap

    protected static final DateTimeFormatter DATE_FR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    protected static final DateTimeFormatter DT_FR   = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    // ── Font factory ──────────────────────────────────────────────────────────
    private static final BaseFont BF_REG;
    private static final BaseFont BF_BOLD;

    static {
        try {
            BF_REG  = BaseFont.createFont(BaseFont.HELVETICA,      BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            BF_BOLD = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialise PDF base fonts", e);
        }
    }

    protected static Font regular(int size)              { return new Font(BF_REG,  size, Font.NORMAL, TEXT_DARK); }
    protected static Font bold(int size)                 { return new Font(BF_BOLD, size, Font.NORMAL, TEXT_DARK); }
    protected static Font muted(int size)                { return new Font(BF_REG,  size, Font.NORMAL, TEXT_MUTED); }
    protected static Font colored(int size, Color c)     { return new Font(BF_BOLD, size, Font.NORMAL, c); }
    protected static Font white(int size)                { return new Font(BF_BOLD, size, Font.NORMAL, Color.WHITE); }
    protected static Font regularWhite(int size)         { return new Font(BF_REG,  size, Font.NORMAL, Color.WHITE); }

    // ── Document bootstrap ────────────────────────────────────────────────────
    protected static Document newA4Document() {
        return new Document(PageSize.A4, MARGIN_H, MARGIN_H, MARGIN_T, MARGIN_B);
    }

    // ── Color utilities ───────────────────────────────────────────────────────
    protected static Color parseHex(String hex) {
        try {
            if (hex == null || hex.isBlank()) return FALLBACK_BRAND;
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            int rgb = Integer.parseInt(h, 16);
            return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        } catch (Exception e) {
            return FALLBACK_BRAND;
        }
    }

    /** Returns a very light tint (≈8% opacity) of the color — for table header backgrounds. */
    protected static Color tint(Color c) {
        int r = c.getRed()   + (255 - c.getRed())   * 92 / 100;
        int g = c.getGreen() + (255 - c.getGreen()) * 92 / 100;
        int b = c.getBlue()  + (255 - c.getBlue())  * 92 / 100;
        return new Color(Math.min(r, 255), Math.min(g, 255), Math.min(b, 255));
    }

    // ── Section heading ───────────────────────────────────────────────────────
    protected static Paragraph sectionLabel(String text, Color brand) {
        Paragraph p = new Paragraph(text, colored(8, brand));
        p.setSpacingBefore(12f);
        p.setSpacingAfter(5f);
        return p;
    }

    // ── Divider ───────────────────────────────────────────────────────────────
    protected static Paragraph divider() {
        Paragraph p = new Paragraph(" ");
        p.setSpacingBefore(3f);
        p.setSpacingAfter(3f);
        return p;
    }

    // ── Table cell helpers ────────────────────────────────────────────────────
    /** Column header: light brand tint background, bold text in brand color. */
    protected static PdfPCell hdrCell(String text, Color brand) {
        PdfPCell c = new PdfPCell(new Phrase(text.toUpperCase(), colored(7, brand)));
        c.setBackgroundColor(tint(brand));
        c.setPaddingTop(5f);
        c.setPaddingBottom(5f);
        c.setPaddingLeft(6f);
        c.setPaddingRight(6f);
        c.setBorderColor(BORDER_GRAY);
        c.setHorizontalAlignment(Element.ALIGN_LEFT);
        return c;
    }

    protected static PdfPCell hdrCellR(String text, Color brand) {
        PdfPCell c = hdrCell(text, brand);
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return c;
    }

    protected static PdfPCell cell(String text) {
        PdfPCell c = new PdfPCell(new Phrase(safe(text), regular(8)));
        c.setPaddingTop(5f);
        c.setPaddingBottom(5f);
        c.setPaddingLeft(6f);
        c.setPaddingRight(6f);
        c.setBorderColor(BORDER_GRAY);
        c.setHorizontalAlignment(Element.ALIGN_LEFT);
        return c;
    }

    protected static PdfPCell cellB(String text) {
        PdfPCell c = new PdfPCell(new Phrase(safe(text), bold(8)));
        c.setPaddingTop(5f); c.setPaddingBottom(5f);
        c.setPaddingLeft(6f); c.setPaddingRight(6f);
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

    protected static PdfPCell noBorderCell() {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        return c;
    }

    // ── KPI box (metric card) ─────────────────────────────────────────────────
    protected static PdfPTable kpiBox(String label, String value, Color brand) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);

        PdfPCell top = new PdfPCell(new Phrase(label, muted(7)));
        top.setBorder(Rectangle.NO_BORDER);
        top.setBackgroundColor(tint(brand));
        top.setPaddingTop(7f); top.setPaddingLeft(8f); top.setPaddingBottom(2f);

        PdfPCell bot = new PdfPCell(new Phrase(value, colored(15, brand)));
        bot.setBorderColor(BORDER_GRAY);
        bot.setBorder(Rectangle.BOTTOM | Rectangle.LEFT | Rectangle.RIGHT);
        bot.setBackgroundColor(tint(brand));
        bot.setPaddingBottom(8f); bot.setPaddingLeft(8f);

        t.addCell(top);
        t.addCell(bot);
        return t;
    }

    // ── Info box (key/value card) ─────────────────────────────────────────────
    protected static PdfPCell infoBox(String title, String[][] rows, Color brand) {
        PdfPCell outer = new PdfPCell();
        outer.setPaddingTop(8f);
        outer.setPaddingBottom(8f);
        outer.setPaddingLeft(10f);
        outer.setPaddingRight(10f);
        outer.setBorderColor(BORDER_GRAY);
        outer.setBorderWidthLeft(3f);
        outer.setBorderColorLeft(brand);

        Paragraph titlePara = new Paragraph(title, colored(7, brand));
        titlePara.setSpacingAfter(5f);
        outer.addElement(titlePara);

        for (String[] row : rows) {
            Paragraph p = new Paragraph();
            p.add(new Chunk(row[0] + ": ", muted(8)));
            p.add(new Chunk(safe(row[1]), bold(8)));
            p.setSpacingBefore(2f);
            outer.addElement(p);
        }
        return outer;
    }

    // ── Summary card (right side of header) ──────────────────────────────────
    protected static PdfPCell summaryBox(String[][] rows, Color brand) {
        PdfPCell outer = new PdfPCell();
        outer.setBackgroundColor(BG_LIGHT);
        outer.setBorderColor(BORDER_GRAY);
        outer.setPaddingTop(10f);
        outer.setPaddingBottom(10f);
        outer.setPaddingLeft(10f);
        outer.setPaddingRight(10f);

        for (String[] row : rows) {
            Paragraph p = new Paragraph();
            p.add(new Chunk(row[0] + "  ", muted(8)));
            p.add(new Chunk(safe(row[1]), bold(8)));
            p.setSpacingBefore(3f);
            outer.addElement(p);
        }
        return outer;
    }

    // ── Company resolution ────────────────────────────────────────────────────
    protected static ReportPageEvent pageEvent(String docType, String subtitle) {
        return new ReportPageEvent(docType, subtitle, "ASM Track", null, FALLBACK_BRAND);
    }

    // ── String safety ─────────────────────────────────────────────────────────
    protected static String safe(String s) { return s != null && !s.isBlank() ? s : "-"; }

    protected static String fmtDuration(double minutes) {
        if (minutes <= 0) return "-";
        int m = (int) Math.round(minutes);
        if (m < 60) return m + " min";
        return (m / 60) + "h" + String.format("%02d", m % 60);
    }

    // ── Header / footer page event ────────────────────────────────────────────
    public static class ReportPageEvent extends PdfPageEventHelper {

        private final String docType;
        private final String subtitle;
        private final String companyName;
        private final byte[] logoBytes;
        private final Color  brandColor;
        private       Image  logoImage;

        public ReportPageEvent(String docType, String subtitle,
                               String companyName, byte[] logoBytes, Color brandColor) {
            this.docType     = docType;
            this.subtitle    = subtitle;
            this.companyName = companyName != null ? companyName : "ASM Track";
            this.logoBytes   = logoBytes;
            this.brandColor  = brandColor != null ? brandColor : FALLBACK_BRAND;
        }

        public Color getPrimaryColor() { return brandColor; }
        public String getCompanyName() { return companyName; }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            if (logoBytes != null && logoBytes.length > 0) {
                try {
                    logoImage = Image.getInstance(logoBytes);
                    logoImage.scaleToFit(90, 38);
                } catch (Exception e) {
                    logoImage = null;
                }
            }
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            Rectangle       r = document.getPageSize();
            float pw = r.getWidth();
            float ph = r.getHeight();

            // ── Header zone (60px from top) ───────────────────────────────────
            // White background — page default, no fill needed.
            // Brand accent: 4px left strip
            cb.saveState();
            cb.setColorFill(brandColor);
            cb.rectangle(0, ph - 60, 4f, 60f);
            cb.fill();
            cb.restoreState();

            // Brand bottom line (full width, 1.5px)
            cb.saveState();
            cb.setColorStroke(brandColor);
            cb.setLineWidth(1.5f);
            cb.moveTo(0, ph - 61f);
            cb.lineTo(pw, ph - 61f);
            cb.stroke();
            cb.restoreState();

            // Logo or company name — left
            float logoX = MARGIN_H;
            if (logoImage != null) {
                try {
                    logoImage.setAbsolutePosition(logoX, ph - 52f);
                    cb.addImage(logoImage);
                    // Company name small below logo
                    ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                            new Phrase(companyName, new Font(BF_REG_STATIC, 7, Font.NORMAL, TEXT_MUTED)),
                            logoX, ph - 57f, 0);
                } catch (Exception e) {
                    ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                            new Phrase(companyName, new Font(BF_BOLD_STATIC, 11, Font.NORMAL, brandColor)),
                            logoX, ph - 28f, 0);
                }
            } else {
                ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                        new Phrase(companyName, new Font(BF_BOLD_STATIC, 11, Font.NORMAL, brandColor)),
                        logoX, ph - 28f, 0);
            }

            // Document type — right, bold dark
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                    new Phrase(docType, new Font(BF_BOLD_STATIC, 9, Font.NORMAL, TEXT_DARK)),
                    pw - MARGIN_H, ph - 25f, 0);

            // Subtitle — right, muted small
            if (subtitle != null && !subtitle.isBlank()) {
                ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                        new Phrase(subtitle, new Font(BF_REG_STATIC, 7, Font.NORMAL, TEXT_MUTED)),
                        pw - MARGIN_H, ph - 40f, 0);
            }

            // ── Footer ────────────────────────────────────────────────────────
            cb.saveState();
            cb.setColorStroke(BORDER_GRAY);
            cb.setLineWidth(0.5f);
            cb.moveTo(MARGIN_H, 35f);
            cb.lineTo(pw - MARGIN_H, 35f);
            cb.stroke();
            cb.restoreState();

            Font footerFont = new Font(BF_REG_STATIC, 7, Font.NORMAL, TEXT_MUTED);
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                    new Phrase("Page " + writer.getPageNumber(), footerFont),
                    MARGIN_H, 23f, 0);
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER,
                    new Phrase(companyName, footerFont),
                    pw / 2, 23f, 0);
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                    new Phrase("Généré le " + LocalDateTime.now().format(DT_FR), footerFont),
                    pw - MARGIN_H, 23f, 0);
        }

        // Static font references for use inside the inner class
        private static BaseFont BF_REG_STATIC;
        private static BaseFont BF_BOLD_STATIC;

        static {
            try {
                BF_REG_STATIC  = BaseFont.createFont(BaseFont.HELVETICA,      BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
                BF_BOLD_STATIC = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot init inner class fonts", e);
            }
        }
    }
}
