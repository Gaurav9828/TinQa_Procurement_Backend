package com.tinqa.procurement.document.dto;

import com.tinqa.procurement.document.constant.DocumentStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DocumentApprovalRequest {

    @NotNull(message = "Document status is required")
    private DocumentStatus decision; // Expected to be DocumentStatus.ACTIVE (Approved) or DocumentStatus.REJECTED

    @Size(max = 500, message = "Rejection reason cannot exceed 500 characters")
    private String rejectionReason;
}