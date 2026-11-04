package com.tinqa.procurement.security.access;

import com.tinqa.procurement.security.model.Role;

import java.util.Set;

/**
 * What one account may do.
 *
 * @param role        the account's role (admin level for employees)
 * @param designation the employee's built-in designation; null for non-employees, custom-role holders and admins without one
 * @param customRole  the employee's custom role, when they hold one instead of a built-in designation
 * @param groups      the permission groups the account holds
 * @param permissions every permission of those groups
 */
public record UserAccess(Role role, Designation designation, CustomRoleGrant customRole, Set<PermissionGroup> groups,
                         Set<Permission> permissions) {

    public static UserAccess of(Role role, Designation designation) {
        Set<PermissionGroup> groups = DesignationAccess.effectiveGroups(role, designation);
        return new UserAccess(role, designation, null, groups, DesignationAccess.permissionsOf(groups));
    }

    /** An employee with a custom role (a built-in designation wins when both are given, which the database prevents). */
    public static UserAccess of(Role role, Designation designation, CustomRoleGrant customRole) {
        if (designation != null || customRole == null) {
            return of(role, designation);
        }
        Set<PermissionGroup> groups = DesignationAccess.effectiveGroupsOfCustomRole(role, customRole);
        return new UserAccess(role, null, customRole, groups, DesignationAccess.permissionsOf(groups));
    }

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }

    /** The designation's code: a built-in designation's name or the custom role's code; null when there is none. */
    public String designationCode() {
        return designation != null ? designation.name() : customRole != null ? customRole.code() : null;
    }

    public String designationLabel() {
        return designation != null ? designation.label() : customRole != null ? customRole.label() : null;
    }
}
