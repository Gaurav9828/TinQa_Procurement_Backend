package com.tinqa.procurement.document.dto;

import com.tinqa.procurement.document.constant.DocumentCategory;
import com.tinqa.procurement.document.constant.DocumentPurpose;
import com.tinqa.procurement.document.constant.DocumentReferenceType;
import com.tinqa.procurement.document.constant.DocumentStage;
import com.tinqa.procurement.document.constant.DocumentStatus;

/**
 * Optional filters of the document listing; null means "any".
 */
public record DocumentFilter(DocumentReferenceType referenceType, Long referenceId, DocumentPurpose purpose,
                             DocumentStage stage, DocumentCategory category, DocumentStatus status) {
}
