package com.tinqa.procurement.order.invoice;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * The text of an invoice PDF, line by line in reading order. A scanned (image only), password protected or damaged PDF
 * gives no text; that is a result, not an error, since the invoice then simply can't be checked automatically.
 */
@Slf4j
@Component
public class InvoicePdfReader {

    // An invoice is a page or two; reading a long document fully would only cost time and memory
    private static final int MAX_PAGES = 10;

    public String readText(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            // Keeps a label and its amount, printed side by side in a table, on the same line
            stripper.setSortByPosition(true);
            stripper.setEndPage(MAX_PAGES);
            return stripper.getText(document);
        } catch (IOException | RuntimeException exception) {
            log.info("Invoice PDF could not be read: {}", exception.getMessage());
            return "";
        }
    }
}
