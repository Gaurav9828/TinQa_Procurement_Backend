package com.tinqa.procurement.outreach;

import com.tinqa.procurement.clientdemo.ClientCategory;
import com.tinqa.procurement.common.validation.GoogleMapsUrl;
import com.tinqa.procurement.common.validation.UrlInput;
import com.tinqa.procurement.common.validation.ValidationPatterns;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Requests and views of /v1/outreach. */
public final class OutreachDTOs {

    private static final String OPTIONAL_TEXT = "^\\s*$|" + ValidationPatterns.SINGLE_LINE_TEXT;
    private static final String TEXT_MESSAGE = " " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE;
    // Digits with an optional leading + and spaces, dashes or brackets between them
    private static final String PHONE = "^\\s*$|^\\+?[0-9][0-9 ()-]{5,18}[0-9]$";

    private OutreachDTOs() {
    }

    // ------------------------------------------------------------------ requests

    public record BusinessRequest(
            @NotBlank(message = "Name is required")
            @Size(max = 200, message = "Name cannot exceed 200 characters")
            @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Name" + TEXT_MESSAGE)
            String name,

            @NotNull(message = "Business type is required")
            ClientCategory businessType,

            @Size(max = 100, message = "City cannot exceed 100 characters")
            @Pattern(regexp = OPTIONAL_TEXT, message = "City" + TEXT_MESSAGE)
            String city,

            @Size(max = 500, message = "Address cannot exceed 500 characters")
            String address,

            @UrlInput
            @GoogleMapsUrl
            String googleMapsUrl,

            @Pattern(regexp = PHONE, message = "Phone must be a phone number such as 9876543210 or +44 20 7946 0958")
            String phone,

            @Size(max = 255, message = "Email cannot exceed 255 characters")
            @Email(message = "Email must be a valid email address")
            String email,

            @Size(max = 150, message = "Contact person cannot exceed 150 characters")
            @Pattern(regexp = OPTIONAL_TEXT, message = "Contact person" + TEXT_MESSAGE)
            String contactPerson,

            @Size(max = 2000, message = "Notes cannot exceed 2000 characters")
            String notes) {
    }

    /** One contact; {@code contactedAt} defaults to now, {@code followUpAt} is required unless nextAction is NONE. */
    public record ContactRequest(
            OffsetDateTime contactedAt,

            @NotNull(message = "Channel is required (CALL, EMAIL, WHATSAPP, IN_PERSON or OTHER)")
            ContactChannel channel,

            @Size(max = 150, message = "Contact person cannot exceed 150 characters")
            @Pattern(regexp = OPTIONAL_TEXT, message = "Contact person" + TEXT_MESSAGE)
            String contactPerson,

            @Pattern(regexp = PHONE, message = "Contact phone must be a phone number such as 9876543210")
            String contactPhone,

            @NotBlank(message = "Summary is required")
            @Size(max = 4000, message = "Summary cannot exceed 4000 characters")
            String summary,

            @Size(max = 2000, message = "Response cannot exceed 2000 characters")
            String response,

            @NotNull(message = "Rating is required (EXCELLENT, GOOD, AVERAGE, BAD or WORSE)")
            Rating rating,

            @NotNull(message = "Interest is required (INTERESTED, MAYBE, LATER or NOT_INTERESTED)")
            Interest interest,

            @NotNull(message = "Next action is required (NONE, CALL_BACK, DEMO_REQUESTED or MEETING_REQUESTED)")
            NextAction nextAction,

            OffsetDateTime followUpAt) {
    }

    // ------------------------------------------------------------------ views

    public record Business(Long id, String name, ClientCategory businessType, String city, String address, String googleMapsUrl,
                           String phone, String email, String contactPerson, String notes, int contactCount, int demoRequestCount,
                           OffsetDateTime lastContactAt, String lastContactByName, Interest latestInterest, Rating latestRating,
                           OffsetDateTime nextFollowUpAt, String createdByName, OffsetDateTime createdAt) {
    }

    public record Proof(Long id, String fileName, String contentType, long size) {
    }

    /** {@code editable}: the caller logged it, so they may change it and its proofs. */
    public record Contact(Long id, Long businessId, String businessName, OffsetDateTime contactedAt, ContactChannel channel,
                          String contactPerson, String contactPhone, String summary, String response, Rating rating,
                          Interest interest, NextAction nextAction, OffsetDateTime followUpAt, List<Proof> proofs,
                          Long loggedById, String loggedByName, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                          boolean editable) {
    }

    public record DayCount(LocalDate date, long contacts, long businesses) {
    }

    public record ExecutiveCount(Long userId, String name, long contacts, long businesses, long interested) {
    }

    public record Summary(long totalContacts, long businessesContacted, long newBusinesses, long demoRequests, long followUpsDue,
                          List<DayCount> byDay, List<ExecutiveCount> byExecutive, Map<String, Long> byChannel,
                          Map<String, Long> byInterest) {
    }
}
