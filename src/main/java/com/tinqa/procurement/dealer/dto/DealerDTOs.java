package com.tinqa.procurement.dealer.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Set;

public class DealerDTOs {

    @Getter
    @Setter
    public static class CreateRequest {
        @NotBlank(message = "Dealer name is required")
        private String name;

        private String tradeName;

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        private String email;

        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^[0-9+\\-\\s]{8,20}$", message = "Invalid phone number")
        private String phoneNumber;

        private String alternatePhoneNumber;

        // Address
        @NotBlank(message = "Street address is required")
        private String street;

        private String landmark;

        @NotBlank(message = "City is required")
        private String city;

        @NotBlank(message = "State is required")
        private String state;

        @NotBlank(message = "Country is required")
        private String country = "India";

        @NotBlank(message = "Pincode is required")
        private String pincode;

        private String googleMapsUrl;

        // Legal & Business
        @Pattern(regexp = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$", message = "Invalid GSTIN format")
        private String gstin;

        private Boolean isGstVerified = false;

        @Pattern(regexp = "^[A-Z]{5}[0-9]{4}[A-Z]{1}$", message = "Invalid PAN number format")
        private String panNumber;

        @Min(value = 1800, message = "Invalid business since year")
        private Integer businessSince;

        @Min(value = 0, message = "Employee count cannot be negative")
        private Integer employeeCount;

        // Capabilities
        private Boolean offersShipping = false;
        private Boolean doesBulkDealing = true;
        private Boolean doesWholesaleDealing = true;

        @NotEmpty(message = "At least one category must be assigned")
        private Set<Long> categoryIds;
    }

    @Getter
    @Setter
    public static class UpdateRequest {
        @NotBlank(message = "Dealer name is required")
        private String name;

        private String tradeName;

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        private String email;

        @NotBlank(message = "Phone number is required")
        private String phoneNumber;

        private String alternatePhoneNumber;

        @NotBlank(message = "Street address is required")
        private String street;

        private String landmark;

        @NotBlank(message = "City is required")
        private String city;

        @NotBlank(message = "State is required")
        private String state;

        @NotBlank(message = "Country is required")
        private String country;

        @NotBlank(message = "Pincode is required")
        private String pincode;

        private String googleMapsUrl;

        private String gstin;
        private Boolean isGstVerified;
        private String panNumber;
        private Integer businessSince;
        private Integer employeeCount;

        private Boolean offersShipping;
        private Boolean doesBulkDealing;
        private Boolean doesWholesaleDealing;

        @NotEmpty(message = "At least one category must be assigned")
        private Set<Long> categoryIds;

        private Boolean isActive;
    }

    @Getter
    @Setter
    public static class Response {
        private Long id;
        private String name;
        private String tradeName;
        private String email;
        private String phoneNumber;
        private String alternatePhoneNumber;

        private String street;
        private String landmark;
        private String city;
        private String state;
        private String country;
        private String pincode;
        private String googleMapsUrl;

        private String gstin;
        private Boolean isGstVerified;
        private String panNumber;
        private Integer businessSince;
        private Integer employeeCount;

        private Boolean offersShipping;
        private Boolean doesBulkDealing;
        private Boolean doesWholesaleDealing;

        private Set<CategorySummary> categories;

        private Boolean isActive;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private Long createdBy;
        private Long updatedBy;
    }

    @Getter
    @Setter
    public static class CategorySummary {
        private Long id;
        private String name;
        private String code;
    }
}