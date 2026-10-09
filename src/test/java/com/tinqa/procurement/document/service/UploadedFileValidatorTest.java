package com.tinqa.procurement.document.service;

import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.document.constant.DocumentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class UploadedFileValidatorTest {

    private static final byte[] PDF = "%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};

    private final UploadedFileValidator validator = new UploadedFileValidator();

    @Test
    void realPdfIsAcceptedAndContentTypeComesFromTheServer() {
        var result = validator.validate(new MockMultipartFile("file", "Invoice 2026 (final).pdf", "text/html", PDF), DocumentType.PDF);

        assertEquals("Invoice 2026 (final).pdf", result.originalFileName());
        assertEquals("application/pdf", result.contentType());
    }

    @Test
    void contentMustMatchExtension() {
        assertThrows(ApiException.class,
                () -> validator.validate(new MockMultipartFile("file", "photo.png", "image/png", PDF), DocumentType.IMAGE));
    }

    @Test
    void extensionMustMatchDeclaredDocumentType() {
        assertThrows(ApiException.class,
                () -> validator.validate(new MockMultipartFile("file", "photo.png", "image/png", PNG), DocumentType.PDF));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page.html", "run.exe", "script.svg", "../../etc/passwd.pdf", "a<b>.pdf", "x\".pdf", "noextension", ".pdf"})
    void dangerousNamesAndTypesAreRejected(String name) {
        assertThrows(ApiException.class,
                () -> validator.validate(new MockMultipartFile("file", name, "application/pdf", PDF), DocumentType.OTHER), name);
    }

    @Test
    void csvWithMarkupIsRejected() {
        byte[] csv = "name,notes\nA,<script>alert(1)</script>\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(ApiException.class,
                () -> validator.validate(new MockMultipartFile("file", "list.csv", "text/csv", csv), DocumentType.EXCEL));
    }

    @Test
    void emptyFileIsRejected() {
        assertThrows(ApiException.class,
                () -> validator.validate(new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[0]), DocumentType.PDF));
    }
}
