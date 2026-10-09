package com.tinqa.procurement.item.dto;

import com.tinqa.procurement.common.validation.*;
import jakarta.validation.constraints.*;
import com.tinqa.procurement.item.entity.ItemWarranty;
import com.tinqa.procurement.item.enums.WarrantyDurationUnit;
import com.tinqa.procurement.item.enums.WarrantyType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class ItemDTOs {

    @Getter
    @Setter
    public static class CreateRequest {
        @NotNull(message = "Category ID is required")
        @Positive(message = "Category ID must be a valid ID")
        private Long categoryId;

        @NotBlank(message = "Item name is required")
        @Size(max = 255, message = "Item name cannot exceed 255 characters")
        @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Item name " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
        private String name;

        @Size(max = 100, message = "Brand cannot exceed 100 characters")
        @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Brand " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
        private String brand;

        @NotBlank(message = "SKU is required")
        @Size(max = 100, message = "SKU cannot exceed 100 characters")
        @Pattern(regexp = ValidationPatterns.CODE, message = "SKU " + ValidationPatterns.CODE_MESSAGE)
        private String sku;

        @NotBlank(message = "Unit of measure is required")
        @Size(max = 20, message = "Unit of measure cannot exceed 20 characters")
        @Pattern(regexp = ValidationPatterns.UNIT, message = "Unit of measure " + ValidationPatterns.UNIT_MESSAGE)
        private String unitOfMeasure;

        @NotNull(message = "MRP is required")
        @Positive(message = "MRP must be greater than zero")
        @MaxAmount(message = "MRP cannot exceed " + ValidationPatterns.MAX_AMOUNT_LABEL + " and can have at most 2 decimal places")
        private BigDecimal mrp;

        @NotBlank(message = "Country of origin is required")
        @Size(max = 50, message = "Country of origin cannot exceed 50 characters")
        @ValidCountry(message = "Country of origin must be a valid country name, e.g. India")
        private String countryOfOrigin;

        @Size(max = 1000, message = "Raw materials used cannot exceed 1000 characters")
        private String rawMaterialsUsed;

        @Size(max = 5000, message = "Terms and conditions cannot exceed 5000 characters")
        private String termsAndCondition;

        @Size(max = 2000, message = "Description cannot exceed 2000 characters")
        private String description;

        @SafeJsonMap
        private Map<String, Object> attributes;

        @Size(max = 20, message = "An item cannot have more than 20 warranties")
        private List<@Valid WarrantyRequest> warranties;
    }

    @Getter
    @Setter
    public static class UpdateRequest {
        @NotNull(message = "Category ID is required")
        @Positive(message = "Category ID must be a valid ID")
        private Long categoryId;

        @NotBlank(message = "Item name is required")
        @Size(max = 255, message = "Item name cannot exceed 255 characters")
        @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Item name " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
        private String name;

        @Size(max = 100, message = "Brand cannot exceed 100 characters")
        @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Brand " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
        private String brand;

        @NotBlank(message = "Unit of measure is required")
        @Size(max = 20, message = "Unit of measure cannot exceed 20 characters")
        @Pattern(regexp = ValidationPatterns.UNIT, message = "Unit of measure " + ValidationPatterns.UNIT_MESSAGE)
        private String unitOfMeasure;

        @NotNull(message = "MRP is required")
        @Positive(message = "MRP must be greater than zero")
        @MaxAmount(message = "MRP cannot exceed " + ValidationPatterns.MAX_AMOUNT_LABEL + " and can have at most 2 decimal places")
        private BigDecimal mrp;

        @NotBlank(message = "Country of origin is required")
        @Size(max = 50, message = "Country of origin cannot exceed 50 characters")
        @ValidCountry(message = "Country of origin must be a valid country name, e.g. India")
        private String countryOfOrigin;

        @Size(max = 1000, message = "Raw materials used cannot exceed 1000 characters")
        private String rawMaterialsUsed;

        @Size(max = 5000, message = "Terms and conditions cannot exceed 5000 characters")
        private String termsAndCondition;

        @Size(max = 2000, message = "Description cannot exceed 2000 characters")
        private String description;

        @SafeJsonMap
        private Map<String, Object> attributes;

        /**
         * Full replacement set of warranties. Null leaves existing warranties untouched;
         * entries with an id are updated, entries without one are added, and existing
         * warranties missing from the list are removed.
         */
        @Size(max = 20, message = "An item cannot have more than 20 warranties")
        private List<@Valid WarrantyRequest> warranties;

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
        private String termsAndCondition;
        private String description;
        private Map<String, Object> attributes;
        private List<WarrantyResponse> warranties;
        private Boolean isActive;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private Long createdBy;
        private Long updatedBy;
    }

    @Getter
    @Setter
    public static class WarrantyRequest {
        // Present only when updating an existing warranty of the item
        @Positive(message = "Warranty ID must be a valid ID")
        private Long id;

        @NotNull(message = "Warranty type is required")
        private WarrantyType warrantyType;

        @NotBlank(message = "Warranty title is required")
        @Size(max = 255, message = "Warranty title cannot exceed 255 characters")
        @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Warranty title " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
        private String title;

        @NotNull(message = "Warranty duration is required")
        @Positive(message = "Warranty duration must be greater than zero")
        @Max(value = 3650, message = "Warranty duration cannot exceed 3650")
        private Integer durationValue;

        @NotNull(message = "Warranty duration unit is required")
        private WarrantyDurationUnit durationUnit;

        @Size(max = 255, message = "Warranty provider cannot exceed 255 characters")
        @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Warranty provider " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
        private String provider;

        @Size(max = 5000, message = "Warranty coverage cannot exceed 5000 characters")
        private String coverage;

        @Size(max = 5000, message = "Warranty exclusions cannot exceed 5000 characters")
        private String exclusions;

        @Size(max = 5000, message = "Warranty terms and conditions cannot exceed 5000 characters")
        private String termsAndConditions;

        private Boolean isActive;
    }

    @Getter
    @Setter
    public static class WarrantyResponse {
        private Long id;
        private Long itemId;
        private WarrantyType warrantyType;
        private String title;
        private Integer durationValue;
        private WarrantyDurationUnit durationUnit;
        private String provider;
        private String coverage;
        private String exclusions;
        private String termsAndConditions;
        private Boolean isActive;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private Long createdBy;
        private Long updatedBy;

        public static WarrantyResponse from(ItemWarranty warranty) {
            WarrantyResponse res = new WarrantyResponse();
            res.setId(warranty.getId());
            res.setItemId(warranty.getItem().getId());
            res.setWarrantyType(warranty.getWarrantyType());
            res.setTitle(warranty.getTitle());
            res.setDurationValue(warranty.getDurationValue());
            res.setDurationUnit(warranty.getDurationUnit());
            res.setProvider(warranty.getProvider());
            res.setCoverage(warranty.getCoverage());
            res.setExclusions(warranty.getExclusions());
            res.setTermsAndConditions(warranty.getTermsAndConditions());
            res.setIsActive(warranty.getIsActive());
            res.setCreatedAt(warranty.getCreatedAt());
            res.setUpdatedAt(warranty.getUpdatedAt());
            res.setCreatedBy(warranty.getCreatedBy());
            res.setUpdatedBy(warranty.getUpdatedBy());
            return res;
        }
    }
}
