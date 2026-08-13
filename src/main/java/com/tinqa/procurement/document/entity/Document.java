package com.tinqa.procurement.document.entity;

import com.tinqa.procurement.document.constant.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "document", indexes = {
        @Index(name = "idx_doc_uploader", columnList = "uploaderType, uploadedByUserId"),
        @Index(name = "idx_doc_reference", columnList = "referenceType, referenceId"),
        @Index(name = "idx_doc_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String originalFileName;

    @Column(nullable = false, unique = true)
    private String storageKey;

    @Column(nullable = false)
    private String contentType;

    @Column(nullable = false)
    private Long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentUploaderType uploaderType;

    @Column(nullable = false)
    private Long uploadedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentOwnerType ownerType;

    @Column(nullable = false)
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentReferenceType referenceType;

    @Column(nullable = false)
    private Long referenceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStage stage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private DocumentStatus status = DocumentStatus.ACTIVE;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}