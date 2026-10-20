package com.tinqa.procurement.document.service.impl;

import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.document.constant.DocumentOwnerType;
import com.tinqa.procurement.document.constant.DocumentStatus;
import com.tinqa.procurement.document.dto.DocumentApprovalRequest;
import com.tinqa.procurement.document.dto.DocumentDownload;
import com.tinqa.procurement.document.dto.DocumentFilter;
import com.tinqa.procurement.document.dto.DocumentListResponse;
import com.tinqa.procurement.document.dto.DocumentResponse;
import com.tinqa.procurement.document.dto.DocumentUploadRequest;
import com.tinqa.procurement.document.entity.Document;
import com.tinqa.procurement.document.mapper.DocumentMapper;
import com.tinqa.procurement.document.repository.DocumentRepository;
import com.tinqa.procurement.document.service.DocumentService;
import com.tinqa.procurement.document.service.FileStorageService;
import com.tinqa.procurement.document.service.UploadedFileValidator;
import com.tinqa.procurement.notification.NotificationLinks;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.security.access.CurrentAccess;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private static final Set<DocumentStatus> DECISIONS = Set.of(DocumentStatus.ACTIVE, DocumentStatus.REJECTED);
    // Owner types whose owner id is a user id, so the owner is a person who may manage the document themselves
    private static final Set<DocumentOwnerType> PERSONAL_OWNER_TYPES = Set.of(DocumentOwnerType.EMPLOYEE, DocumentOwnerType.ADMIN);

    private final DocumentRepository documentRepository;
    private final FileStorageService fileStorageService;
    private final UploadedFileValidator uploadedFileValidator;
    private final NotificationService notificationService;
    private final CurrentUserProvider currentUserProvider;
    private final CurrentAccess currentAccess;
    private final DocumentMapper documentMapper;

    @Override
    @Transactional
    public DocumentResponse uploadDocument(MultipartFile file, DocumentUploadRequest request) {
        User uploader = currentUserProvider.getCurrentUser();
        if (!currentAccess.has(Permission.DOCUMENT_UPLOAD)
                && !(PERSONAL_OWNER_TYPES.contains(request.getOwnerType()) && uploader.getId().equals(request.getOwnerId()))) {
            throw new AccessDeniedException("You can only upload your own documents.");
        }
        UploadedFileValidator.ValidatedFile validated = uploadedFileValidator.validate(file, request.getType());
        String pathPrefix = request.getReferenceType().name().toLowerCase(Locale.ROOT) + "/"
                + request.getPurpose().name().toLowerCase(Locale.ROOT);
        String storageKey = fileStorageService.storeFile(file, pathPrefix);
        // The file is already on disk: remove it again if the record is not committed
        deleteFileAfterRollback(storageKey);

        Document document = Document.builder()
                .originalFileName(validated.originalFileName())
                .storageKey(storageKey)
                .contentType(validated.contentType())
                .fileSize(file.getSize())
                .uploaderType(request.getUploaderType())
                .uploadedByUserId(uploader.getId())
                .ownerType(request.getOwnerType())
                .ownerId(request.getOwnerId())
                .referenceType(request.getReferenceType())
                .referenceId(request.getReferenceId())
                .category(request.getCategory())
                .purpose(request.getPurpose())
                .type(request.getType())
                .stage(request.getStage())
                // Approvers' own uploads go live directly; everyone else's wait for an approver
                .status(currentAccess.has(Permission.DOCUMENT_APPROVE) ? DocumentStatus.ACTIVE : DocumentStatus.WAITING_FOR_APPROVAL)
                .build();
        return documentMapper.toResponse(documentRepository.save(document));
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentResponse getDocumentById(Long documentId) {
        return documentMapper.toResponse(requireReadable(findDocument(documentId)));
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentDownload downloadDocument(Long documentId) {
        Document document = requireReadable(findDocument(documentId));
        return new DocumentDownload(fileStorageService.loadFileAsResource(document.getStorageKey()),
                document.getContentType(), document.getOriginalFileName());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentListResponse> getAllDocumentsForListing(DocumentFilter filter) {
        return documentRepository.findAll(matching(filter)).stream()
                .map(documentMapper::toListResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentResponse> getDocumentsByUserId(Long userId) {
        if (!currentAccess.has(Permission.DOCUMENT_VIEW) && !userId.equals(currentUserProvider.getCurrentUserId())) {
            throw new AccessDeniedException("You can only view your own documents.");
        }
        return documentRepository.findByOwnerId(userId).stream()
                .map(documentMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public void deleteDocument(Long documentId) {
        Document document = documentRepository.findByIdAndStatus(documentId, DocumentStatus.ACTIVE)
                .orElseThrow(() -> documentNotFound(documentId));
        if (!currentAccess.has(Permission.DOCUMENT_DELETE) && !isOwnDocument(document)) {
            throw new AccessDeniedException("You can only delete your own documents.");
        }
        document.setStatus(DocumentStatus.DELETED);
        documentRepository.save(document);
        deleteFileAfterCommit(document.getStorageKey());
    }

    @Override
    @Transactional
    public DocumentResponse processAdminL2Approval(Long documentId, DocumentApprovalRequest request) {
        User reviewer = currentUserProvider.getCurrentUser();
        if (!currentAccess.has(Permission.DOCUMENT_APPROVE)) {
            throw new AccessDeniedException("You don't have permission to approve or reject documents.");
        }

        Document document = findDocument(documentId);
        if (!DECISIONS.contains(request.getDecision())) {
            throw ApiErrors.fieldError("decision", "Invalid status update. Only ACTIVE (Approved) or REJECTED are allowed.");
        }
        boolean rejected = request.getDecision() == DocumentStatus.REJECTED;
        if (rejected && OptionalText.blankToNull(request.getRejectionReason()) == null) {
            throw ApiErrors.fieldError("rejectionReason", "Rejection reason is required when rejecting a document.");
        }

        // The uploader learns the decision, unless they made it themselves
        if (!document.getUploadedByUserId().equals(reviewer.getId())) {
            String title = rejected ? "Document Rejected" : "Document Approved";
            String message = rejected
                    ? document.getCategory() + " Document Rejected by ID: " + reviewer.getUsername() + ". Reason: " + request.getRejectionReason()
                    : document.getCategory() + " Document approved by ID: " + reviewer.getUsername();
            notificationService.createForUser(document.getUploadedByUserId(), title, message, NotificationLinks.PROFILE);
        }

        // The uploader stays recorded as the uploader
        document.setStatus(request.getDecision());
        return documentMapper.toResponse(documentRepository.save(document));
    }

    // Everyone may read their own documents (the ones they own or uploaded); others' need DOCUMENT_VIEW
    private Document requireReadable(Document document) {
        if (!currentAccess.has(Permission.DOCUMENT_VIEW) && !isOwnDocument(document)) {
            throw new AccessDeniedException("You can only view your own documents.");
        }
        return document;
    }

    private boolean isOwnDocument(Document document) {
        Long me = currentUserProvider.getCurrentUserId();
        return (PERSONAL_OWNER_TYPES.contains(document.getOwnerType()) && me.equals(document.getOwnerId()))
                || me.equals(document.getUploadedByUserId());
    }

    private Document findDocument(Long documentId) {
        return documentRepository.findById(documentId).orElseThrow(() -> documentNotFound(documentId));
    }

    private static Specification<Document> matching(DocumentFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.referenceType() != null) predicates.add(cb.equal(root.get("referenceType"), filter.referenceType()));
            if (filter.referenceId() != null) predicates.add(cb.equal(root.get("referenceId"), filter.referenceId()));
            if (filter.purpose() != null) predicates.add(cb.equal(root.get("purpose"), filter.purpose()));
            if (filter.stage() != null) predicates.add(cb.equal(root.get("stage"), filter.stage()));
            if (filter.category() != null) predicates.add(cb.equal(root.get("category"), filter.category()));
            if (filter.status() != null) predicates.add(cb.equal(root.get("status"), filter.status()));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private void deleteFileAfterCommit(String storageKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                fileStorageService.deleteFile(storageKey);
            }
        });
    }

    private void deleteFileAfterRollback(String storageKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    fileStorageService.deleteFile(storageKey);
                }
            }
        });
    }

    private static ResourceNotFoundException documentNotFound(Long documentId) {
        return new ResourceNotFoundException("Document not found with ID: " + documentId);
    }
}
