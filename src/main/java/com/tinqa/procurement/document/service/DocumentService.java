package com.tinqa.procurement.document.service;

import com.tinqa.procurement.document.dto.DocumentApprovalRequest;
import com.tinqa.procurement.document.dto.DocumentDownload;
import com.tinqa.procurement.document.dto.DocumentFilter;
import com.tinqa.procurement.document.dto.DocumentListResponse;
import com.tinqa.procurement.document.dto.DocumentResponse;
import com.tinqa.procurement.document.dto.DocumentUploadRequest;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Uploaded documents. Uploads by anyone but Admin L2 wait for Admin L2 approval.
 */
public interface DocumentService {

    DocumentResponse uploadDocument(MultipartFile file, DocumentUploadRequest request);

    DocumentResponse getDocumentById(Long documentId);

    DocumentDownload downloadDocument(Long documentId);

    List<DocumentListResponse> getAllDocumentsForListing(DocumentFilter filter);

    List<DocumentResponse> getDocumentsByUserId(Long userId);

    /** Soft delete of an active document; the file is removed once the deletion is committed. */
    void deleteDocument(Long documentId);

    DocumentResponse processAdminL2Approval(Long documentId, DocumentApprovalRequest request);
}
