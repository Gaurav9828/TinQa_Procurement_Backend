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
    // /v1/access/roles: custom roles (e.g. Marketing Executive) built from permission groups, next to the built-in
    // designations. An admin grants only groups they hold themselves
    ROLE_VIEW(EMPLOYEE_READ, "View built-in and custom roles"),
    ROLE_MANAGE(EMPLOYEE_MANAGEMENT, "Create and edit custom roles from the permission groups you hold"),

    // Suppliers (/v1/admin/suppliers)
    SUPPLIER_VIEW(SUPPLIER_READ, "View suppliers and supplier categories"),
    SUPPLIER_CREATE(SUPPLIER_MANAGEMENT, "Onboard suppliers"),
    SUPPLIER_UPDATE(SUPPLIER_MANAGEMENT, "Edit suppliers"),
    SUPPLIER_TOGGLE_STATUS(SUPPLIER_MANAGEMENT, "Activate or deactivate suppliers"),
    SUPPLIER_CATEGORY_MANAGE(SUPPLIER_MANAGEMENT, "Assign item categories to suppliers"),
    SUPPLIER_DELETE(SUPPLIER_APPROVAL, "Remove suppliers"),
    // Supplier logins (/v1/admin/suppliers/{id}/login, /v1/admin/supplier-logins): a Supplier Manager creates one, and it can
    // sign in once someone else approves it
    SUPPLIER_LOGIN_MANAGE(SUPPLIER_MANAGEMENT, "Create supplier logins, disable them and reset their passwords"),
    SUPPLIER_LOGIN_APPROVE(SUPPLIER_APPROVAL, "Approve or reject supplier logins"),

    // Procurement orders (/v1/orders)
    PROCUREMENT_ORDER_VIEW(PROCUREMENT_ORDER_READ, "View procurement orders"),
    PROCUREMENT_ORDER_CREATE(PROCUREMENT_ORDER_MANAGEMENT, "Create quotations / RFQs"),
    PROCUREMENT_ORDER_UPDATE(PROCUREMENT_ORDER_MANAGEMENT, "Edit quotations"),
    PROCUREMENT_ORDER_UPDATE_STATUS(PROCUREMENT_ORDER_MANAGEMENT, "Progress orders (submit, ship, deliver, returns, refunds)"),
    PROCUREMENT_ORDER_APPROVE(PROCUREMENT_ORDER_APPROVAL, "Approve orders, confirm with suppliers and decide returns"),
    // /v1/orders/change-requests: a supplier's edit to a quotation only applies once approved. The supplier is the maker,
    // so any Supplier Manager (either level) is the checker
    SUPPLIER_QUOTATION_CHANGE_APPROVE(PROCUREMENT_ORDER_MANAGEMENT, "Approve or reject suppliers' changes to their quotations"),
    // /v1/orders/{id}/payments: what was paid to the supplier and refunded by them, recorded by one person and confirmed
    // by another. Seen by everyone with PROCUREMENT_ORDER_VIEW
    SUPPLIER_PAYMENT_RECORD(SUPPLIER_PAYMENT_MANAGEMENT, "Record payments made to suppliers and refunds they paid back, and reverse them"),
    SUPPLIER_PAYMENT_APPROVE(SUPPLIER_PAYMENT_APPROVAL, "Confirm or reject recorded supplier payments and refunds, and change a refund claim"),

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

    // Barcodes (/v1/barcodes): one on every piece of item stock and every product unit, entered when stock is received
    // (STOCK_CREATE / STOCK_ADJUST) or a product unit is built (PRODUCT_CREATE / PRODUCT_UPDATE), and locked once that
    // is approved. Afterwards only a Super Admin changes them; anyone else asks
    BARCODE_VIEW(BARCODE_READ, "View barcodes and trace a unit to its batch, purchase order, supplier and product"),
    BARCODE_REQUEST(BARCODE_MANAGEMENT, "Ask for a barcode correction, or for barcodes on stock received without them"),
    BARCODE_APPROVE(BARCODE_APPROVAL, "Correct locked barcodes and decide barcode requests"),

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

    // Promo codes (Ecommerce /api/admin/promo-codes): a percentage off chosen products for all or chosen customers,
    // between two dates, limited in units and uses. Viewers only tell customers the code; the Super Admin makes them
    PROMO_CODE_VIEW(PROMO_CODE_READ, "View promo codes, their rules and every order that used them"),
    // A code for chosen customers is shown in full only to PROMO_CODE_MANAGE; sharers email it without seeing it
    PROMO_CODE_SHARE(PROMO_CODE_MANAGEMENT, "Email a promo code to its customers and show an everyone code on the storefront"),

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

    // Client demos & meetings (/v1/admin/client-demos): a visit to a client is planned as a cost estimate (the
    // quotation), approved by someone else, its bills recorded while it runs, and its completion approved too; only
    // then is the total booked to the company account. A completed demo changes only through a Super Admin
    // (DEMO_COMPLETED_EDIT), and the correction is approved again
    DEMO_VIEW(CLIENT_DEMO_READ, "View every client demo and meeting, its bills and invoices"),
    DEMO_MANAGE(CLIENT_DEMO_MANAGEMENT, "Schedule client demos, record their bills and send quotations and completions for approval"),
    DEMO_APPROVE(CLIENT_DEMO_APPROVAL, "Approve or reject demo quotations, completions and corrections (books the costs)"),

    // Client outreach (/v1/outreach): businesses we contact and each call, email or WhatsApp message with them, with its
    // response, rating, interest, follow-up date and optional proof screenshots. Executives edit only what they logged
    OUTREACH_VIEW(CLIENT_OUTREACH_READ, "View businesses, every contact logged with them and daily outreach counts"),
    OUTREACH_LOG(CLIENT_OUTREACH_MANAGEMENT, "Add businesses and log your calls, emails and WhatsApp messages with them"),

    // Meetings (/v1/meetings): scheduled straight away, no approval. The organiser cancels, invites replacements for
    // anyone who declined, and marks the meeting completed, which asks every attendee for feedback
    MEETING_VIEW_ALL(MEETING_READ, "View every meeting, its attendees and their feedback"),
    MEETING_SCHEDULE(MEETING_MANAGEMENT, "Schedule meetings, and cancel, edit and complete the ones you organise"),

    // Careers (Ecommerce /api/admin/careers): openings are posted straight to the storefront; an applicant's shortlist
    // and final selection are asked for by one admin and confirmed by another at Level 2, and the candidate is emailed
    CAREER_OPENING_VIEW(RECRUITMENT_READ, "View job openings and their applicant counts"),
    CAREER_APPLICATION_VIEW(RECRUITMENT_READ, "View applications, their résumés and interviews"),
    CAREER_OPENING_MANAGE(RECRUITMENT_MANAGEMENT, "Create, edit, publish and close job openings"),
    CAREER_APPLICATION_SCREEN(RECRUITMENT_MANAGEMENT, "Screen applicants: résumé match, shortlist, reject, schedule interviews and propose a selection"),
    CAREER_DECISION_APPROVE(RECRUITMENT_APPROVAL, "Confirm or return shortlists and selections (emails the candidate)"),

    // Platform
    // Ecommerce /api/admin/settings/email: the mail server and the address customer emails are sent from
    SYSTEM_SETTINGS_MANAGE(SYSTEM_ADMINISTRATION, "Manage global settings: email delivery, payment gateways and shipping rates"),
    AUDIT_LOG_VIEW(SYSTEM_ADMINISTRATION, "View audit logs"),
    // Ecommerce PUT /api/admin/careers/settings: hide the storefront's Careers tab and bring it back
    STOREFRONT_FEATURE_TOGGLE(SYSTEM_ADMINISTRATION, "Hide or show parts of the website, such as the Careers tab"),
    // Ecommerce POST/PUT/PATCH /api/admin/promo-codes and its code suggestion: create, edit and switch promo codes off
    PROMO_CODE_MANAGE(SYSTEM_ADMINISTRATION, "Create, edit and switch off promo codes"),
    // Ecommerce DELETE /api/admin/company/profile/drafts/{id}: anyone's draft, not only your own (COMPANY_PROFILE_EDIT)
    COMPANY_PROFILE_DRAFT_DELETE_ANY(SYSTEM_ADMINISTRATION, "Delete any admin's company profile draft"),
    // /v1/admin/client-demos/{id}/reopen and the edits of a reopened demo: correcting a completed (booked) demo
    DEMO_COMPLETED_EDIT(SYSTEM_ADMINISTRATION, "Reopen and correct completed client demos (the correction is approved again)"),
    // /v1/admin/client-demos/{id}/units/write-off: a product unit that did not come back from a demo leaves stock for good
    DEMO_UNIT_WRITE_OFF(SYSTEM_ADMINISTRATION, "Write off a product unit that did not come back from a client demo"),
    // Web UI only (static content, no endpoint): the user guide page and its per-role PDF downloads
    USER_GUIDE_VIEW(USER_GUIDE, "View the user guide and download role guides as PDF"),

    // Every employee (/v1/payroll/my-payslips): their own payslips of the months whose salary was confirmed
    PAYSLIP_VIEW_OWN(SELF_SERVICE, "View and download your own payslips"),
    // /v1/client-demos/mine: the demos the employee is tagged on as attending staff, and the bills they pay on them.
    // Only staff holding these can be tagged on a demo (ClientDemoService.staffOptions)
    DEMO_VIEW_OWN(CLIENT_DEMO_ATTENDANCE, "View the client demos you attend"),
    DEMO_EXPENSE_RECORD_OWN(CLIENT_DEMO_ATTENDANCE, "Record your hotel, food, travel and other bills on demos you attend"),
    // /v1/client-demos/mine/{id}/units: the product units taken, by barcode, out of stock on the day and back afterwards
    DEMO_UNITS_HANDLE_OWN(CLIENT_DEMO_ATTENDANCE, "Hand out and hand back the product units taken on demos you attend"),

    // /v1/meetings/mine: meetings the employee is invited to; they decline within 24 hours of the invite, and give
    // feedback once the meeting is completed
    MEETING_VIEW_OWN(SELF_SERVICE, "View the meetings you are invited to"),
    MEETING_RESPOND_OWN(SELF_SERVICE, "Decline a meeting invite (within 24 hours) and give feedback after a meeting"),

    // Supplier portal (/v1/supplier-portal), held only by supplier logins and always limited to the supplier's own records
    SUPPLIER_PORTAL_VIEW(SUPPLIER_PORTAL, "View your own quotations, orders and bills"),
    SUPPLIER_QUOTATION_SUBMIT(SUPPLIER_PORTAL, "Create quotations"),
    SUPPLIER_QUOTATION_CHANGE_REQUEST(SUPPLIER_PORTAL, "Request changes to your quotations (a Supplier Manager approves them)"),
    SUPPLIER_ORDER_RESPOND(SUPPLIER_PORTAL, "Accept or reject orders awaiting your confirmation"),
    SUPPLIER_ORDER_SHIP(SUPPLIER_PORTAL, "Mark your confirmed orders as shipped");

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
