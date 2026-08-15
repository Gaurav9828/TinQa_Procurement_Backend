package com.tinqa.procurement.item.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.time.LocalDateTime;

public class CategoryDTOs {

    @Getter
    @Setter
    public static class CreateRequest {
        @NotBlank(message = "Category name is required")
        private String name;

        @NotBlank(message = "Category code is required")
        private String code;

        private String description;
    }

    @Getter
    @Setter
    public static class Response {
        private Long id;
        private String name;
        private String code;
        private String description;
        private Boolean isActive;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }
}