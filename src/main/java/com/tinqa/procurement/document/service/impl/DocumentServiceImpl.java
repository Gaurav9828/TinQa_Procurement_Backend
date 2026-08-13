package com.tinqa.procurement.document.service.impl;

import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.document.constant.*;
import com.tinqa.procurement.document.dto.*;
import com.tinqa.procurement.document.entity.Document;
import com.tinqa.procurement.document.repository.DocumentRepository;
import com.tinqa.procurement.document.service.DocumentService;
import com.tinqa.procurement.document.service.FileStorageService;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final DocumentRepository documentRepository;
    private final FileStorageService fileStorageService;

    @Override
    @Transactional
    public DocumentResponse uploadDocument(MultipartFile file, DocumentUploadRequest request, User currentUser) {
        String pathPrefix = request.getReferenceType().name().toLowerCase() + "/" + request.getPurpose().name().toLowerCase();
        String storageKey = fileStorageService.storeFile(file, pathPrefix);

        Document document = Document.builder()
                .originalFileName(file.getOriginalFilename())
                .storageKey(storageKey)
                .contentType(file.getContentType())
                .fileSize(file.getSize())
                .uploaderType(request.getUploaderType())
                .uploadedByUserId(currentUser.getId())
                .ownerType(request.getOwnerType())
                .ownerId(request.getOwnerId())
                .referenceType(request.getReferenceType())
                .referenceId(request.getReferenceId())
                .category(request.getCategory())
                .purpose(request.getPurpose())
                .type(request.getType())
                .stage(request.getStage())
                .status(currentUser.getRole() == Role.ADMIN_L2 ? DocumentStatus.ACTIVE : DocumentStatus.WAITING_FOR_APPROVAL)
                .build();

        Document savedDocument = documentRepository.save(document);
        return mapToResponse(savedDocument);
    }

    @Override
    public DocumentResponse getDocumentById(Long documentId) {
        Document document = documentRepository.findByIdAndStatus(documentId, DocumentStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with ID: " + documentId));
        return mapToResponse(document);
    }

    @Override
    public Resource downloadDocument(Long documentId) {
        Document document = documentRepository.findByIdAndStatus(documentId, DocumentStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with ID: " + documentId));
        return fileStorageService.loadFileAsResource(document.getStorageKey());
    }

    @Override
    public List<DocumentListResponse> getAllDocumentsForListing(DocumentReferenceType referenceType, Long referenceId, DocumentPurpose purpose, DocumentStage stage, DocumentCategory category) {
        Specification<Document> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), DocumentStatus.ACTIVE));

            if (referenceType != null) {
                predicates.add(cb.equal(root.get("referenceType"), referenceType));
            }
            if (referenceId != null) {
                predicates.add(cb.equal(root.get("referenceId"), referenceId));
            }
            if (purpose != null) {
                predicates.add(cb.equal(root.get("purpose"), purpose));
            }
            if (stage != null) {
                predicates.add(cb.equal(root.get("stage"), stage));
            }
            if (category != null) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return documentRepository.findAll(spec).stream()
                .map(this::mapToListResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<DocumentResponse> getDocumentsByUserId(Long userId) {
        return documentRepository.findByUploadedByUserIdAndStatus(userId, DocumentStatus.ACTIVE)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteDocument(Long documentId, Long currentUserId) {
        Document document = documentRepository.findByIdAndStatus(documentId, DocumentStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with ID: " + documentId));

        document.setStatus(DocumentStatus.DELETED);
        documentRepository.save(document);
        fileStorageService.deleteFile(document.getStorageKey());
    }

    private DocumentResponse mapToResponse(Document doc) {
        return DocumentResponse.builder()
                .id(doc.getId())
                .originalFileName(doc.getOriginalFileName())
                .contentType(doc.getContentType())
                .fileSize(doc.getFileSize())
                .uploaderType(doc.getUploaderType())
                .uploadedByUserId(doc.getUploadedByUserId())
                .ownerType(doc.getOwnerType())
                .ownerId(doc.getOwnerId())
                .referenceType(doc.getReferenceType())
                .referenceId(doc.getReferenceId())
                .category(doc.getCategory())
                .purpose(doc.getPurpose())
                .type(doc.getType())
                .stage(doc.getStage())
                .status(doc.getStatus())
                .downloadUrl("/api/v1/documents/" + doc.getId() + "/download")
                .createdAt(doc.getCreatedAt())
                .build();
    }

    private DocumentListResponse mapToListResponse(Document doc) {
        return DocumentListResponse.builder()
                .id(doc.getId())
                .originalFileName(doc.getOriginalFileName())
                .contentType(doc.getContentType())
                .fileSize(doc.getFileSize())
                .category(doc.getCategory().name())
                .purpose(doc.getPurpose().name())
                .type(doc.getType().name())
                .stage(doc.getStage().name())
                .createdAt(doc.getCreatedAt())
                .build();
    }
}