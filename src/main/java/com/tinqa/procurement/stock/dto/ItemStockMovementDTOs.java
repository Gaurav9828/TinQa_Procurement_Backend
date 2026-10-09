package com.tinqa.procurement.stock.dto;

import com.tinqa.procurement.common.validation.*;
import com.tinqa.procurement.stock.enums.ItemStockMovementStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class ItemStockMovementDTOs {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MovementRequest {
        // Idempotency key: retries with the same movementId return the original result
        @NotNull(message = "Movement ID is required")
        private UUID movementId;

        @NotBlank(message = "Source is required")
        @Size(max = 50, message = "Source cannot exceed 50 characters")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]*$", message = "Source must be an upper-case code, e.g. ECOMMERCE_PRODUCT")
        private String source;

        @NotBlank(message = "Reference is required")
        @Size(max = 100, message = "Reference cannot exceed 100 characters")
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9:_./-]*$", message = "Reference can only contain letters, numbers and : _ . / -")
        private String reference;

        @NotEmpty(message = "At least one line is required")
        @Size(max = 100, message = "A movement cannot have more than 100 lines")
        private List<@Valid @NotNull(message = "Lines cannot contain null entries") Line> lines;

        @Size(max = 255, message = "Performed by cannot exceed 255 characters")
        private String performedBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        @NotNull(message = "Item ID is required")
        @Positive(message = "Item ID must be positive")
        private Long itemId;

        // Positive = consume from item stock, negative = return to item stock
        @NotNull(message = "Quantity is required")
        @Digits(integer = 11, fraction = 3, message = "Quantity can have at most 11 digits and 3 decimal places")
        private BigDecimal quantity;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReverseRequest {
        // Used only when the movement is unknown, to record a placeholder that blocks a late-arriving apply
        @Size(max = 50, message = "Source cannot exceed 50 characters")
        private String source;

        @Size(max = 100, message = "Reference cannot exceed 100 characters")
        private String reference;

        @Size(max = 255, message = "Performed by cannot exceed 255 characters")
        private String performedBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MovementResponse {
        private UUID movementId;
        private String source;
        private String reference;
        private ItemStockMovementStatus status;
        private String performedBy;
        private LocalDateTime createdAt;
        private LocalDateTime reversedAt;
        private String reversedBy;
        private List<LineResult> lines;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineResult {
        private Long itemId;
        private BigDecimal quantity;
        private List<Allocation> allocations;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Allocation {
        private Long stockId;
        private String batchNumber;
        private BigDecimal units;
    }
}
