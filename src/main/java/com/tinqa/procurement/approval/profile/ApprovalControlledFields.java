package com.tinqa.procurement.approval.profile;

import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.employee.model.Employee;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Compares and applies the {@link ApprovalControlledProfile} fields of an employee.
 */
public final class ApprovalControlledFields {

    public static final String DATE_OF_BIRTH = "dateOfBirth";
    public static final String FIRST_NAME = "firstName";
    public static final String MIDDLE_NAME = "middleName";
    public static final String LAST_NAME = "lastName";
    public static final String GENDER = "gender";

    private ApprovalControlledFields() {
    }

    /** Names of the fields whose requested value differs from the employee's, in a stable order. */
    public static Set<String> changedFields(Employee employee, ApprovalControlledProfile requested) {
        if (requested == null) {
            return Collections.emptySet();
        }
        Set<String> changed = new LinkedHashSet<>();
        addIfChanged(changed, DATE_OF_BIRTH, employee.getDateOfBirth(), requested.getDateOfBirth());
        addIfChanged(changed, FIRST_NAME, employee.getFirstName(), requested.getFirstName());
        if (OptionalText.changes(employee.getMiddleName(), requested.getMiddleName())) {
            changed.add(MIDDLE_NAME);
        }
        addIfChanged(changed, LAST_NAME, employee.getLastName(), requested.getLastName());
        addIfChanged(changed, GENDER, employee.getGender(), requested.getGender());
        return changed;
    }

    /** Writes every requested value onto the employee; a blank middle name removes it. */
    public static void apply(Employee employee, ApprovalControlledProfile requested) {
        Set<String> changed = changedFields(employee, requested);
        if (changed.contains(DATE_OF_BIRTH)) employee.setDateOfBirth(requested.getDateOfBirth());
        if (changed.contains(FIRST_NAME)) employee.setFirstName(requested.getFirstName());
        if (changed.contains(MIDDLE_NAME)) employee.setMiddleName(OptionalText.blankToNull(requested.getMiddleName()));
        if (changed.contains(LAST_NAME)) employee.setLastName(requested.getLastName());
        if (changed.contains(GENDER)) employee.setGender(requested.getGender());
    }

    private static void addIfChanged(Set<String> changed, String field, Object current, Object requested) {
        if (requested != null && !Objects.equals(current, requested)) {
            changed.add(field);
        }
    }
}
