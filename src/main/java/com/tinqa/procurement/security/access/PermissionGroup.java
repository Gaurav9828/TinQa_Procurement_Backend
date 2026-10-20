package com.tinqa.procurement.security.access;

/**
 * A named bundle of {@link Permission}s. Every permission belongs to exactly one group, and access is granted per
 * group: a user may perform an action when they hold the group the action belongs to.
 *
 * <p>Groups follow one naming scheme per business area: {@code <AREA>_READ} (look), {@code <AREA>_MANAGEMENT}
 * (make and change, the "maker" side) and {@code <AREA>_APPROVAL} (decide on what a maker submitted, the "checker"
 * side). Approval groups are only ever granted to ADMIN_L2 accounts, so an ADMIN_L1 can prepare work in an area but
 * never approve it. A new feature goes into an existing group, or into a new group following this scheme.
 */
public enum PermissionGroup {

    // People
    EMPLOYEE_READ("Employees - view", false),
    EMPLOYEE_MANAGEMENT("Employees - create and edit", false),
    EMPLOYEE_APPROVAL("Employees - approve accounts, deletions and profile changes", true),

    // Dealers (B2B partners)
    DEALER_READ("Dealers - view", false),
    DEALER_MANAGEMENT("Dealers - create and edit", false),
    DEALER_APPROVAL("Dealers - approve dealer logins and remove dealers", true),

    // Procurement orders (RFQs / quotations placed with dealers)
    PROCUREMENT_ORDER_READ("Procurement orders - view", false),
    PROCUREMENT_ORDER_MANAGEMENT("Procurement orders - create, progress and decide dealers' quotation changes", false),
    PROCUREMENT_ORDER_APPROVAL("Procurement orders - approve, confirm and decide returns", true),

    // Catalog (items, procurement products and storefront listings)
    CATALOG_READ("Catalog - view", false),
    CATALOG_MANAGEMENT("Catalog - create and edit listings", false),
    CATALOG_APPROVAL("Catalog - approve listings and pricing", true),

    // Inventory
    INVENTORY_READ("Inventory - view stock", false),
    INVENTORY_MANAGEMENT("Inventory - receive and adjust stock", false),
    INVENTORY_APPROVAL("Inventory - approve stock entries", true),

    // Storefront customer orders and delivery
    CUSTOMER_ORDER_READ("Customer orders - view", false),
    CUSTOMER_ORDER_MANAGEMENT("Customer orders - progress and override", false),
    DELIVERY_OPERATIONS("Delivery - update tracking and delivery status", false),
    LAST_MILE_DELIVERY("Delivery - record delivery attempts and outcomes", false),

    // Refunds
    REFUND_READ("Refunds - view", false),
    REFUND_MANAGEMENT("Refunds - request and sync", false),
    REFUND_APPROVAL("Refunds - approve or reject", true),

    // Customer care
    CUSTOMER_SUPPORT("Support tickets - view and respond", false),
    REVIEW_MODERATION("Product reviews - moderate", false),

    // Company profile (storefront branding and legal details)
    COMPANY_PROFILE_READ("Company profile - view", false),
    COMPANY_PROFILE_MANAGEMENT("Company profile - draft changes", false),
    COMPANY_PROFILE_APPROVAL("Company profile - approve changes", true),

    // Documents
    DOCUMENT_READ("Documents - view and download", false),
    DOCUMENT_MANAGEMENT("Documents - upload and delete", false),
    DOCUMENT_APPROVAL("Documents - approve or reject", true),

    // Money
    PAYMENT_READ("Payments - view", false),
    PAYMENT_MANAGEMENT("Payments - record", false),
    PAYMENT_APPROVAL("Payments - approve", true),
    FINANCE_REPORTING("Finance - company account, reports and exports", false),
    EXPENSE_READ("Expenses - view", false),
    EXPENSE_MANAGEMENT("Expenses - raise for approval", false),
    EXPENSE_APPROVAL("Expenses - approve, reject and reverse", true),
    ANALYTICS("Analytics - dashboards", false),

    // Platform
    SYSTEM_ADMINISTRATION("System - settings, integrations and audit logs", true),
    // The in-app user guide: every role's flows, downloadable as a PDF to hand to a colleague
    USER_GUIDE("User guide - view and share role guides", false),

    // External: dealers signed in to this web app with a dealer login. Everything in it is limited to the dealer's own
    // records on the server, whatever is asked for
    DEALER_PORTAL("Dealer portal - own quotations, orders and bills", false);

    private final String label;
    private final boolean approvalLevel;

    PermissionGroup(String label, boolean approvalLevel) {
        this.label = label;
        this.approvalLevel = approvalLevel;
    }

    public String label() {
        return label;
    }

    /** True when only ADMIN_L2 accounts may hold this group (checker / owner powers). */
    public boolean approvalLevel() {
        return approvalLevel;
    }
}
