package com.tinqa.procurement.barcode.dto;

import com.tinqa.procurement.barcode.enums.BarcodeEventType;
import com.tinqa.procurement.barcode.enums.BarcodeRequestStatus;
import com.tinqa.procurement.barcode.enums.BarcodeRequestType;
import com.tinqa.procurement.barcode.enums.BarcodeStatus;
import com.tinqa.procurement.barcode.enums.BarcodeUnitType;
import com.tinqa.procurement.barcode.service.BarcodeCodes;
import com.tinqa.procurement.common.enums.ApprovalStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class BarcodeDTOs {

    /** One unit and its barcode. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Unit {
        private Long id;
        private String code;
        private BarcodeUnitType unitType;
        private BarcodeStatus status;
        // Approved: only a Super Admin can change the code
        private boolean locked;
        private String statusNote;
        // Item units
        private Long itemId;
        private String itemName;
        private String itemSku;
        private Long stockId;
        private String stockIdentityNumber;
        private String batchNumber;
        // Product units, and the product an item unit is fitted into
        private Long ecommerceProductId;
        private String productTitle;
        private Long fittedIntoId;
        private String fittedIntoCode;
        private LocalDateTime createdAt;
        private String createdByName;
        private LocalDateTime lockedAt;
    }

    /** Where a piece came from: its stock entry (batch), the purchase order it was received against and the dealer. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Source {
        private Long stockId;
        private String stockIdentityNumber;
        private String batchNumber;
        private LocalDate dateOfArrival;
        private ApprovalStatus stockApprovalStatus;
        private String receivedBy;
        private String approvedBy;
        private String orderNumber;
        private LocalDate orderDate;
        private LocalDate deliveredOn;
        private Long dealerId;
        private String dealerName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Part {
        private Unit unit;
        private Source source;
    }

    /** A product unit with the pieces fitted into it. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductUnit {
        private Unit unit;
        private List<Unit> parts;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Event {
        private BarcodeEventType eventType;
        private BarcodeStatus fromStatus;
        private BarcodeStatus toStatus;
        private String oldCode;
        private String newCode;
        private String fittedIntoCode;
        private String note;
        private String actorName;
        private LocalDateTime at;
    }

    /**
     * Everything known about a barcode: an item unit's batch, purchase order, dealer and the product unit it is fitted
     * into; a product unit's parts and where each came from; and its history.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Trace {
        private Unit unit;
        private Source source;
        private Unit fittedInto;
        private List<Part> parts;
        private List<Event> history;
        private List<RequestResponse> requests;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        @NotNull(message = "Request type is required")
        private BarcodeRequestType requestType;

        // CORRECTION
        @Positive(message = "Barcode ID must be a valid ID")
        private Long barcodeId;

        @Size(max = 64, message = "New barcode cannot exceed 64 characters")
        private String newCode;

        // ASSIGNMENT
        @Positive(message = "Stock ID must be a valid ID")
        private Long stockId;

        @Size(max = BarcodeCodes.MAX_CODES, message = "At most " + BarcodeCodes.MAX_CODES + " barcodes can be assigned at once")
        private List<String> codes;

        @NotBlank(message = "Reason is required")
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DecisionRequest {
        @NotNull(message = "Decision is required")
        private BarcodeRequestStatus decision;

        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequestResponse {
        private Long id;
        private BarcodeRequestType requestType;
        private BarcodeRequestStatus status;
        private Long barcodeId;
        private String currentCode;
        private String newCode;
        private BarcodeUnitType unitType;
        // What the barcode or stock entry is a unit of
        private String itemName;
        private String productTitle;
        private Long stockId;
        private String stockIdentityNumber;
        private String batchNumber;
        private BigDecimal unitsWithoutBarcode;
        private List<String> codes;
        private String reason;
        private Long requestedBy;
        private String requestedByUsername;
        private LocalDateTime requestedAt;
        private Long decidedBy;
        private String decidedByUsername;
        private LocalDateTime decidedAt;
        private String decisionReason;
    }

    /**
     * The storefront product a scanned product unit belongs to, for finding it at the store counter. Says nothing
     * about the unit's parts, batches or dealers (that is the trace, for BARCODE_VIEW).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StoreUnit {
        private String barcode;
        private Long productId;
        private String productTitle;
        private BarcodeStatus status;
        private boolean inStock;
        // Why the unit can't be sold from the shelf, when it can't
        private String note;
    }
}
