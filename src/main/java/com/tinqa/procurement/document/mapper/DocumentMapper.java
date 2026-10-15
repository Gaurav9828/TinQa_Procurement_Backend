package com.tinqa.procurement.document.mapper;

import com.tinqa.procurement.document.dto.DocumentListResponse;
import com.tinqa.procurement.document.dto.DocumentResponse;
import com.tinqa.procurement.document.entity.Document;
import org.springframework.stereotype.Component;

@Component
public class DocumentMapper {

    // Includes the /api servlet context path, so clients can use it as is
    private static final String DOWNLOAD_URL = "/api/v1/documents/%d/download";

    public DocumentResponse toResponse(Document document) {
        return DocumentResponse.builder()
                .id(document.getId())
                .originalFileName(document.getOriginalFileName())
                .contentType(document.getContentType())
                .fileSize(document.getFileSize())
                .uploaderType(document.getUploaderType())
                .uploadedByUserId(document.getUploadedByUserId())
                .ownerType(document.getOwnerType())
                .ownerId(document.getOwnerId())
                .referenceType(document.getReferenceType())
                .referenceId(document.getReferenceId())
                .category(document.getCategory())
                .purpose(document.getPurpose())
                .type(document.getType())
                .stage(document.getStage())
                .status(document.getStatus())
                .downloadUrl(String.format(DOWNLOAD_URL, document.getId()))
                .createdAt(document.getCreatedAt())
                .build();
    }

    public DocumentListResponse toListResponse(Document document) {
        return DocumentListResponse.builder()
                .id(document.getId())
                .originalFileName(document.getOriginalFileName())
                .contentType(document.getContentType())
                .fileSize(document.getFileSize())
                .category(document.getCategory().name())
                .purpose(document.getPurpose().name())
                .type(document.getType().name())
                .stage(document.getStage().name())
                .createdAt(document.getCreatedAt())
                .build();
    }
}
