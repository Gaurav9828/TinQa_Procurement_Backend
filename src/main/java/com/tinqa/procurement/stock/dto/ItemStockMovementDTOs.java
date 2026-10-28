package com.tinqa.procurement.stock.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tinqa.procurement.common.validation.*;
import com.tinqa.procurement.stock.enums.ItemStockMovementStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

        // At least one, unless the movement only registers or removes product units (a product without components)
        @Size(max = 100, message = "A movement cannot have more than 100 lines")
        private List<@Valid @NotNull(message = "Lines cannot contain null entries") Line> lines;

        @Size(max = 255, message = "Performed by cannot exceed 255 characters")
        private String performedBy;

        // Product units built or taken out of stock by this change, with the barcodes of the pieces inside them
        @Valid
        private ProductUnitChange productUnits;
    }

    /**
     * The units of an Ecommerce product that a change builds or takes out of stock. Every new unit carries its own
     * barcode and lists the barcode of each piece fitted into it; units taken out are named by barcode.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductUnitChange {
        @NotNull(message = "Product ID is required")
        @Positive(message = "Product ID must be positive")
        private Long productId;

        @Size(max = 200, message = "Product title cannot exceed 200 characters")
        private String productTitle;

        // The product's units in stock before and after the change
        @NotNull(message = "Stock before is required")
        @PositiveOrZero(message = "Stock before cannot be negative")
        private Integer stockBefore;

        @NotNull(message = "Stock after is required")
        @PositiveOrZero(message = "Stock after cannot be negative")
        private Integer stockAfter;

        // A product just created: its units wait for the product's approval
        private boolean awaitingApproval;

        @Size(max = 1000, message = "At most 1000 product units can be added at once")
        private List<@Valid @NotNull(message = "Added units cannot contain null entries") NewUnit> added;

        @Size(max = 1000, message = "At most 1000 product units can be removed at once")
        private List<@NotNull(message = "Removed units cannot contain null entries") String> removed;

        // Removed units: their pieces go back to item stock (true, the default) or are scrapped with them
        @Builder.Default
        private boolean returnParts = true;

        // Set only inside this service (a rejected product): the removed units' registrations are voided
        @JsonIgnore
        private boolean withdrawRegistration;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NewUnit {
        @NotNull(message = "Unit barcode is required")
        private String barcode;

        @Size(max = 100, message = "A unit cannot list more than 100 components")
        private List<@Valid @NotNull(message = "Parts cannot contain null entries") Part> parts;
    }

    /** Pieces of one item fitted into a product unit. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Part {
        @NotNull(message = "Item ID is required")
        @Positive(message = "Item ID must be positive")
        private Long itemId;

        @NotEmpty(message = "At least one part barcode is required")
        @Size(max = 1000, message = "A component cannot list more than 1000 pieces")
        private List<@NotNull(message = "Part barcodes cannot contain null entries") String> barcodes;
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

    /** What an Ecommerce product holds in Procurement: its units still held as stock and the pieces it consumed. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductHoldings {
        // Barcodes of the product's units still held (pending approval or in stock), oldest first
        private List<String> heldUnitCodes;
        // Item id -> units the product still holds (consumed minus returned, applied movements only)
        private Map<Long, BigDecimal> heldPiecesByItem;
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
