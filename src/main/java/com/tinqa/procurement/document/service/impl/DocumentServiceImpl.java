package com.tinqa.procurement.document.service.impl;

import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.document.constant.*;
import com.tinqa.procurement.document.dto.*;
import com.tinqa.procurement.document.entity.Document;
import com.tinqa.procurement.document.repository.DocumentRepository;
import com.tinqa.procurement.document.service.DocumentService;
import com.tinqa.procurement.document.service.FileStorageService;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final DocumentRepository documentRepository;
    private final FileStorageService fileStorageService;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

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
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with ID: " + documentId));
        return mapToResponse(document);
    }

    @Override
    public Resource downloadDocument(Long documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with ID: " + documentId));
        return fileStorageService.loadFileAsResource(document.getStorageKey());
    }

    @Override
    public List<DocumentListResponse> getAllDocumentsForListing(DocumentReferenceType referenceType, Long referenceId, DocumentPurpose purpose, DocumentStage stage, DocumentCategory category, DocumentStatus status) {
        Specification<Document> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

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

            if(Objects.nonNull(status)){
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return documentRepository.findAll(spec).stream()
                .map(this::mapToListResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<DocumentResponse> getDocumentsByUserId(Long userId) {
        return documentRepository.findByOwnerId(userId)
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

    @Override
    @Transactional
    public DocumentResponse processAdminL2Approval(Long documentId, DocumentApprovalRequest request, User currentUser) {
        // Restrict access strictly to ADMIN_L2
        if (currentUser.getRole() != Role.ADMIN_L2) {
            throw new AccessDeniedException("Only Level 2 Administrators are authorized to approve or reject documents.");
        }

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with ID: " + documentId));

        // Validate that the target status is a valid decision state
        if (request.getDecision() != DocumentStatus.ACTIVE && request.getDecision() != DocumentStatus.REJECTED) {
            throw new IllegalArgumentException("Invalid status update. Only ACTIVE (Approved) or REJECTED are allowed.");
        }

        if (request.getDecision() == DocumentStatus.REJECTED && (request.getRejectionReason() == null || request.getRejectionReason().isBlank())) {
            throw new IllegalArgumentException("Rejection reason is required when rejecting a document.");
        }

        User user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User entry not found with id: " + currentUser.getId()));

        if(!document.getUploadedByUserId().equals(currentUser.getId())){
            String title = "";
            String message = "";

            if(request.getDecision() == DocumentStatus.ACTIVE){
                title = "Document Approved";
                message = document.getCategory() + " Document approved by ID: " + user.getUsername();
            }else {
                title = "Document Rejected";
                message = document.getCategory() + "Document Rejected by ID: " + user.getUsername() + ". Reason: " + request.getRejectionReason();
            }

            notificationService.createForUser(document.getUploadedByUserId(), title, message);
        }

        document.setStatus(request.getDecision());
        document.setUploadedByUserId(currentUser.getId());
        Document updatedDocument = documentRepository.save(document);
        return mapToResponse(updatedDocument);
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