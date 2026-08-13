package com.tinqa.procurement.document.service;

import com.tinqa.procurement.document.constant.*;
import com.tinqa.procurement.document.dto.*;
import com.tinqa.procurement.security.model.User;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface DocumentService {
    DocumentResponse uploadDocument(MultipartFile file, DocumentUploadRequest request, User currentUser);
    DocumentResponse getDocumentById(Long documentId);
    Resource downloadDocument(Long documentId);
    List<DocumentListResponse> getAllDocumentsForListing(DocumentReferenceType referenceType, Long referenceId, DocumentPurpose purpose, DocumentStage stage, DocumentCategory category);
    List<DocumentResponse> getDocumentsByUserId(Long userId);
    void deleteDocument(Long documentId, Long currentUserId);
}