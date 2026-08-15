package com.tinqa.procurement.document.controller;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.document.constant.*;
import com.tinqa.procurement.document.dto.*;
import com.tinqa.procurement.document.service.DocumentService;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocumentResponse>> uploadDocument(
            @RequestPart("file") MultipartFile file,
            @RequestPart("meta") @Valid DocumentUploadRequest request,
            HttpServletRequest httpServletRequest) {

        User currentUser = currentUserProvider.getCurrentUser();
        DocumentResponse response = documentService.uploadDocument(file, request, currentUser);

        ApiResponse<DocumentResponse> apiResponse = ApiResponse.<DocumentResponse>builder()
                .success(true)
                .message("Document uploaded successfully")
                .data(response)
                .timestamp(Instant.now())
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(apiResponse);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DocumentResponse>> getDocumentById(
            @PathVariable Long id,
            HttpServletRequest httpServletRequest) {

        DocumentResponse response = documentService.getDocumentById(id);

        ApiResponse<DocumentResponse> apiResponse = ApiResponse.<DocumentResponse>builder()
                .success(true)
                .message("Document details fetched successfully")
                .data(response)
                .timestamp(Instant.now())
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(apiResponse);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadDocument(@PathVariable Long id) {
        Resource resource = documentService.downloadDocument(id);
        DocumentResponse metadata = documentService.getDocumentById(id);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(metadata.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + metadata.getOriginalFileName() + "\"")
                .body(resource);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<DocumentListResponse>>> getAllDocuments(
            @RequestParam(required = false) DocumentReferenceType referenceType,
            @RequestParam(required = false) Long referenceId,
            @RequestParam(required = false) DocumentPurpose purpose,
            @RequestParam(required = false) DocumentStage stage,
            @RequestParam(required = false) DocumentCategory category,
            @RequestParam(required = false) DocumentStatus status,
            HttpServletRequest httpServletRequest) {

        List<DocumentListResponse> response = documentService.getAllDocumentsForListing(referenceType, referenceId, purpose, stage, category, status);

        ApiResponse<List<DocumentListResponse>> apiResponse = ApiResponse.<List<DocumentListResponse>>builder()
                .success(true)
                .message("Documents retrieved successfully")
                .data(response)
                .timestamp(Instant.now())
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(apiResponse);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<ApiResponse<List<DocumentResponse>>> getDocumentsByUserId(
            @PathVariable Long userId,
            HttpServletRequest httpServletRequest) {

        List<DocumentResponse> response = documentService.getDocumentsByUserId(userId);

        ApiResponse<List<DocumentResponse>> apiResponse = ApiResponse.<List<DocumentResponse>>builder()
                .success(true)
                .message("User documents fetched successfully")
                .data(response)
                .timestamp(Instant.now())
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(apiResponse);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteDocument(
            @PathVariable Long id,
            HttpServletRequest httpServletRequest) {

        User currentUser = currentUserProvider.getCurrentUser();
        documentService.deleteDocument(id, currentUser.getId());

        ApiResponse<Void> apiResponse = ApiResponse.<Void>builder()
                .success(true)
                .message("Document deleted successfully")
                .timestamp(Instant.now())
                .path(httpServletRequest.getRequestURI())
                .build();

        return ResponseEntity.ok(apiResponse);
    }
}