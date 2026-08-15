package com.tinqa.procurement.document.repository;

import com.tinqa.procurement.document.constant.DocumentStatus;
import com.tinqa.procurement.document.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long>, JpaSpecificationExecutor<Document> {

    List<Document> findByUploadedByUserIdAndStatus(Long uploadedByUserId, DocumentStatus status);

    Optional<Document> findByIdAndStatus(Long id, DocumentStatus status);

}