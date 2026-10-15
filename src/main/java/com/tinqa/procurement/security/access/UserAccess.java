package com.tinqa.procurement.security.access;

import com.tinqa.procurement.security.model.Role;

import java.util.Set;

/**
 * What one account may do.
 *
 * @param role        the account's role (admin level for employees)
 * @param designation the employee's designation; null for non-employees and admins without one
 * @param groups      the permission groups the account holds
 * @param permissions every permission of those groups
 */
public record UserAccess(Role role, Designation designation, Set<PermissionGroup> groups, Set<Permission> permissions) {

    public static UserAccess of(Role role, Designation designation) {
        Set<PermissionGroup> groups = DesignationAccess.effectiveGroups(role, designation);
        return new UserAccess(role, designation, groups, DesignationAccess.permissionsOf(groups));
    }

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }
}
