package com.tinqa.procurement.security.access;

import com.tinqa.procurement.security.model.Role;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What the access endpoints and the login response return, so clients check permissions by name instead of
 * re-deriving them from the role.
 */
public final class AccessDTOs {

    private AccessDTOs() {
    }

    /** One account's access. */
    public record MyAccess(String role, String designation, String designationLabel, List<String> permissionGroups,
                           List<String> permissions) {

        // Suppliers are not employees and have no designation; they are labelled by their role instead
        private static final String SUPPLIER_LABEL = "Supplier";

        public static MyAccess of(UserAccess access) {
            String label = access.designationLabel() != null ? access.designationLabel()
                    : access.role() == Role.SUPPLIER ? SUPPLIER_LABEL : null;
            return new MyAccess(
                    access.role() == null ? null : access.role().name(),
                    access.designationCode(),
                    label,
                    access.groups().stream().map(Enum::name).sorted().toList(),
                    access.permissions().stream().map(Enum::name).sorted().toList());
        }
    }

    /**
     * The whole catalog: every designation (built-in, then the active custom roles), group and permission, and how they
     * connect.
     */
    public record Catalog(List<DesignationEntry> designations, List<GroupEntry> groups, List<PermissionEntry> permissions) {

        public static Catalog build() {
            return build(List.of());
        }

        /** @param customRoles the active custom roles, listed after the built-in designations */
        public static Catalog build(List<DesignationEntry> customRoles) {
            List<DesignationEntry> designations = new ArrayList<>(Arrays.stream(Designation.values()).map(DesignationEntry::of).toList());
            designations.addAll(customRoles);
            return new Catalog(
                    List.copyOf(designations),
                    Arrays.stream(PermissionGroup.values()).map(GroupEntry::of).toList(),
                    Arrays.stream(Permission.values()).map(PermissionEntry::of).toList());
        }
    }

    /**
     * {@code groups} are what the designation holds at ADMIN_L2; ADMIN_L1 holds them minus the approval-level ones.
     * {@code id} is set for custom roles only.
     */
    public record DesignationEntry(Long id, String code, String label, String department, String description, List<String> groups,
                                   boolean custom, boolean active) {

        static DesignationEntry of(Designation designation) {
            return new DesignationEntry(null, designation.name(), designation.label(), designation.department(), designation.description(),
                    DesignationAccess.groupsOf(designation).stream().map(Enum::name).toList(), false, true);
        }

        public static DesignationEntry of(CustomRoleGrant role, String description, boolean active) {
            return new DesignationEntry(role.id(), role.code(), role.label(), role.department(), description,
                    DesignationAccess.groupsOfCustomRole(role).stream().sorted().map(Enum::name).toList(), true, active);
        }
    }

    public record GroupEntry(String code, String label, boolean approvalLevel) {

        static GroupEntry of(PermissionGroup group) {
            return new GroupEntry(group.name(), group.label(), group.approvalLevel());
        }
    }

    public record PermissionEntry(String code, String label, String group) {

        static PermissionEntry of(Permission permission) {
            return new PermissionEntry(permission.name(), permission.label(), permission.group().name());
        }
    }
}
