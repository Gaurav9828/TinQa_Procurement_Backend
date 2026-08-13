package com.tinqa.procurement.document.dto;

import com.tinqa.procurement.document.constant.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DocumentUploadRequest {

    @NotNull(message = "Uploader type is required")
    private DocumentUploaderType uploaderType;

    @NotNull(message = "Owner type is required")
    private DocumentOwnerType ownerType;

    @NotNull(message = "Owner ID is required")
    private Long ownerId;

    @NotNull(message = "Reference type is required")
    private DocumentReferenceType referenceType;

    @NotNull(message = "Reference ID is required")
    private Long referenceId;

    @NotNull(message = "Category is required")
    private DocumentCategory category;

    @NotNull(message = "Document purpose is required")
    private DocumentPurpose purpose;

    @NotNull(message = "Document type is required")
    private DocumentType type;

    @NotNull(message = "Document stage is required")
    private DocumentStage stage;
}