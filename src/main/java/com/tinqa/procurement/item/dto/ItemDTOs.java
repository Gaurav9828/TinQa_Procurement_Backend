package com.tinqa.procurement.item.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public class ItemDTOs {

    @Getter
    @Setter
    public static class CreateRequest {
        @NotNull(message = "Category ID is required")
        private Long categoryId;

        @NotBlank(message = "Item name is required")
        private String name;

        private String brand;

        @NotBlank(message = "SKU is required")
        private String sku;

        @NotBlank(message = "Unit of measure is required")
        private String unitOfMeasure;

        @NotNull(message = "MRP is required")
        @Positive(message = "MRP must be greater than zero")
        private BigDecimal mrp;

        @NotBlank(message = "Country of origin is required")
        private String countryOfOrigin;

        private String rawMaterialsUsed;

        @Min(value = 0, message = "Warranty months cannot be negative")
        @Max(value = 999, message = "Warranty months cannot exceed 999")
        private Integer warrantyMonths;

        private String termsAndCondition;

        private String description;

        private Map<String, Object> attributes;
    }

    @Getter
    @Setter
    public static class UpdateRequest {
        @NotNull(message = "Category ID is required")
        private Long categoryId;

        @NotBlank(message = "Item name is required")
        private String name;

        private String brand;

        @NotBlank(message = "Unit of measure is required")
        private String unitOfMeasure;

        @NotNull(message = "MRP is required")
        @Positive(message = "MRP must be greater than zero")
        private BigDecimal mrp;

        @NotBlank(message = "Country of origin is required")
        private String countryOfOrigin;

        private String rawMaterialsUsed;

        @Min(value = 0, message = "Warranty months cannot be negative")
        @Max(value = 999, message = "Warranty months cannot exceed 999")
        private Integer warrantyMonths;

        private String termsAndCondition;

        private String description;

        private Map<String, Object> attributes;

        private Boolean isActive;
    }

    @Getter
    @Setter
    public static class Response {
        private Long id;
        private Long categoryId;
        private String categoryName;
        private String name;
        private String brand;
        private String sku;
        private String unitOfMeasure;
        private BigDecimal mrp;
        private String countryOfOrigin;
        private String rawMaterialsUsed;
        private Integer warrantyMonths;
        private String termsAndCondition;
        private String description;
        private Map<String, Object> attributes;
        private Boolean isActive;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private Long createdBy;
        private Long updatedBy;
    }
}