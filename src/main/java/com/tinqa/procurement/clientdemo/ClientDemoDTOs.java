package com.tinqa.procurement.clientdemo;

import com.tinqa.procurement.common.validation.GoogleMapsUrl;
import com.tinqa.procurement.common.validation.MaxAmount;
import com.tinqa.procurement.common.validation.UrlInput;
import com.tinqa.procurement.common.validation.ValidationPatterns;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

public final class ClientDemoDTOs {

    /** The longest visit one demo plans. */
    public static final int MAX_DAYS = 60;
    /** The most staff tagged on one demo. */
    public static final int MAX_STAFF = 30;
    /** The most bills on one demo. */
    public static final int MAX_EXPENSES = 300;
    /** The most clients visited on one demo trip. */
    public static final int MAX_VISITS = 20;
    /** The most products shown on one demo. */
    public static final int MAX_PRODUCTS = 50;
    /** The most units of one product taken to a demo. */
    public static final int MAX_PRODUCT_UNITS = 1000;

    private static final String OPTIONAL_TEXT = "^\\s*$|" + ValidationPatterns.SINGLE_LINE_TEXT;
    private static final String TEXT_MESSAGE = " " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE;
    private static final String MAPS_MESSAGE = "must be an https Google Maps link (maps.google.com, google.com/maps, maps.app.goo.gl or goo.gl/maps)";

    private ClientDemoDTOs() {
    }

    // ------------------------------------------------------------------ requests

    /**
     * The plan of a demo: the clients visited (each on a day, between two times that don't overlap another visit that
     * day, and never the same client twice in a row on a day), the start date, for how long, who attends, the products
     * shown and the proposed budget. Once the quotation is approved every change needs a {@code changeReason}; changing
     * the budget or the number of days then sends the quotation back for approval.
     *
     * <p>{@code plannedBills}, when sent, is the full day-by-day plan of stays, meals and travel (STAY, FOOD and TRAVEL
     * bills): bills with an {@code id} are changed, those without are added (in the order given, so their ids ascend in
     * that order), and stay, food and travel bills left out are removed. Leave it out to keep the bills as they are.
     *
     * <p>The proposed budget is the plan's total plus an optional extra amount; any extra needs
     * {@code extraBudgetReason}, the reason and its breakup.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DemoRequest {
        @NotNull(message = "Type is required (APPOINTMENT or PROMOTION)")
        private DemoType demoType;

        @NotEmpty(message = "Add at least one client to visit")
        @Size(max = MAX_VISITS, message = "At most " + MAX_VISITS + " clients can be visited on one demo")
        @Valid
        private List<VisitRequest> clients;

        @NotNull(message = "Date is required")
        private LocalDate startDate;

        @NotNull(message = "Number of days is required")
        @Min(value = 1, message = "Number of days must be at least 1")
        @Max(value = MAX_DAYS, message = "Number of days cannot exceed " + MAX_DAYS)
        private Integer numberOfDays;

        @NotNull(message = "Proposed budget is required")
        @DecimalMin(value = "0.00", message = "Proposed budget cannot be negative")
        @MaxAmount(message = "Proposed budget cannot exceed " + ValidationPatterns.MAX_AMOUNT_LABEL + " and can have at most 2 decimal places")
        private BigDecimal proposedBudget;

        // Why the budget is more than the day-by-day plan, and its breakup; required when it is
        @Size(max = 1000, message = "Reason for the extra budget cannot exceed 1000 characters")
        private String extraBudgetReason;

        @Size(max = 1000, message = "Purpose cannot exceed 1000 characters")
        private String purpose;

        @Size(max = MAX_STAFF, message = "At most " + MAX_STAFF + " staff can attend one demo")
        private List<Long> staffEmployeeIds;

        @Size(max = MAX_PRODUCTS, message = "At most " + MAX_PRODUCTS + " products can be shown on one demo")
        @Valid
        private List<ProductRequest> products;

        @Size(max = MAX_EXPENSES, message = "At most " + MAX_EXPENSES + " bills fit on one demo")
        @Valid
        private List<ExpenseRequest> plannedBills;

        // Why the plan changes, once the quotation was approved
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        private String changeReason;
    }

    /** One client visited, and when: a day of the trip and the expected time there (e.g. 10:00 to 13:00). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VisitRequest {
        @NotNull(message = "Client category is required")
        private ClientCategory clientCategory;

        // Required when the category is OTHER
        @Size(max = 100, message = "Client category cannot exceed 100 characters")
        @Pattern(regexp = OPTIONAL_TEXT, message = "Client category" + TEXT_MESSAGE)
        private String clientCategoryOther;

        @NotBlank(message = "Client name is required")
        @Size(max = 150, message = "Client name cannot exceed 150 characters")
        @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Client name" + TEXT_MESSAGE)
        private String clientName;

        // A business client's registered name and GSTIN; kept only for categories that have them
        @Size(max = 150, message = "Legal name cannot exceed 150 characters")
        @Pattern(regexp = OPTIONAL_TEXT, message = "Legal name" + TEXT_MESSAGE)
        private String legalName;

        @Pattern(regexp = "^\\s*$|" + ValidationPatterns.GSTIN, message = "GSTIN must be 15 characters like 22AAAAA0000A1Z5 (capital letters)")
        private String gstin;

        // The client's own phone and email (front desk, reception), not its contact person's
        @NotBlank(message = "Client phone is required")
        @Pattern(regexp = ValidationPatterns.PHONE, message = "Client phone " + ValidationPatterns.PHONE_MESSAGE)
        private String clientPhone;

        @Size(max = 255, message = "Client email cannot exceed 255 characters")
        @Pattern(regexp = "^\\s*$|" + ValidationPatterns.EMAIL, message = "Client email " + ValidationPatterns.EMAIL_MESSAGE)
        private String clientEmail;

        @Size(max = 100, message = "Contact person cannot exceed 100 characters")
        @Pattern(regexp = ValidationPatterns.OPTIONAL_PERSON_NAME, message = "Contact person " + ValidationPatterns.OPTIONAL_PERSON_NAME_MESSAGE)
        private String contactPerson;

        @NotBlank(message = "Contact phone is required")
        @Pattern(regexp = ValidationPatterns.PHONE, message = "Contact phone " + ValidationPatterns.PHONE_MESSAGE)
        private String contactPhone;

        @Size(max = 255, message = "Contact email cannot exceed 255 characters")
        @Pattern(regexp = "^\\s*$|" + ValidationPatterns.EMAIL, message = "Contact email " + ValidationPatterns.EMAIL_MESSAGE)
        private String contactEmail;

        @NotBlank(message = "Address is required")
        @Size(max = 300, message = "Address cannot exceed 300 characters")
        @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Address" + TEXT_MESSAGE)
        private String addressLine;

        @NotBlank(message = "City is required")
        @Size(max = 100, message = "City cannot exceed 100 characters")
        @Pattern(regexp = ValidationPatterns.CITY, message = "City " + ValidationPatterns.CITY_MESSAGE)
        private String city;

        @Size(max = 100, message = "State cannot exceed 100 characters")
        @Pattern(regexp = "^\\s*$|" + ValidationPatterns.CITY, message = "State must be a valid state name")
        private String state;

        @Pattern(regexp = "^\\s*$|" + ValidationPatterns.PINCODE, message = "PIN code " + ValidationPatterns.PINCODE_MESSAGE)
        private String pincode;

        @UrlInput
        @GoogleMapsUrl(message = "Client location " + MAPS_MESSAGE)
        private String mapLink;

        @NotNull(message = "Day of the visit is required")
        @Min(value = 1, message = "Day must be at least 1")
        @Max(value = MAX_DAYS, message = "Day cannot exceed " + MAX_DAYS)
        private Integer dayNumber;

        @NotNull(message = "Visit start time is required")
        private LocalTime startTime;

        @NotNull(message = "Visit end time is required")
        private LocalTime endTime;
    }

    /**
     * A storefront product shown on the demo and the barcodes of the units taken (each one of its units in stock, not
     * held by an overlapping demo); the number of units is the number of barcodes. A product listed before units were
     * picked by barcode may keep only its number of units while it is unchanged.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductRequest {
        @NotNull(message = "Product is required")
        @Min(value = 1, message = "Product is required")
        private Long productId;

        // The product's title as the storefront shows it, kept as chosen
        @NotBlank(message = "Product name is required")
        @Size(max = 200, message = "Product name cannot exceed 200 characters")
        private String productName;

        @NotNull(message = "Number of units is required")
        @Min(value = 1, message = "Take at least 1 unit")
        @Max(value = MAX_PRODUCT_UNITS, message = "At most " + MAX_PRODUCT_UNITS + " units of one product")
        private Integer quantity;

        @Size(max = MAX_PRODUCT_UNITS, message = "At most " + MAX_PRODUCT_UNITS + " units of one product")
        private List<String> barcodes;
    }

    /**
     * One bill. STAY: hotel (vendor) name, address, location, GSTIN, invoice number. FOOD: meal and restaurant.
     * TRAVEL: from, to, mode and ticket number. OTHER: what it was and why it was needed. Changing or removing a bill
     * once the quotation is approved needs a {@code changeReason}.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExpenseRequest {
        // Only in a plan's plannedBills: the bill this changes; blank for a new one
        private Long id;

        @NotNull(message = "Bill type is required (STAY, FOOD, TRAVEL or OTHER)")
        private ExpenseKind kind;

        @Min(value = 1, message = "Day must be at least 1")
        @Max(value = MAX_DAYS, message = "Day cannot exceed " + MAX_DAYS)
        private Integer dayNumber;

        @Size(max = 150, message = "Name cannot exceed 150 characters")
        @Pattern(regexp = OPTIONAL_TEXT, message = "Name" + TEXT_MESSAGE)
        private String vendorName;

        @Size(max = 300, message = "Address cannot exceed 300 characters")
        @Pattern(regexp = OPTIONAL_TEXT, message = "Address" + TEXT_MESSAGE)
        private String vendorAddress;

        @UrlInput
        @GoogleMapsUrl(message = "Location " + MAPS_MESSAGE)
        private String vendorMapLink;

        @Pattern(regexp = "^\\s*$|" + ValidationPatterns.PHONE, message = "Phone " + ValidationPatterns.PHONE_MESSAGE)
        private String vendorPhone;

        @Pattern(regexp = "^\\s*$|" + ValidationPatterns.GSTIN, message = "GSTIN must be 15 characters like 22AAAAA0000A1Z5 (capital letters)")
        private String vendorGstin;

        private MealType mealType;

        @Size(max = 150, message = "From cannot exceed 150 characters")
        @Pattern(regexp = OPTIONAL_TEXT, message = "From" + TEXT_MESSAGE)
        private String fromPlace;

        @Size(max = 150, message = "To cannot exceed 150 characters")
        @Pattern(regexp = OPTIONAL_TEXT, message = "To" + TEXT_MESSAGE)
        private String toPlace;

        private TravelMode travelMode;

        // A stay's check-in and check-out, or a journey's departure and arrival: all four or none; it may end the next day
        private LocalDate startDate;

        private LocalTime startTime;

        private LocalDate endDate;

        private LocalTime endTime;

        @Size(max = 60, message = "Invoice / ticket number cannot exceed 60 characters")
        @Pattern(regexp = OPTIONAL_TEXT, message = "Invoice / ticket number" + TEXT_MESSAGE)
        private String referenceNumber;

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.00", message = "Amount cannot be negative")
        @MaxAmount(message = "Amount cannot exceed " + ValidationPatterns.MAX_AMOUNT_LABEL + " and can have at most 2 decimal places")
        private BigDecimal amount;

        @DecimalMin(value = "0.00", message = "GST cannot be negative")
        @MaxAmount(message = "GST cannot exceed " + ValidationPatterns.MAX_AMOUNT_LABEL + " and can have at most 2 decimal places")
        private BigDecimal gstAmount;

        @Size(max = 300, message = "Description cannot exceed 300 characters")
        private String description;

        // Why an OTHER cost was needed
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        private String reason;

        @Size(max = 500, message = "Reason for the change cannot exceed 500 characters")
        private String changeReason;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReasonRequest {
        @NotBlank(message = "A reason is required")
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        private String reason;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompleteRequest {
        @NotBlank(message = "Describe the outcome of the visit")
        @Size(max = 1000, message = "Outcome cannot exceed 1000 characters")
        private String outcome;
    }

    /** Units of the demo handed out or handed back, by barcode (scanned or picked). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UnitsRequest {
        @NotEmpty(message = "Scan or pick at least one unit")
        @Size(max = MAX_PRODUCT_UNITS, message = "At most " + MAX_PRODUCT_UNITS + " units at once")
        private List<String> barcodes;
    }

    /** A Super Admin writes off a unit that did not come back from the demo. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WriteOffRequest {
        @NotBlank(message = "Barcode is required")
        private String barcode;

        @NotBlank(message = "Say what happened to the unit")
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        private String reason;
    }

    public enum Decision { APPROVED, REJECTED }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DecisionRequest {
        @NotNull(message = "Decision is required (APPROVED or REJECTED)")
        private Decision decision;

        // Required to reject
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        private String reason;
    }

    // ------------------------------------------------------------------ responses

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StaffMember {
        private Long employeeId;
        private String employeeCode;
        private String name;
        private String designation;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private Long id;
        private String reference;
        private DemoType demoType;
        private DemoStatus status;
        private String statusLabel;
        private ClientCategory clientCategory;
        private String clientCategoryLabel;
        private String clientName;
        private String city;
        // How many clients the trip visits; the one above is the first
        private int clientCount;
        private LocalDate startDate;
        private LocalTime startTime;
        private Integer numberOfDays;
        private LocalDate endDate;
        private BigDecimal proposedBudget;
        private BigDecimal total;
        private BigDecimal bookedTotal;
        private List<String> staffNames;
        private String createdByName;
        private Instant submittedAt;
        // Whether the caller may approve or reject what is waiting (never what they made or attend)
        private boolean decidableByYou;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Expense {
        private Long id;
        private ExpenseKind kind;
        private Integer dayNumber;
        private LocalDate date;
        private String vendorName;
        private String vendorAddress;
        private String vendorMapLink;
        private String vendorPhone;
        private String vendorGstin;
        private MealType mealType;
        private String fromPlace;
        private String toPlace;
        private TravelMode travelMode;
        private LocalDate startDate;
        private LocalTime startTime;
        private LocalDate endDate;
        private LocalTime endTime;
        private String referenceNumber;
        private BigDecimal amount;
        private BigDecimal gstAmount;
        private String description;
        private String reason;
        private String invoiceFileName;
        private Long addedBy;
        private String addedByName;
        private Instant addedAt;
        private String updatedByName;
        private Instant updatedAt;
        private boolean editableByYou;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Visit {
        private Long id;
        private ClientCategory clientCategory;
        private String clientCategoryLabel;
        private String clientCategoryOther;
        private String clientName;
        private String legalName;
        private String gstin;
        private String clientPhone;
        private String clientEmail;
        private String contactPerson;
        private String contactPhone;
        private String contactEmail;
        private String addressLine;
        private String city;
        private String state;
        private String pincode;
        private String mapLink;
        private Integer dayNumber;
        private LocalDate date;
        private LocalTime startTime;
        private LocalTime endTime;
    }

    /** A product shown, its number of units and their barcodes (empty for a product listed before barcodes were picked). */
    public record ProductLine(Long productId, String productName, Integer quantity, List<String> barcodes) {
    }

    /** A unit taken to the demo and where it is. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Unit {
        private String barcode;
        private Long productId;
        private String productName;
        private DemoUnitStatus status;
        private String statusLabel;
        private Instant handedOutAt;
        private String handedOutByName;
        private Instant returnedAt;
        private String returnedByName;
        private String writeOffReason;
    }

    /**
     * A product unit that may be picked for a demo: its barcode, product and stock status, and whether it can be
     * picked for the demo's days ({@code heldBy}: the demo that has it then).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UnitOption {
        private String barcode;
        private Long productId;
        private String productTitle;
        private String status;
        private String statusLabel;
        private java.time.LocalDateTime inStockSince;
        private boolean available;
        private String heldBy;
        // Why it can't be picked, when it can't
        private String note;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Event {
        private DemoEventType type;
        private String actorName;
        private Instant at;
        private String detail;
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Detail {
        private Long id;
        private String reference;
        private DemoType demoType;
        private DemoStatus status;
        private String statusLabel;
        private ClientCategory clientCategory;
        private String clientCategoryLabel;
        private String clientCategoryOther;
        private String clientName;
        private String contactPerson;
        private String contactPhone;
        private String contactEmail;
        private String addressLine;
        private String city;
        private String state;
        private String pincode;
        private String mapLink;
        private LocalDate startDate;
        private LocalTime startTime;
        private Integer numberOfDays;
        private LocalDate endDate;
        private BigDecimal proposedBudget;
        // What the budget holds beyond the day-by-day plan when it was set, and why
        private BigDecimal extraBudget;
        private String extraBudgetReason;
        private String purpose;
        private String outcome;
        // Every client visited, by day and time; the client fields above are the first
        private List<Visit> clients;
        private List<ProductLine> products;
        // Every unit taken, by barcode
        private List<Unit> units;
        // Whether the caller may hand units out or back now, and write off a unit that is out
        private boolean handlesUnits;
        private boolean writesOffUnits;
        private List<StaffMember> staff;
        private List<Expense> expenses;
        // Spent per bill type (STAY, FOOD, TRAVEL, OTHER), the total, and what is left of the budget (negative: over)
        private Map<ExpenseKind, BigDecimal> totalsByKind;
        private BigDecimal total;
        private BigDecimal budgetLeft;
        private BigDecimal bookedTotal;
        private Long createdBy;
        private String createdByName;
        private Instant createdAt;
        private String submittedByName;
        private Instant submittedAt;
        private String decidedByName;
        private Instant decidedAt;
        private String rejectionReason;
        private String cancelReason;
        private String reopenReason;
        private Instant completedAt;
        private List<Event> events;
        private boolean decidableByYou;
        // Whether the caller is tagged as attending staff
        private boolean attendingYou;
    }

    /** The company account feed: what each completion and correction booked. */
    public record PostingEvent(
            Long eventId,
            String postingKey,
            String demoReference,
            String clientName,
            String clientCategory,
            DemoPostingKind kind,
            BigDecimal amount,
            LocalDate occurredOn,
            Instant changedAt) {
    }

    public record PostingEventPage(List<PostingEvent> events, boolean hasMore) {
    }
}
