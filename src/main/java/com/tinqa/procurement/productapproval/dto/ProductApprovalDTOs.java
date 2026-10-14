package com.tinqa.procurement.productapproval.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.common.validation.MaxAmount;
import com.tinqa.procurement.common.validation.UrlInput;
import com.tinqa.procurement.common.validation.ValidationPatterns;
import com.tinqa.procurement.productapproval.enums.ProductApprovalRequestType;
import com.tinqa.procurement.productapproval.enums.ProductApprovalStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public class ProductApprovalDTOs {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubmitRequest {
        @NotNull(message = "Ecommerce product ID is required")
        @Positive(message = "Ecommerce product ID must be a valid ID")
        private Long ecommerceProductId;

        @NotBlank(message = "Product title is required")
        @Size(max = 150, message = "Product title cannot exceed 150 characters")
        @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Product title " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
        private String productTitle;

        @NotNull(message = "Snapshot is required")
        @Valid
        private Snapshot snapshot;

        // Omit for a new product (CREATE / RESUBMISSION); only UPDATE may be sent
        private ProductApprovalRequestType requestType;

        // UPDATE only: the complete Ecommerce PUT body to apply once approved. Kept verbatim (a JsonNode is not
        // screened by this service's text rules; Ecommerce validates it when it is applied)
        private JsonNode proposedChanges;

        // UPDATE only: the live product when the change was requested
        @Valid
        private Snapshot previousSnapshot;

        // UPDATE only: 1-100 entries
        @Size(max = 100, message = "Change summary cannot have more than 100 entries")
        private List<@Valid @NotNull(message = "Change summary cannot contain empty entries") ChangeSummaryEntry> changeSummary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChangeSummaryEntry {
        @NotBlank(message = "Change label is required")
        @Size(max = 100, message = "Change label cannot exceed 100 characters")
        private String label;

        // Values may be links (e.g. a changed image URL); scripts and markup are still rejected
        @UrlInput
        @Size(max = 5000, message = "Old value cannot exceed 5000 characters")
        private String oldValue;

        @UrlInput
        @Size(max = 5000, message = "New value cannot exceed 5000 characters")
        private String newValue;
    }

    /**
     * What the reviewer saw when deciding; stored as JSONB exactly as validated here.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Snapshot {
        @NotBlank(message = "Snapshot title is required")
        @Size(max = 150, message = "Snapshot title cannot exceed 150 characters")
        private String title;

        @Size(max = 250, message = "Snapshot tagline cannot exceed 250 characters")
        private String tagline;

        @NotNull(message = "Snapshot price is required")
        @PositiveOrZero(message = "Snapshot price cannot be negative")
        @MaxAmount(message = "Snapshot price cannot exceed " + ValidationPatterns.MAX_AMOUNT_LABEL + " and can have at most 2 decimal places")
        private BigDecimal price;

        @DecimalMin(value = "0", message = "Snapshot discount percentage cannot be negative")
        @DecimalMax(value = "100", message = "Snapshot discount percentage cannot exceed 100")
        private BigDecimal discountPercentage;

        // Units of the product built so far: on resubmission the product must again hold components x this many
        @NotNull(message = "Snapshot stock quantity is required")
        @Min(value = 0, message = "Snapshot stock quantity cannot be negative")
        private Integer stockQuantity;

        @UrlInput
        @Size(max = 500, message = "Snapshot image URL cannot exceed 500 characters")
        @Pattern(regexp = "(?i)^https?://\\S+$", message = "Snapshot image URL must be a valid HTTP or HTTPS URL")
        private String image1Url;

        @Size(max = 50, message = "Snapshot cannot have more than 50 components")
        private List<@Valid @NotNull(message = "Snapshot components cannot contain empty entries") Component> components;

        @Size(max = 20, message = "Snapshot cannot have more than 20 warranties")
        private List<@Valid @NotNull(message = "Snapshot warranties cannot contain empty entries") Warranty> warranties;

        @Min(value = 0, message = "Snapshot specification count cannot be negative")
        @Max(value = 1000, message = "Snapshot specification count cannot exceed 1000")
        private Integer specificationCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Component {
        @NotNull(message = "Component item ID is required")
        @Positive(message = "Component item ID must be a valid ID")
        private Long itemId;

        @NotBlank(message = "Component item name is required")
        @Size(max = 150, message = "Component item name cannot exceed 150 characters")
        private String itemName;

        @NotNull(message = "Component quantity is required")
        @Positive(message = "Component quantity must be greater than zero")
        @Digits(integer = 11, fraction = 3, message = "Component quantity can have at most 11 digits and 3 decimal places")
        private BigDecimal quantity;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Warranty {
        @NotBlank(message = "Warranty title is required")
        @Size(max = 255, message = "Warranty title cannot exceed 255 characters")
        private String title;

        @Pattern(regexp = "MANUFACTURER|SELLER|EXTENDED|REPLACEMENT|SERVICE|PARTS|LIMITED",
                message = "Warranty type must be MANUFACTURER, SELLER, EXTENDED, REPLACEMENT, SERVICE, PARTS or LIMITED")
        private String warrantyType;

        @Positive(message = "Warranty duration must be greater than zero")
        @Max(value = 3650, message = "Warranty duration cannot exceed 3650")
        private Integer durationValue;

        @Pattern(regexp = "DAYS|MONTHS|YEARS", message = "Warranty duration unit must be DAYS, MONTHS or YEARS")
        private String durationUnit;

        private Boolean isActive;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DecisionRequest {
        @NotNull(message = "Decision is required")
        private ProductApprovalStatus decision;

        // Required for REJECTED and CANCELLED
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Long id;
        private Long ecommerceProductId;
        private String productTitle;
        private ProductApprovalRequestType requestType;
        private ProductApprovalStatus status;
        private Snapshot snapshot;
        private Long requestedBy;
        private String requestedByUsername;
        private OffsetDateTime requestedAt;
        private Long decidedBy;
        private String decidedByUsername;
        private OffsetDateTime decidedAt;
        private String decisionReason;
        // UPDATE only (null for CREATE / RESUBMISSION)
        private JsonNode proposedChanges;
        private Snapshot previousSnapshot;
        private List<ChangeSummaryEntry> changeSummary;
        private OffsetDateTime appliedAt;
        private Long appliedBy;
    }
}
