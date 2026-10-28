package com.tinqa.procurement.clientdemo;

import com.tinqa.procurement.common.validation.GoogleMapsUrl;
import com.tinqa.procurement.common.validation.MaxAmount;
import com.tinqa.procurement.common.validation.UrlInput;
import com.tinqa.procurement.common.validation.ValidationPatterns;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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

    private static final String OPTIONAL_TEXT = "^\\s*$|" + ValidationPatterns.SINGLE_LINE_TEXT;
    private static final String TEXT_MESSAGE = " " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE;
    private static final String MAPS_MESSAGE = "must be an https Google Maps link (maps.google.com, google.com/maps, maps.app.goo.gl or goo.gl/maps)";

    private ClientDemoDTOs() {
    }

    // ------------------------------------------------------------------ requests

    /**
     * The plan of a demo: the client, when, for how long, who attends and the proposed budget. Once the quotation is
     * approved every change needs a {@code changeReason}; changing the budget or the number of days then sends the
     * quotation back for approval.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DemoRequest {
        @NotNull(message = "Type is required (APPOINTMENT or PROMOTION)")
        private DemoType demoType;

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

        @NotNull(message = "Date is required")
        private LocalDate startDate;

        @NotNull(message = "Time is required")
        private LocalTime startTime;

        @NotNull(message = "Number of days is required")
        @Min(value = 1, message = "Number of days must be at least 1")
        @Max(value = MAX_DAYS, message = "Number of days cannot exceed " + MAX_DAYS)
        private Integer numberOfDays;

        @NotNull(message = "Proposed budget is required")
        @DecimalMin(value = "0.00", message = "Proposed budget cannot be negative")
        @MaxAmount(message = "Proposed budget cannot exceed " + ValidationPatterns.MAX_AMOUNT_LABEL + " and can have at most 2 decimal places")
        private BigDecimal proposedBudget;

        @Size(max = 1000, message = "Purpose cannot exceed 1000 characters")
        private String purpose;

        @Size(max = MAX_STAFF, message = "At most " + MAX_STAFF + " staff can attend one demo")
        private List<Long> staffEmployeeIds;

        // Why the plan changes, once the quotation was approved
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        private String changeReason;
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
        private String purpose;
        private String outcome;
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
