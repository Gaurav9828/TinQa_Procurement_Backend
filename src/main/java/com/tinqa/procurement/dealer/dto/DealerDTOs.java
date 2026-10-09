package com.tinqa.procurement.dealer.dto;

import com.tinqa.procurement.common.validation.*;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Set;

public class DealerDTOs {

    @Getter
    @Setter
    @ValidAddress
    @ValidTaxIdentity
    public static class CreateRequest implements PostalAddress, TaxIdentity {
        @NotBlank(message = "Dealer name is required")
        @Size(max = 255, message = "Dealer name cannot exceed 255 characters")
        @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Dealer name " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
        private String name;

        @Size(max = 255, message = "Trade name cannot exceed 255 characters")
        @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Trade name " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
        private String tradeName;

        @NotBlank(message = "Email is required")
        @Size(max = 150, message = "Email cannot exceed 150 characters")
        @Pattern(regexp = ValidationPatterns.EMAIL, message = "Email " + ValidationPatterns.EMAIL_MESSAGE)
        private String email;

        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = ValidationPatterns.PHONE, message = "Phone number " + ValidationPatterns.PHONE_MESSAGE)
        private String phoneNumber;

        @Pattern(regexp = ValidationPatterns.PHONE, message = "Alternate phone number " + ValidationPatterns.PHONE_MESSAGE)
        private String alternatePhoneNumber;

        // Address
        @NotBlank(message = "Street address is required")
        @Size(max = 500, message = "Street address cannot exceed 500 characters")
        @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Street address " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
        private String street;

        @Size(max = 255, message = "Landmark cannot exceed 255 characters")
        @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Landmark " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
        private String landmark;

        @NotBlank(message = "City is required")
        @Size(max = 100, message = "City cannot exceed 100 characters")
        @Pattern(regexp = ValidationPatterns.CITY, message = "City " + ValidationPatterns.CITY_MESSAGE)
        private String city;

        @NotBlank(message = "State is required")
        @Size(max = 100, message = "State cannot exceed 100 characters")
        @Pattern(regexp = ValidationPatterns.CITY, message = "State must be a valid state name")
        private String state;

        @NotBlank(message = "Country is required")
        @Size(max = 100, message = "Country cannot exceed 100 characters")
        @ValidCountry(message = "Country must be a valid country name, e.g. India")
        private String country = "India";

        @NotBlank(message = "Pincode is required")
        @Size(max = 10, message = "Pincode cannot exceed 10 characters")
        private String pincode;

        @UrlInput
        @GoogleMapsUrl(message = "Google Maps link must be an https Google Maps link (maps.google.com, google.com/maps, maps.app.goo.gl or goo.gl/maps)")
        private String googleMapsUrl;

        // Legal & Business
        @Pattern(regexp = ValidationPatterns.GSTIN, message = "GSTIN must be 15 characters like 22AAAAA0000A1Z5 (capital letters)")
        private String gstin;

        private Boolean isGstVerified = false;

        @Pattern(regexp = ValidationPatterns.PAN, message = "PAN must be 10 characters like ABCPE1234F (capital letters)")
        private String panNumber;

        @NotFutureYear(min = 1800, message = "Business since must be a year between 1800 and the current year")
        private Integer businessSince;

        @Min(value = 0, message = "Employee count cannot be negative")
        @Max(value = 1000000, message = "Employee count cannot exceed 10,00,000")
        private Integer employeeCount;

        // Capabilities
        private Boolean offersShipping = false;
        private Boolean doesBulkDealing = true;
        private Boolean doesWholesaleDealing = true;

        @NotEmpty(message = "At least one category must be assigned")
        @Size(max = 50, message = "A dealer cannot have more than 50 categories")
        private Set<Long> categoryIds;
    }

    @Getter
    @Setter
    @ValidAddress
    @ValidTaxIdentity
    public static class UpdateRequest implements PostalAddress, TaxIdentity {
        @NotBlank(message = "Dealer name is required")
        @Size(max = 255, message = "Dealer name cannot exceed 255 characters")
        @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Dealer name " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
        private String name;

        @Size(max = 255, message = "Trade name cannot exceed 255 characters")
        @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Trade name " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
        private String tradeName;

        @NotBlank(message = "Email is required")
        @Size(max = 150, message = "Email cannot exceed 150 characters")
        @Pattern(regexp = ValidationPatterns.EMAIL, message = "Email " + ValidationPatterns.EMAIL_MESSAGE)
        private String email;

        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = ValidationPatterns.PHONE, message = "Phone number " + ValidationPatterns.PHONE_MESSAGE)
        private String phoneNumber;

        @Pattern(regexp = ValidationPatterns.PHONE, message = "Alternate phone number " + ValidationPatterns.PHONE_MESSAGE)
        private String alternatePhoneNumber;

        @NotBlank(message = "Street address is required")
        @Size(max = 500, message = "Street address cannot exceed 500 characters")
        @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Street address " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
        private String street;

        @Size(max = 255, message = "Landmark cannot exceed 255 characters")
        @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Landmark " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
        private String landmark;

        @NotBlank(message = "City is required")
        @Size(max = 100, message = "City cannot exceed 100 characters")
        @Pattern(regexp = ValidationPatterns.CITY, message = "City " + ValidationPatterns.CITY_MESSAGE)
        private String city;

        @NotBlank(message = "State is required")
        @Size(max = 100, message = "State cannot exceed 100 characters")
        @Pattern(regexp = ValidationPatterns.CITY, message = "State must be a valid state name")
        private String state;

        @NotBlank(message = "Country is required")
        @Size(max = 100, message = "Country cannot exceed 100 characters")
        @ValidCountry(message = "Country must be a valid country name, e.g. India")
        private String country;

        @NotBlank(message = "Pincode is required")
        @Size(max = 10, message = "Pincode cannot exceed 10 characters")
        private String pincode;

        @UrlInput
        @GoogleMapsUrl(message = "Google Maps link must be an https Google Maps link (maps.google.com, google.com/maps, maps.app.goo.gl or goo.gl/maps)")
        private String googleMapsUrl;

        @Pattern(regexp = ValidationPatterns.GSTIN, message = "GSTIN must be 15 characters like 22AAAAA0000A1Z5 (capital letters)")
        private String gstin;
        private Boolean isGstVerified;
        @Pattern(regexp = ValidationPatterns.PAN, message = "PAN must be 10 characters like ABCPE1234F (capital letters)")
        private String panNumber;
        @NotFutureYear(min = 1800, message = "Business since must be a year between 1800 and the current year")
        private Integer businessSince;
        @Min(value = 0, message = "Employee count cannot be negative")
        @Max(value = 1000000, message = "Employee count cannot exceed 10,00,000")
        private Integer employeeCount;

        private Boolean offersShipping;
        private Boolean doesBulkDealing;
        private Boolean doesWholesaleDealing;

        @NotEmpty(message = "At least one category must be assigned")
        @Size(max = 50, message = "A dealer cannot have more than 50 categories")
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