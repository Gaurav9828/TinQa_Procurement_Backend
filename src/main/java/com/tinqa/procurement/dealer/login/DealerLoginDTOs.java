package com.tinqa.procurement.dealer.login;

import com.tinqa.procurement.common.validation.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

public final class DealerLoginDTOs {

    private DealerLoginDTOs() {
    }

    /** A new login for a dealer, or a rejected one submitted again with corrected details. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        @NotBlank(message = "Username is required")
        @Pattern(regexp = ValidationPatterns.USERNAME, message = "Username " + ValidationPatterns.USERNAME_MESSAGE)
        private String username;

        @NotBlank(message = "Login email is required")
        @Size(max = 150, message = "Login email cannot exceed 150 characters")
        @Pattern(regexp = ValidationPatterns.EMAIL, message = "Login email " + ValidationPatterns.EMAIL_MESSAGE)
        private String email;
    }

    public enum Decision {
        APPROVED,
        REJECTED
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DecisionRequest {
        @NotNull(message = "Decision is required")
        private Decision decision;

        // Required to reject (checked in the service)
        @Size(max = 500, message = "Rejection reason cannot exceed 500 characters")
        private String rejectionReason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusRequest {
        @NotNull(message = "Enabled is required")
        private Boolean enabled;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Long id;
        private Long dealerId;
        private String dealerName;
        private boolean dealerActive;
        private String username;
        private String email;
        private DealerLoginStatus status;
        /** True until the dealer replaces the default password */
        private boolean passwordChangePending;
        private Long createdBy;
        private String createdByUsername;
        private Instant createdAt;
        private String decidedByUsername;
        private Instant decidedAt;
        private String rejectionReason;
    }
}
