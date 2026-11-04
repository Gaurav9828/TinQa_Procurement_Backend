package com.tinqa.procurement.security.access.role;

import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.security.access.AccessResolver;
import com.tinqa.procurement.security.access.CustomRoleGrant;
import com.tinqa.procurement.security.access.Designation;
import com.tinqa.procurement.security.access.DesignationAccess;
import com.tinqa.procurement.security.model.Role;

/**
 * The designation an employee holds or is given: a built-in {@link Designation} or a {@link CustomRole}, exactly one
 * of them. Its department is the employee's department.
 */
public record AssignedDesignation(Designation builtIn, CustomRole custom) {

    public AssignedDesignation {
        if ((builtIn == null) == (custom == null)) {
            throw new IllegalArgumentException("Exactly one of a built-in designation and a custom role");
        }
    }

    public static AssignedDesignation of(Designation designation) {
        return new AssignedDesignation(designation, null);
    }

    public static AssignedDesignation of(CustomRole role) {
        return new AssignedDesignation(null, role);
    }

    /** The employee's current designation; null for (old) records without one. */
    public static AssignedDesignation of(Employee employee) {
        if (employee.getDesignation() != null) {
            return of(employee.getDesignation());
        }
        return employee.getCustomRole() != null ? of(employee.getCustomRole()) : null;
    }

    public String code() {
        return builtIn != null ? builtIn.name() : custom.getCode();
    }

    public String label() {
        return builtIn != null ? builtIn.label() : custom.getLabel();
    }

    public String department() {
        return builtIn != null ? builtIn.department() : custom.getDepartment();
    }

    public boolean isSuperAdmin() {
        return builtIn == Designation.SUPER_ADMIN;
    }

    /** Level 2 only exists where the designation carries approval groups. */
    public Role effectiveLevel(Role requested) {
        return builtIn != null ? DesignationAccess.effectiveLevel(requested, builtIn)
                : DesignationAccess.effectiveLevelOfCustomRole(requested, AccessResolver.grantOf(custom));
    }

    public boolean sameAs(AssignedDesignation other) {
        return other != null && code().equals(other.code());
    }

    /** Gives the employee this designation and its department. */
    public void applyTo(Employee employee) {
        employee.setDesignation(builtIn);
        employee.setCustomRole(custom);
        employee.setDepartment(department());
    }
}
