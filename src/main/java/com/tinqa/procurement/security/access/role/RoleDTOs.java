package com.tinqa.procurement.security.access.role;

import com.tinqa.procurement.common.validation.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Requests and views of /v1/access/roles. */
public final class RoleDTOs {

    private RoleDTOs() {
    }

    /**
     * A custom role. {@code active} is read on update only (a new role is active). {@code groups} are permission group
     * codes; SELF_SERVICE may be sent but is always added anyway.
     */
    public record RoleRequest(
            @NotBlank(message = "Name is required")
            @Size(max = 100, message = "Name cannot exceed 100 characters")
            @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Name " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
            String label,

            @NotBlank(message = "Department is required")
            @Size(max = 100, message = "Department cannot exceed 100 characters")
            @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Department " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
            String department,

            @Size(max = 500, message = "Description cannot exceed 500 characters")
            String description,

            @NotEmpty(message = "Pick at least one permission group")
            @Size(max = 100, message = "Too many permission groups")
            List<String> groups,

            Boolean active) {
    }

    /** A built-in designation ({@code id} null, read-only) or a custom role. */
    public record RoleView(Long id, String code, String label, String department, String description, List<String> groups,
                           boolean custom, boolean active, long employeeCount, String createdByName, Instant updatedAt) {
    }

    /** A permission group the caller may put in a custom role. */
    public record GrantableGroup(String code, String label, boolean approvalLevel) {
    }
}
