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
    DEALER_CATEGORY_MANAGE(DEALER_MANAGEMENT, "Assign item categories to dealers"),
    DEALER_DELETE(DEALER_APPROVAL, "Remove dealers"),
    // Dealer logins (/v1/admin/dealers/{id}/login, /v1/admin/dealer-logins): a Dealer Manager creates one, and it can
    // sign in once someone else approves it
    DEALER_LOGIN_MANAGE(DEALER_MANAGEMENT, "Create dealer logins, disable them and reset their passwords"),
    DEALER_LOGIN_APPROVE(DEALER_APPROVAL, "Approve or reject dealer logins"),

    // Procurement orders (/v1/orders)
    PROCUREMENT_ORDER_VIEW(PROCUREMENT_ORDER_READ, "View procurement orders"),
    PROCUREMENT_ORDER_CREATE(PROCUREMENT_ORDER_MANAGEMENT, "Create quotations / RFQs"),
    PROCUREMENT_ORDER_UPDATE(PROCUREMENT_ORDER_MANAGEMENT, "Edit quotations"),
    PROCUREMENT_ORDER_UPDATE_STATUS(PROCUREMENT_ORDER_MANAGEMENT, "Progress orders (submit, ship, deliver, returns, refunds)"),
    PROCUREMENT_ORDER_APPROVE(PROCUREMENT_ORDER_APPROVAL, "Approve orders, confirm with dealers and decide returns"),
    // /v1/orders/change-requests: a dealer's edit to a quotation only applies once approved. The dealer is the maker,
    // so any Dealer Manager (either level) is the checker
    DEALER_QUOTATION_CHANGE_APPROVE(PROCUREMENT_ORDER_MANAGEMENT, "Approve or reject dealers' changes to their quotations"),

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

    // Product costing (Ecommerce /api/admin/costing, the cost fields of /api/products, /v1/stocks/items/{id}/cost):
    // a product's cost sheet (component purchase costs + extra costs with their reasons), its profit before and after
    // tax, and the profit of each customer order
    PRODUCT_COST_VIEW(COSTING_READ, "View product cost sheets, item purchase costs and product / order profit"),
    PRODUCT_COST_EDIT(COSTING_MANAGEMENT, "Enter component costs and extra costs (manpower, software...) on products"),

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
    // invalid address, customer cancelled delivery and re-attempt; every other status needs the permission above.
    // Held by delivery riders and their senior, the fleet coordinator
    DELIVERY_STATUS_UPDATE(LAST_MILE_DELIVERY, "Record delivery outcomes (out for delivery, delivered, failed attempts)"),
    DELIVERY_ASSIGN(DELIVERY_OPERATIONS, "Assign deliveries to riders (not wired yet)"),

    // Store sales (Ecommerce /api/admin/store-sales). An agent registers a walk-in customer and books their order, paid
    // in cash at the counter (booked to the company account at once: the agent received it) or online by the customer.
    // The customer is emailed their login details
    STORE_SALES_VIEW_ALL(STORE_SALES_READ, "View every agent's store sales"),
    STORE_CUSTOMER_REGISTER(STORE_SALES, "Register walk-in customers and add their delivery addresses"),
    STORE_ORDER_CREATE(STORE_SALES, "Book orders for customers (cash received, or online)"),
    STORE_ORDER_VIEW(STORE_SALES, "View your own store sales"),

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

    // Storefront customers (Ecommerce /api/admin/customers, /v1/admin/customer-change-requests). Customer care asks
    // for a correction to a customer's details; the holders of CUSTOMER_UPDATE (Super Admin) are notified and make it
    CUSTOMER_VIEW(CUSTOMER_READ, "View customers, their orders, carts and support tickets"),
    CUSTOMER_CHANGE_REQUEST(CUSTOMER_MANAGEMENT, "Ask for a change to a customer's details"),
    CUSTOMER_UPDATE(CUSTOMER_APPROVAL, "Edit a customer's details"),

    // Company profile (Ecommerce /api/admin/company)
    COMPANY_PROFILE_VIEW(COMPANY_PROFILE_READ, "View the company profile and its versions"),
    COMPANY_PROFILE_EDIT(COMPANY_PROFILE_MANAGEMENT, "Draft, submit, withdraw and delete your own company profile changes"),
    COMPANY_PROFILE_APPROVE(COMPANY_PROFILE_APPROVAL, "Approve or reject company profile changes"),

    // Documents (/v1/documents)
    DOCUMENT_VIEW(DOCUMENT_READ, "View and download documents"),
    DOCUMENT_UPLOAD(DOCUMENT_MANAGEMENT, "Upload documents"),
    DOCUMENT_DELETE(DOCUMENT_MANAGEMENT, "Delete documents"),
    DOCUMENT_APPROVE(DOCUMENT_APPROVAL, "Approve or reject documents"),

    // Money. Salary payments (/v1/admin/payroll): recorded as transferred, confirmed by someone else, and only then
    // booked to the company account and emailed as payslips. Finance reports and expenses are served by Ecommerce
    // /api/admin/finance
    PAYMENT_VIEW(PAYMENT_READ, "View salary payments and download any employee's payslip"),
    PAYMENT_RECORD(PAYMENT_MANAGEMENT, "Record salaries as transferred, withdraw them and resend payslips"),
    PAYMENT_APPROVE(PAYMENT_APPROVAL, "Confirm or reject recorded salary payments (books them and emails the payslips)"),
    FINANCE_REPORT_VIEW(FINANCE_REPORTING, "View the company account, ledger and profit & loss / cash flow analytics"),
    FINANCE_REPORT_EXPORT(FINANCE_REPORTING, "Export finance reports (PDF, Excel, Word)"),
    // Expenses and other vouchers (Ecommerce /api/admin/finance/vouchers): raised, approved by someone else, then
    // posted to the company account. Shipping costs entered when an order ships become vouchers too
    EXPENSE_VIEW(EXPENSE_READ, "View expenses and other vouchers"),
    EXPENSE_SUBMIT(EXPENSE_MANAGEMENT, "Raise expenses and other vouchers for approval"),
    EXPENSE_APPROVE(EXPENSE_APPROVAL, "Approve, reject or reverse expenses (posts them to the company account)"),
    ANALYTICS_VIEW(ANALYTICS, "View analytics dashboards"),

    // Platform
    // Ecommerce /api/admin/settings/email: the mail server and the address customer emails are sent from
    SYSTEM_SETTINGS_MANAGE(SYSTEM_ADMINISTRATION, "Manage global settings: email delivery, payment gateways and shipping rates"),
    AUDIT_LOG_VIEW(SYSTEM_ADMINISTRATION, "View audit logs"),
    // Ecommerce DELETE /api/admin/company/profile/drafts/{id}: anyone's draft, not only your own (COMPANY_PROFILE_EDIT)
    COMPANY_PROFILE_DRAFT_DELETE_ANY(SYSTEM_ADMINISTRATION, "Delete any admin's company profile draft"),
    // Web UI only (static content, no endpoint): the user guide page and its per-role PDF downloads
    USER_GUIDE_VIEW(USER_GUIDE, "View the user guide and download role guides as PDF"),

    // Every employee (/v1/payroll/my-payslips): their own payslips of the months whose salary was confirmed
    PAYSLIP_VIEW_OWN(SELF_SERVICE, "View and download your own payslips"),

    // Dealer portal (/v1/dealer-portal), held only by dealer logins and always limited to the dealer's own records
    DEALER_PORTAL_VIEW(DEALER_PORTAL, "View your own quotations, orders and bills"),
    DEALER_QUOTATION_SUBMIT(DEALER_PORTAL, "Create quotations"),
    DEALER_QUOTATION_CHANGE_REQUEST(DEALER_PORTAL, "Request changes to your quotations (a Dealer Manager approves them)"),
    DEALER_ORDER_RESPOND(DEALER_PORTAL, "Accept or reject orders awaiting your confirmation"),
    DEALER_ORDER_SHIP(DEALER_PORTAL, "Mark your confirmed orders as shipped");

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
