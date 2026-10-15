package com.tinqa.procurement.security.access;

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

        public static MyAccess of(UserAccess access) {
            return new MyAccess(
                    access.role() == null ? null : access.role().name(),
                    access.designation() == null ? null : access.designation().name(),
                    access.designation() == null ? null : access.designation().label(),
                    access.groups().stream().map(Enum::name).sorted().toList(),
                    access.permissions().stream().map(Enum::name).sorted().toList());
        }
    }

    /** The whole catalog: every designation, group and permission, and how they connect. */
    public record Catalog(List<DesignationEntry> designations, List<GroupEntry> groups, List<PermissionEntry> permissions) {

        public static Catalog build() {
            return new Catalog(
                    Arrays.stream(Designation.values()).map(DesignationEntry::of).toList(),
                    Arrays.stream(PermissionGroup.values()).map(GroupEntry::of).toList(),
                    Arrays.stream(Permission.values()).map(PermissionEntry::of).toList());
        }
    }

    /** {@code groups} are what the designation holds at ADMIN_L2; ADMIN_L1 holds them minus the approval-level ones. */
    public record DesignationEntry(String code, String label, String department, String description, List<String> groups) {

        static DesignationEntry of(Designation designation) {
            return new DesignationEntry(designation.name(), designation.label(), designation.department(), designation.description(),
                    DesignationAccess.groupsOf(designation).stream().map(Enum::name).toList());
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
