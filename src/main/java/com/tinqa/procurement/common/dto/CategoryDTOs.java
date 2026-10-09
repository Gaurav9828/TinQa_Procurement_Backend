package com.tinqa.procurement.common.dto;

import com.tinqa.procurement.common.validation.*;
import jakarta.validation.constraints.*;
import com.tinqa.procurement.common.enums.CategoryType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

public class CategoryDTOs {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {

        @NotBlank(message = "Category name is required")
        @Size(max = 100, message = "Category name cannot exceed 100 characters")
        @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Category name " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
        private String name;

        @NotBlank(message = "Category code is required")
        @Size(max = 50, message = "Category code cannot exceed 50 characters")
        @Pattern(regexp = ValidationPatterns.CODE, message = "Category code " + ValidationPatterns.CODE_MESSAGE)
        private String code;

        @Size(max = 500, message = "Description cannot exceed 500 characters")
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {

        @NotBlank(message = "Category name is required")
        @Size(max = 100, message = "Category name cannot exceed 100 characters")
        @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Category name " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
        private String name;

        @NotBlank(message = "Category code is required")
        @Size(max = 50, message = "Category code cannot exceed 50 characters")
        @Pattern(regexp = ValidationPatterns.CODE, message = "Category code " + ValidationPatterns.CODE_MESSAGE)
        private String code;

        @Size(max = 500, message = "Description cannot exceed 500 characters")
        private String description;

        private Boolean isActive;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {

        private Long id;
        private CategoryType type;
        private String name;
        private String code;
        private String description;
        private Boolean isActive;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private Long createdBy;
        private Long updatedBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {

        private Long id;
        private String name;
        private String code;
    }
}