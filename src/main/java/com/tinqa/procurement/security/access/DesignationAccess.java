package com.tinqa.procurement.security.access;

import com.tinqa.procurement.security.model.Role;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.tinqa.procurement.security.access.PermissionGroup.*;

/**
 * The single place that says who may do what: designation -> permission groups -> permissions.
 *
 * <p>An account's effective access is the groups of its designation, minus the approval-level groups when the
 * account is ADMIN_L1. So a designation says which business areas someone works in, and the admin level says
 * whether they may also approve there. Mobile and partner accounts (DEALER, INSPECTOR) get the groups of their role
 * instead and never act as employees.
 */
public final class DesignationAccess {

    private static final Map<Designation, Set<PermissionGroup>> GROUPS_BY_DESIGNATION = new EnumMap<>(Designation.class);
    private static final Map<Role, Set<PermissionGroup>> GROUPS_BY_EXTERNAL_ROLE = new EnumMap<>(Role.class);

    static {
        // Owns the platform: every internal group
        Set<PermissionGroup> everythingInternal = EnumSet.allOf(PermissionGroup.class);
        everythingInternal.remove(DEALER_PORTAL);
        grant(Designation.SUPER_ADMIN, everythingInternal);

        grant(Designation.STORE_MANAGER, EnumSet.of(
                ANALYTICS,
                CATALOG_READ, CATALOG_MANAGEMENT, CATALOG_APPROVAL,
                COSTING_READ, COSTING_MANAGEMENT,
                INVENTORY_READ, INVENTORY_APPROVAL,
                CUSTOMER_ORDER_READ, CUSTOMER_ORDER_MANAGEMENT, DELIVERY_OPERATIONS,
                STORE_SALES_READ, STORE_SALES,
                REFUND_READ, REFUND_MANAGEMENT, REFUND_APPROVAL,
                CUSTOMER_SUPPORT, REVIEW_MODERATION,
                CUSTOMER_READ, CUSTOMER_MANAGEMENT,
                COMPANY_PROFILE_READ, COMPANY_PROFILE_MANAGEMENT, COMPANY_PROFILE_APPROVAL,
                DOCUMENT_READ, DOCUMENT_MANAGEMENT,
                PAYMENT_READ,
                EXPENSE_READ, EXPENSE_MANAGEMENT,
                USER_GUIDE));

        grant(Designation.DEALER_MANAGER, EnumSet.of(
                DEALER_READ, DEALER_MANAGEMENT, DEALER_APPROVAL,
                PROCUREMENT_ORDER_READ, PROCUREMENT_ORDER_MANAGEMENT, PROCUREMENT_ORDER_APPROVAL,
                CATALOG_READ,
                INVENTORY_READ,
                DOCUMENT_READ, DOCUMENT_MANAGEMENT,
                PAYMENT_READ,
                EXPENSE_READ, EXPENSE_MANAGEMENT));

        grant(Designation.CATALOG_SPECIALIST, EnumSet.of(
                CATALOG_READ, CATALOG_MANAGEMENT,
                COSTING_READ, COSTING_MANAGEMENT,
                INVENTORY_READ,
                DOCUMENT_READ, DOCUMENT_MANAGEMENT));

        grant(Designation.FULFILLMENT_SPECIALIST, EnumSet.of(
                CATALOG_READ,
                INVENTORY_READ, INVENTORY_MANAGEMENT,
                CUSTOMER_ORDER_READ, CUSTOMER_ORDER_MANAGEMENT, DELIVERY_OPERATIONS,
                EXPENSE_READ, EXPENSE_MANAGEMENT));

        // Riders only record what happened at the door; the rest of the lifecycle stays with the roles above
        grant(Designation.DELIVERY_RIDER, EnumSet.of(
                CUSTOMER_ORDER_READ, LAST_MILE_DELIVERY));

        // The riders' senior: records the same delivery outcomes as a rider, and also moves orders along the rest
        // of the delivery leg (e.g. sending a refused or undeliverable parcel back to the warehouse)
        grant(Designation.FLEET_COORDINATOR, EnumSet.of(
                CUSTOMER_ORDER_READ, LAST_MILE_DELIVERY, DELIVERY_OPERATIONS,
                EXPENSE_READ, EXPENSE_MANAGEMENT));

        grant(Designation.CUSTOMER_SUPPORT, EnumSet.of(
                CUSTOMER_SUPPORT,
                CUSTOMER_READ, CUSTOMER_MANAGEMENT,
                CUSTOMER_ORDER_READ,
                REFUND_READ, REFUND_MANAGEMENT,
                CATALOG_READ));

        grant(Designation.FINANCIAL_AUDITOR, EnumSet.of(
                ANALYTICS, FINANCE_REPORTING, COSTING_READ,
                STORE_SALES_READ,
                PAYMENT_READ,
                REFUND_READ,
                CUSTOMER_ORDER_READ,
                PROCUREMENT_ORDER_READ,
                DOCUMENT_READ,
                EXPENSE_READ));

        // Keeps the books: approves expenses (posting them to the company account), reads every money flow
        grant(Designation.FINANCE_MANAGER, EnumSet.of(
                ANALYTICS, FINANCE_REPORTING, COSTING_READ,
                EXPENSE_READ, EXPENSE_MANAGEMENT, EXPENSE_APPROVAL,
                PAYMENT_READ, PAYMENT_MANAGEMENT, PAYMENT_APPROVAL,
                STORE_SALES_READ,
                REFUND_READ,
                CUSTOMER_ORDER_READ,
                PROCUREMENT_ORDER_READ,
                DOCUMENT_READ));

        // Sells at a store or in the market: registers walk-in customers and books their orders. Cash they take is
        // booked when they record it; they see only their own sales
        grant(Designation.SALES_AGENT, EnumSet.of(
                STORE_SALES));

        GROUPS_BY_EXTERNAL_ROLE.put(Role.DEALER, Collections.unmodifiableSet(EnumSet.of(DEALER_PORTAL)));

        for (Designation designation : Designation.values()) {
            if (!GROUPS_BY_DESIGNATION.containsKey(designation)) {
                throw new IllegalStateException("Designation " + designation + " has no permission groups");
            }
        }
    }

    private DesignationAccess() {
    }

    // Every employee also gets their own records (SELF_SERVICE), whatever their designation
    private static void grant(Designation designation, Set<PermissionGroup> groups) {
        Set<PermissionGroup> withSelfService = EnumSet.copyOf(groups);
        withSelfService.add(SELF_SERVICE);
        GROUPS_BY_DESIGNATION.put(designation, Collections.unmodifiableSet(withSelfService));
    }

    /** Groups a designation carries at ADMIN_L2 (the most it can ever hold). */
    public static Set<PermissionGroup> groupsOf(Designation designation) {
        return GROUPS_BY_DESIGNATION.get(designation);
    }

    /**
     * Whether Level 2 adds anything for this designation, i.e. it carries at least one approval-level group. When it
     * doesn't, the designation only exists at Level 1.
     */
    public static boolean hasApprovalLevel(Designation designation) {
        return designation != null && GROUPS_BY_DESIGNATION.get(designation).stream().anyMatch(PermissionGroup::approvalLevel);
    }

    /** The level an admin account with this designation can actually have: Level 2 only where it adds approval powers. */
    public static Role effectiveLevel(Role requested, Designation designation) {
        return requested == Role.ADMIN_L2 && !hasApprovalLevel(designation) ? Role.ADMIN_L1 : requested;
    }

    /**
     * The groups an account actually holds. Admin accounts without a designation hold nothing, so an account can
     * never gain access by accident.
     */
    public static Set<PermissionGroup> effectiveGroups(Role role, Designation designation) {
        if (role == Role.ADMIN_L1 || role == Role.ADMIN_L2) {
            if (designation == null) {
                return Set.of();
            }
            Set<PermissionGroup> groups = EnumSet.noneOf(PermissionGroup.class);
            groups.addAll(GROUPS_BY_DESIGNATION.get(designation));
            if (role == Role.ADMIN_L1) {
                groups.removeIf(PermissionGroup::approvalLevel);
            }
            return Collections.unmodifiableSet(groups);
        }
        return role == null ? Set.of() : GROUPS_BY_EXTERNAL_ROLE.getOrDefault(role, Set.of());
    }

    /** Every permission of the given groups. */
    public static Set<Permission> permissionsOf(Set<PermissionGroup> groups) {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        for (Permission permission : Permission.values()) {
            if (groups.contains(permission.group())) {
                permissions.add(permission);
            }
        }
        return Collections.unmodifiableSet(permissions);
    }
}
