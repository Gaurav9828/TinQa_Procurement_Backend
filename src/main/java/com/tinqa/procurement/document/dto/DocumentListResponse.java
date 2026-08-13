package com.tinqa.procurement.document.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentListResponse {
    private Long id;
    private String originalFileName;
    private String contentType;
    private Long fileSize;
    private String category;
    private String purpose;
    private String type;
    private String stage;
    private LocalDateTime createdAt;
}