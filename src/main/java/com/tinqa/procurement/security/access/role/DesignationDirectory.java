package com.tinqa.procurement.security.access.role;

import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.security.access.Designation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/** Finds a designation by its code among the built-in designations and the custom roles. */
@Component
@RequiredArgsConstructor
public class DesignationDirectory {

    private final CustomRoleRepository customRoleRepository;

    /** The designation with this code (any letter case), built-in or custom (active or not); empty when unknown. */
    public Optional<AssignedDesignation> find(String code) {
        String normalized = OptionalText.blankToNull(code) == null ? null : code.trim().toUpperCase(Locale.ROOT);
        if (normalized == null) {
            return Optional.empty();
        }
        Optional<Designation> builtIn = Designation.fromCode(normalized);
        if (builtIn.isPresent()) {
            return builtIn.map(AssignedDesignation::of);
        }
        return customRoleRepository.findWithGroupsByCode(normalized).map(AssignedDesignation::of);
    }

    /**
     * The designation to give an employee: a built-in one or an active custom role. {@code current} (the employee's
     * designation now) may be sent back unchanged even when its custom role was deactivated since.
     */
    public AssignedDesignation requireAssignable(String code, AssignedDesignation current) {
        AssignedDesignation found = find(code).orElseThrow(() -> ApiErrors.fieldError("designation",
                "Designation " + code.trim() + " doesn't exist. Pick a built-in designation or an active custom role."));
        if (found.custom() != null && !found.custom().isActive() && !found.sameAs(current)) {
            throw ApiErrors.fieldError("designation", "The role " + found.label() + " is inactive, so it can't be given to employees.");
        }
        return found;
    }
}
