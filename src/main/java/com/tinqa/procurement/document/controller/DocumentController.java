package com.tinqa.procurement.document.controller;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.document.constant.DocumentCategory;
import com.tinqa.procurement.document.constant.DocumentPurpose;
import com.tinqa.procurement.document.constant.DocumentReferenceType;
import com.tinqa.procurement.document.constant.DocumentStage;
import com.tinqa.procurement.document.constant.DocumentStatus;
import com.tinqa.procurement.document.dto.DocumentApprovalRequest;
import com.tinqa.procurement.document.dto.DocumentDownload;
import com.tinqa.procurement.document.dto.DocumentFilter;
import com.tinqa.procurement.document.dto.DocumentListResponse;
import com.tinqa.procurement.document.dto.DocumentResponse;
import com.tinqa.procurement.document.dto.DocumentUploadRequest;
import com.tinqa.procurement.document.service.DocumentService;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.RequiresPermission;
import com.tinqa.procurement.security.config.Authorities;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Everyone signed in may upload, read and delete their own documents; doing so for others' documents needs the
 * DOCUMENT_* permissions, checked in DocumentServiceImpl. Listing all documents and approving them are permissions here.
 */
@RestController
@RequestMapping("/v1/documents")
@PreAuthorize(Authorities.AUTHENTICATED)
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocumentResponse>> uploadDocument(@RequestPart("file") MultipartFile file,
                                                                        @RequestPart("meta") @Valid DocumentUploadRequest request) {
        return ApiResponses.ok(documentService.uploadDocument(file, request), "Document uploaded successfully");
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DocumentResponse>> getDocumentById(@PathVariable Long id) {
        return ApiResponses.ok(documentService.getDocumentById(id), "Document details fetched successfully");
    }

    // Always served as an attachment with the stored (validated) content type
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadDocument(@PathVariable Long id) {
        DocumentDownload download = documentService.downloadDocument(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(download.resource());
    }

    @RequiresPermission(Permission.DOCUMENT_VIEW)
    @GetMapping
    public ResponseEntity<ApiResponse<List<DocumentListResponse>>> getAllDocuments(
            @RequestParam(required = false) DocumentReferenceType referenceType,
            @RequestParam(required = false) Long referenceId,
            @RequestParam(required = false) DocumentPurpose purpose,
            @RequestParam(required = false) DocumentStage stage,
            @RequestParam(required = false) DocumentCategory category,
            @RequestParam(required = false) DocumentStatus status) {
        DocumentFilter filter = new DocumentFilter(referenceType, referenceId, purpose, stage, category, status);
        return ApiResponses.ok(documentService.getAllDocumentsForListing(filter), "Documents retrieved successfully");
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<ApiResponse<List<DocumentResponse>>> getDocumentsByUserId(@PathVariable Long userId) {
        return ApiResponses.ok(documentService.getDocumentsByUserId(userId), "User documents fetched successfully");
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteDocument(@PathVariable Long id) {
        documentService.deleteDocument(id);
        return ApiResponses.ok("Document deleted successfully");
    }

    @RequiresPermission(Permission.DOCUMENT_APPROVE)
    @PatchMapping("/{id}/approval")
    public ResponseEntity<ApiResponse<DocumentResponse>> processAdminL2Approval(@PathVariable Long id,
                                                                                @Valid @RequestBody DocumentApprovalRequest request) {
        return ApiResponses.ok(documentService.processAdminL2Approval(id, request), "Document status updated successfully");
    }
}
