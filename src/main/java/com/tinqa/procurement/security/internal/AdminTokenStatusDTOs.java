package com.tinqa.procurement.security.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class AdminTokenStatusDTOs {

    private AdminTokenStatusDTOs() {
    }

    /** The admin's bearer token exactly as the Ecommerce service received it. */
    public record Request(@NotBlank @Size(max = 8192) String token) {
    }

    /**
     * {@code active} false: signed out, password changed since, account disabled or deleted, first password not set
     * yet, or not a valid token at all. When active, {@code role} and {@code permissions} are the account's access now
     * (not what the token says), so a designation change applies to the Ecommerce service too.
     */
    public record Response(boolean active, String role, List<String> permissions) {

        public static Response inactive() {
            return new Response(false, null, List.of());
        }
    }
}
