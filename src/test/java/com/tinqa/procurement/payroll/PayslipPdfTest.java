package com.tinqa.procurement.payroll;

import com.tinqa.procurement.company.CompanyLetterhead;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PayslipPdfTest {

    @Test
    void fitsOnOnePageWithTheMostLinesAllowed() throws Exception {
        List<PayslipPdf.Row> earnings = new ArrayList<>(List.of(new PayslipPdf.Row("Monthly salary", new BigDecimal("50000"))));
        List<PayslipPdf.Row> deductions = new ArrayList<>();
        for (int i = 0; i < PayrollDTOs.MAX_LINES; i++) {
            earnings.add(new PayslipPdf.Row("Overtime week " + i, new BigDecimal("100")));
            deductions.add(new PayslipPdf.Row("Advance recovery " + i, new BigDecimal("50")));
        }
        CompanyLetterhead company = new CompanyLetterhead("TinQa", "TinQa Technologies Pvt Ltd", "12 MG Road, Bengaluru, Karnataka 560001",
                "29ABCDE1234F1Z5", "ABCDE1234F", "support@tinqa.in", "+91 90000 00000");
        byte[] pdf = new PayslipPdf().render(new PayslipPdf.Payslip(company, "October 2026", "Asha Verma", "EMP-0007",
                "Delivery Rider / Courier", "Logistics", "01 Jan 2024", "ABCPV1234K", "31 Oct 2026", "UTR123456789",
                earnings, new BigDecimal("52000"), deductions, new BigDecimal("1000"), new BigDecimal("51000"),
                "finance.l2", "01 Nov 2026"));
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("PAYSLIP", "Asha Verma", "Rs. 51,000.00", "Rupees Fifty One Thousand Only");
        }
    }
}
