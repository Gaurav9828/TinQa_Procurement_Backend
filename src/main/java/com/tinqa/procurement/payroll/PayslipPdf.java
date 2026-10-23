package com.tinqa.procurement.payroll;

import com.tinqa.procurement.company.CompanyLetterhead;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

/**
 * Lays out a payslip on one A4 page: the company, the employee, the month's earnings beside its deductions, and the
 * net pay in figures and words. Everything comes in a {@link Payslip}; nothing is looked up here.
 */
@Component
public class PayslipPdf {

    /** One amount on the earnings or deductions side. */
    public record Row(String label, BigDecimal amount) {
    }

    public record Payslip(CompanyLetterhead company, String period, String employeeName, String employeeCode,
                          String designation, String department, String joiningDate, String pan, String transferDate,
                          String bankReference, List<Row> earnings, BigDecimal totalEarnings, List<Row> deductions,
                          BigDecimal totalDeductions, BigDecimal netPay, String confirmedBy, String confirmedOn) {
    }

    private static final float MARGIN = 48;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;
    private static final float RIGHT = MARGIN + WIDTH;
    private static final float HALF = MARGIN + WIDTH / 2;
    private static final float COLUMN_GAP = 12;
    private static final Color ACCENT = new Color(0, 113, 227);
    private static final Color MUTED = new Color(110, 110, 115);
    private static final Color RULE = new Color(229, 229, 234);
    private static final Color PANEL = new Color(242, 247, 255);
    private static final int LABEL_CHARS = 38;

    private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    public byte[] render(Payslip slip) {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                Writer writer = new Writer(content, PDRectangle.A4.getHeight() - MARGIN);
                header(writer, slip);
                employee(writer, slip);
                amounts(writer, slip);
                net(writer, slip);
                footer(writer, slip);
            }
            pdf.save(out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create the payslip for " + slip.employeeCode() + ", " + slip.period(), exception);
        }
    }

    private void header(Writer w, Payslip slip) throws IOException {
        w.text(bold, 20, MARGIN, slip.company().brandName(), ACCENT);
        w.right(bold, 14, RIGHT, "PAYSLIP", Color.BLACK);
        w.y -= 16;
        w.right(regular, 10, RIGHT, slip.period(), Color.BLACK);
        for (String line : slip.company().detailLines()) {
            w.text(regular, 9, MARGIN, line, MUTED);
            w.y -= 12;
        }
        w.y -= 8;
        w.rule(w.y);
        w.y -= 20;
    }

    private void employee(Writer w, Payslip slip) throws IOException {
        String[][] left = {{"Employee", slip.employeeName()}, {"Employee code", slip.employeeCode()},
                {"Designation", slip.designation()}, {"Department", slip.department()}};
        String[][] right = {{"Pay period", slip.period()}, {"Date of joining", slip.joiningDate()},
                {"PAN", slip.pan()}, {"Paid on", slip.transferDate()}, {"Bank reference", slip.bankReference()}};
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            if (i < left.length) {
                field(w, MARGIN, left[i]);
            }
            if (i < right.length) {
                field(w, HALF + COLUMN_GAP, right[i]);
            }
            w.y -= 15;
        }
        w.y -= 12;
    }

    private void field(Writer w, float x, String[] labelAndValue) throws IOException {
        w.text(regular, 9, x, labelAndValue[0], MUTED);
        w.text(bold, 9.5f, x + 92, fit(labelAndValue[1] == null || labelAndValue[1].isBlank() ? "-" : labelAndValue[1], 34), Color.BLACK);
    }

    private void amounts(Writer w, Payslip slip) throws IOException {
        float leftEnd = HALF - COLUMN_GAP;
        float rightStart = HALF + COLUMN_GAP;
        w.text(bold, 9, MARGIN, "EARNINGS", MUTED);
        w.right(bold, 9, leftEnd, "AMOUNT", MUTED);
        w.text(bold, 9, rightStart, "DEDUCTIONS", MUTED);
        w.right(bold, 9, RIGHT, "AMOUNT", MUTED);
        w.y -= 6;
        w.rule(w.y);
        w.y -= 15;
        int rows = Math.max(Math.max(slip.earnings().size(), slip.deductions().size()), 1);
        for (int i = 0; i < rows; i++) {
            if (i < slip.earnings().size()) {
                Row row = slip.earnings().get(i);
                w.text(regular, 10, MARGIN, fit(row.label(), LABEL_CHARS), Color.BLACK);
                w.right(regular, 10, leftEnd, PayrollPeriods.rupees(row.amount()), Color.BLACK);
            }
            if (i < slip.deductions().size()) {
                Row row = slip.deductions().get(i);
                w.text(regular, 10, rightStart, fit(row.label(), LABEL_CHARS), Color.BLACK);
                w.right(regular, 10, RIGHT, PayrollPeriods.rupees(row.amount()), Color.BLACK);
            } else if (i == 0) {
                w.text(regular, 10, rightStart, "No deductions", MUTED);
            }
            w.y -= 16;
        }
        w.rule(w.y + 10);
        w.y -= 6;
        w.text(bold, 10, MARGIN, "Total earnings", Color.BLACK);
        w.right(bold, 10, leftEnd, PayrollPeriods.rupees(slip.totalEarnings()), Color.BLACK);
        w.text(bold, 10, rightStart, "Total deductions", Color.BLACK);
        w.right(bold, 10, RIGHT, PayrollPeriods.rupees(slip.totalDeductions()), Color.BLACK);
        w.y -= 28;
    }

    private void net(Writer w, Payslip slip) throws IOException {
        float height = 46;
        w.panel(w.y - height + 14, height);
        w.text(bold, 12, MARGIN + 12, "Net pay (transferred)", Color.BLACK);
        w.right(bold, 14, RIGHT - 12, PayrollPeriods.rupees(slip.netPay()), ACCENT);
        w.y -= 18;
        w.text(regular, 9, MARGIN + 12, fit(AmountInWords.rupees(slip.netPay()), 100), MUTED);
        w.y -= 40;
    }

    private void footer(Writer w, Payslip slip) throws IOException {
        w.text(regular, 8.5f, MARGIN, "Salary transfer confirmed by " + slip.confirmedBy() + " on " + slip.confirmedOn() + ".", MUTED);
        w.y -= 11;
        w.text(regular, 8.5f, MARGIN, "This is a computer-generated payslip and does not need a signature.", MUTED);
    }

    private static String fit(String value, int max) {
        return value == null ? "" : value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    /** The standard PDF fonts cover Latin-1 only; anything else (e.g. names in other scripts) prints as "?". */
    static String latin1(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(c -> out.append(c >= 0x20 && c <= 0xFF && c != 0x7F ? (char) c : '?'));
        return out.toString();
    }

    /** Where on the page the next line goes. */
    private static final class Writer {
        private final PDPageContentStream content;
        private float y;

        Writer(PDPageContentStream content, float y) {
            this.content = content;
            this.y = y;
        }

        void text(PDType1Font font, float size, float x, String value, Color color) throws IOException {
            content.beginText();
            content.setFont(font, size);
            content.setNonStrokingColor(color);
            content.newLineAtOffset(x, y);
            content.showText(latin1(value));
            content.endText();
        }

        void right(PDType1Font font, float size, float rightEdge, String value, Color color) throws IOException {
            float width = font.getStringWidth(latin1(value)) / 1000 * size;
            text(font, size, rightEdge - width, value, color);
        }

        void rule(float at) throws IOException {
            content.setStrokingColor(RULE);
            content.moveTo(MARGIN, at);
            content.lineTo(RIGHT, at);
            content.stroke();
        }

        void panel(float bottom, float height) throws IOException {
            content.setNonStrokingColor(PANEL);
            content.addRect(MARGIN, bottom, WIDTH, height);
            content.fill();
        }
    }
}
