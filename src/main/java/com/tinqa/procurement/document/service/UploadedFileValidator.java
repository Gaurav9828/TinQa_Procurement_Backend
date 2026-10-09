package com.tinqa.procurement.document.service;

import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.validation.InputSafety;
import com.tinqa.procurement.document.constant.DocumentType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Accepts only real documents: allow-listed extension, content that starts like that kind of file, a plain
 * file name, and a size limit. The content type that is stored (and later served) is decided here, never
 * taken from the client.
 */
@Component
public class UploadedFileValidator {

    public static final long MAX_FILE_SIZE_BYTES = 25L * 1024 * 1024;

    private static final Pattern FILE_NAME = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N} ._()-]{0,250}\\.[A-Za-z0-9]{1,5}$");

    private record FileKind(String contentType, DocumentType documentType, byte[]... signatures) {
    }

    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] ZIP = {'P', 'K', 0x03, 0x04};
    private static final byte[] OLE = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private static final Map<String, FileKind> KINDS = Map.of(
            "pdf", new FileKind("application/pdf", DocumentType.PDF, PDF),
            "png", new FileKind("image/png", DocumentType.IMAGE, PNG),
            "jpg", new FileKind("image/jpeg", DocumentType.IMAGE, JPEG),
            "jpeg", new FileKind("image/jpeg", DocumentType.IMAGE, JPEG),
            "webp", new FileKind("image/webp", DocumentType.IMAGE),
            "doc", new FileKind("application/msword", DocumentType.WORD, OLE),
            "docx", new FileKind("application/vnd.openxmlformats-officedocument.wordprocessingml.document", DocumentType.WORD, ZIP),
            "xls", new FileKind("application/vnd.ms-excel", DocumentType.EXCEL, OLE),
            "xlsx", new FileKind("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", DocumentType.EXCEL, ZIP),
            "csv", new FileKind("text/csv", DocumentType.EXCEL));

    public static final List<String> ALLOWED_EXTENSIONS = List.of("pdf", "png", "jpg", "jpeg", "webp", "doc", "docx", "xls", "xlsx", "csv");

    public record ValidatedFile(String originalFileName, String contentType) {
    }

    public ValidatedFile validate(MultipartFile file, DocumentType declaredType) {
        if (file == null || file.isEmpty()) {
            throw invalid("The file is empty.");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw invalid("The file is larger than 25 MB.");
        }

        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().trim();
        String nameViolation = InputSafety.findViolation(name);
        if (name.isEmpty() || nameViolation != null || name.contains("..") || !FILE_NAME.matcher(name).matches()) {
            throw invalid("File name must be a plain name such as invoice_2026.pdf (letters, numbers, spaces, . _ ( ) -, up to 255 characters).");
        }

        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        FileKind kind = KINDS.get(extension);
        if (kind == null) {
            throw invalid("Only these file types are allowed: " + String.join(", ", ALLOWED_EXTENSIONS) + ".");
        }
        if (declaredType != null && declaredType != DocumentType.OTHER && declaredType != kind.documentType()) {
            throw invalid("A ." + extension + " file cannot be uploaded as document type " + declaredType + ".");
        }

        byte[] head = readHead(file, 4096);
        if (!contentMatches(extension, kind, head)) {
            throw invalid("The file content does not match its ." + extension + " extension.");
        }
        return new ValidatedFile(name, kind.contentType());
    }

    private boolean contentMatches(String extension, FileKind kind, byte[] head) {
        if (extension.equals("webp")) {
            return head.length >= 12 && startsWith(head, new byte[]{'R', 'I', 'F', 'F'})
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
        }
        if (extension.equals("csv")) {
            return isUtf8Text(head) && InputSafety.findViolation(new String(head, StandardCharsets.UTF_8), true) == null;
        }
        return Arrays.stream(kind.signatures()).anyMatch(signature -> startsWith(head, signature));
    }

    private boolean isUtf8Text(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(trimPartialCharacter(bytes)));
            return true;
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    // The 4 KB sample may end in the middle of a multi-byte character
    private byte[] trimPartialCharacter(byte[] bytes) {
        int end = bytes.length;
        int back = 0;
        while (end - back - 1 >= 0 && back < 3 && (bytes[end - back - 1] & 0xC0) == 0x80) {
            back++;
        }
        if (end - back - 1 >= 0 && (bytes[end - back - 1] & 0xC0) == 0xC0) {
            return Arrays.copyOf(bytes, end - back - 1);
        }
        return bytes;
    }

    private boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private byte[] readHead(MultipartFile file, int limit) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(limit);
        } catch (IOException exception) {
            throw invalid("The file could not be read.");
        }
    }

    private ApiException invalid(String message) {
        return new ApiException(message, "INVALID_FILE", HttpStatus.BAD_REQUEST,
                List.of(new ApiResponse.ApiErrorItem("file", message)));
    }
}
