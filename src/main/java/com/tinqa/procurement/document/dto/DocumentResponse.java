package com.tinqa.procurement.document.dto;

import com.tinqa.procurement.document.constant.*;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class DocumentResponse {
    private Long id;
    private String originalFileName;
    private String contentType;
    private Long fileSize;
    private DocumentUploaderType uploaderType;
    private Long uploadedByUserId;
    private DocumentOwnerType ownerType;
    private Long ownerId;
    private DocumentReferenceType referenceType;
    private Long referenceId;
    private DocumentCategory category;
    private DocumentPurpose purpose;
    private DocumentType type;
    private DocumentStage stage;
    private DocumentStatus status;
    private String downloadUrl;
    private LocalDateTime createdAt;
}