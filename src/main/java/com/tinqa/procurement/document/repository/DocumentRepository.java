package com.tinqa.procurement.document.repository;

import com.tinqa.procurement.document.constant.DocumentReferenceType;
import com.tinqa.procurement.document.constant.DocumentStatus;
import com.tinqa.procurement.document.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long>, JpaSpecificationExecutor<Document> {

    List<Document> findByOwnerId(Long userId);

    Optional<Document> findByIdAndStatus(Long id, DocumentStatus status);

    // The files attached to several records at once (e.g. the proofs of a page of outreach contacts)
    List<Document> findByReferenceTypeAndReferenceIdInAndStatusOrderByIdAsc(DocumentReferenceType referenceType,
                                                                            java.util.Collection<Long> referenceIds,
                                                                            DocumentStatus status);

    long countByReferenceTypeAndReferenceIdAndStatus(DocumentReferenceType referenceType, Long referenceId, DocumentStatus status);

    Optional<Document> findByIdAndReferenceTypeAndReferenceIdAndStatus(Long id, DocumentReferenceType referenceType, Long referenceId,
                                                                       DocumentStatus status);

}