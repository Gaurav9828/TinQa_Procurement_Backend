package com.tinqa.procurement.security.access;

import static com.tinqa.procurement.security.access.PermissionGroup.*;

/**
 * Every action an authenticated user can take, each in exactly one {@link PermissionGroup}. Endpoints declare the
 * action they perform with {@link RequiresPermission}; services ask {@link CurrentAccess} when a rule depends on who
 * is acting.
 *
 * <p>The names are a contract: they travel in the access token ({@code perms} claim) to the Ecommerce backend and in
 * the login / access responses to the web UI, which both check them by name. Rename only together with those.
 * Permissions marked "not wired yet" have no endpoint today; they reserve the action so screens and future endpoints
 * can be built against it.
 */
public enum Permission {

    // Employees (/v1/admin/employees, /admin/profile-approvals)
    EMPLOYEE_VIEW(EMPLOYEE_READ, "View employees"),
    EMPLOYEE_CREATE(EMPLOYEE_MANAGEMENT, "Create employees"),
    EMPLOYEE_UPDATE(EMPLOYEE_MANAGEMENT, "Edit employees"),
    EMPLOYEE_REQUEST_DELETION(EMPLOYEE_MANAGEMENT, "Request an employee's deletion"),
    EMPLOYEE_APPROVE(EMPLOYEE_APPROVAL, "Approve or reject new employee accounts"),
    EMPLOYEE_DELETE(EMPLOYEE_APPROVAL, "Finalize an employee's deletion"),
    EMPLOYEE_PROFILE_CHANGE_APPROVE(EMPLOYEE_APPROVAL, "Approve or reject employee profile changes"),

    // Dealers (/v1/admin/dealers)
    DEALER_VIEW(DEALER_READ, "View dealers and dealer categories"),
    DEALER_CREATE(DEALER_MANAGEMENT, "Onboard dealers"),
    DEALER_UPDATE(DEALER_MANAGEMENT, "Edit dealers"),
    DEALER_TOGGLE_STATUS(DEALER_MANAGEMENT, "Activate or deactivate dealers"),
    DEALER_CATEGORY_MANAGE(DEALER_MANAGEMENT, "Create dealer categories and assign them"),
    DEALER_DELETE(DEALER_APPROVAL, "Remove dealers"),

    // Procurement orders (/v1/orders)
    PROCUREMENT_ORDER_VIEW(PROCUREMENT_ORDER_READ, "View procurement orders"),
    PROCUREMENT_ORDER_CREATE(PROCUREMENT_ORDER_MANAGEMENT, "Create quotations / RFQs"),
    PROCUREMENT_ORDER_UPDATE(PROCUREMENT_ORDER_MANAGEMENT, "Edit quotations"),
    PROCUREMENT_ORDER_UPDATE_STATUS(PROCUREMENT_ORDER_MANAGEMENT, "Progress orders (submit, ship, deliver, returns, refunds)"),
    PROCUREMENT_ORDER_APPROVE(PROCUREMENT_ORDER_APPROVAL, "Approve orders, confirm with dealers and decide returns"),

    // Catalog: items (/v1/admin/items), procurement products (/v1/admin/products), product approvals
    // (/v1/product-approvals) and storefront products (Ecommerce /api/products)
    ITEM_VIEW(CATALOG_READ, "View items, item categories and warranties"),
    PRODUCT_VIEW(CATALOG_READ, "View products and their approval history"),
    ITEM_CREATE(CATALOG_MANAGEMENT, "Create items"),
    ITEM_UPDATE(CATALOG_MANAGEMENT, "Edit items"),
    ITEM_DELETE(CATALOG_MANAGEMENT, "Delete items"),
    ITEM_CATEGORY_MANAGE(CATALOG_MANAGEMENT, "Create item categories"),
    PRODUCT_CREATE(CATALOG_MANAGEMENT, "Create products"),
    PRODUCT_UPDATE(CATALOG_MANAGEMENT, "Edit products"),
    PRODUCT_DELETE(CATALOG_MANAGEMENT, "Delete products"),
    PRODUCT_APPROVAL_SUBMIT(CATALOG_MANAGEMENT, "Submit storefront products for approval"),
    PRODUCT_PUBLISH(CATALOG_MANAGEMENT, "Publish approved products to the storefront"),
    PRODUCT_APPROVAL_DECIDE(CATALOG_APPROVAL, "Review the approval queue and approve or reject products"),

    // Inventory (/v1/stocks, Ecommerce PATCH /api/products/{id}/stock)
    STOCK_VIEW(INVENTORY_READ, "View stock and availability"),
    STOCK_CREATE(INVENTORY_MANAGEMENT, "Record received stock"),
    STOCK_ADJUST(INVENTORY_MANAGEMENT, "Add or deduct stock quantities"),
    STOCK_UPDATE(INVENTORY_MANAGEMENT, "Edit stock entries"),
    STOREFRONT_STOCK_UPDATE(INVENTORY_MANAGEMENT, "Set storefront product stock"),
    STOCK_APPROVE(INVENTORY_APPROVAL, "Approve or reject stock entries"),

    // Storefront customer orders (Ecommerce /api/admin/orders, /api/orders/{n}/status|tracking)
    CUSTOMER_ORDER_VIEW(CUSTOMER_ORDER_READ, "View customer orders and their tracking"),
    CUSTOMER_ORDER_UPDATE_STATUS(CUSTOMER_ORDER_MANAGEMENT, "Change a customer order's status"),
    CUSTOMER_ORDER_PRE_ORDER_APPROVE(CUSTOMER_ORDER_MANAGEMENT, "Accept pre-orders"),
    CUSTOMER_ORDER_TRACKING_UPDATE(DELIVERY_OPERATIONS, "Update shipment tracking and delivery status"),
    // Ecommerce PATCH /api/orders/{n}/delivery-status: only out for delivery, delivered, customer unavailable,
    // invalid address, customer cancelled delivery and re-attempt; every other status needs the permission above
    DELIVERY_STATUS_UPDATE(LAST_MILE_DELIVERY, "Record delivery outcomes (out for delivery, delivered, failed attempts)"),
    DELIVERY_ASSIGN(DELIVERY_OPERATIONS, "Assign deliveries to riders (not wired yet)"),

    // Refunds (Ecommerce /api/admin/refunds)
    REFUND_VIEW(REFUND_READ, "View refunds"),
    REFUND_REQUEST(REFUND_MANAGEMENT, "Request a refund for an order"),
    REFUND_SYNC(REFUND_MANAGEMENT, "Re-check a refund with the payment gateway"),
    REFUND_APPROVE(REFUND_APPROVAL, "Approve or reject refunds"),

    // Customer care (Ecommerce /api/admin/support, /api/admin/reviews)
    SUPPORT_TICKET_VIEW(CUSTOMER_SUPPORT, "View support tickets"),
    SUPPORT_TICKET_REPLY(CUSTOMER_SUPPORT, "Reply to support tickets"),
    SUPPORT_TICKET_UPDATE_STATUS(CUSTOMER_SUPPORT, "Change a support ticket's status"),
    REVIEW_VIEW(REVIEW_MODERATION, "View product reviews"),
    REVIEW_MODERATE(REVIEW_MODERATION, "Publish or hide product reviews"),

    // Company profile (Ecommerce /api/admin/company)
    COMPANY_PROFILE_VIEW(COMPANY_PROFILE_READ, "View the company profile and its versions"),
    COMPANY_PROFILE_EDIT(COMPANY_PROFILE_MANAGEMENT, "Draft, submit and withdraw company profile changes"),
    COMPANY_PROFILE_APPROVE(COMPANY_PROFILE_APPROVAL, "Approve or reject company profile changes"),

    // Documents (/v1/documents)
    DOCUMENT_VIEW(DOCUMENT_READ, "View and download documents"),
    DOCUMENT_UPLOAD(DOCUMENT_MANAGEMENT, "Upload documents"),
    DOCUMENT_DELETE(DOCUMENT_MANAGEMENT, "Delete documents"),
    DOCUMENT_APPROVE(DOCUMENT_APPROVAL, "Approve or reject documents"),

    // Money (not wired yet: the payments, finance and analytics screens have no backend endpoints yet)
    PAYMENT_VIEW(PAYMENT_READ, "View payments and chalans"),
    PAYMENT_RECORD(PAYMENT_MANAGEMENT, "Record payments and chalans"),
    PAYMENT_APPROVE(PAYMENT_APPROVAL, "Approve payments and dealer payouts"),
    FINANCE_REPORT_VIEW(FINANCE_REPORTING, "View sales, tax, revenue and COD reports"),
    FINANCE_REPORT_EXPORT(FINANCE_REPORTING, "Export finance reports"),
    ANALYTICS_VIEW(ANALYTICS, "View analytics dashboards"),

    // Platform (not wired yet)
    SYSTEM_SETTINGS_MANAGE(SYSTEM_ADMINISTRATION, "Manage global settings, payment gateways and shipping rates"),
    AUDIT_LOG_VIEW(SYSTEM_ADMINISTRATION, "View audit logs"),

    // Dealer portal (not wired yet)
    DEALER_QUOTATION_SUBMIT(DEALER_PORTAL, "Submit quotations for open requests"),
    DEALER_ORDER_RESPOND(DEALER_PORTAL, "Accept, reject or confirm bulk orders");

    /** Prefix of the Spring Security authority each permission is granted as. */
    public static final String AUTHORITY_PREFIX = "PERM_";

    private final PermissionGroup group;
    private final String label;

    Permission(PermissionGroup group, String label) {
        this.group = group;
        this.label = label;
    }

    public PermissionGroup group() {
        return group;
    }

    public String label() {
        return label;
    }

    public String authority() {
        return AUTHORITY_PREFIX + name();
    }
}
