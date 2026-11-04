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

    // Product costing: what a unit costs (components, manpower, software...) and the profit made on products and orders
    COSTING_READ("Costing - view product costs, margins and profit", false),
    COSTING_MANAGEMENT("Costing - enter product cost sheets", false),

    // Inventory
    INVENTORY_READ("Inventory - view stock", false),
    INVENTORY_MANAGEMENT("Inventory - receive and adjust stock", false),
    INVENTORY_APPROVAL("Inventory - approve stock entries", true),

    // Barcodes: every piece of stock and every product unit, traceable to its batch, purchase order, dealer and product
    BARCODE_READ("Barcodes - view and trace units", false),
    BARCODE_MANAGEMENT("Barcodes - request corrections", false),
    BARCODE_APPROVAL("Barcodes - correct barcodes and decide requests", true),

    // Storefront customer orders and delivery
    CUSTOMER_ORDER_READ("Customer orders - view", false),
    CUSTOMER_ORDER_MANAGEMENT("Customer orders - progress and override", false),
    DELIVERY_OPERATIONS("Delivery - update tracking and delivery status", false),
    LAST_MILE_DELIVERY("Delivery - record delivery attempts and outcomes", false),

    // Store / field sales: agents book orders for walk-in customers, paid in cash to the agent or online
    STORE_SALES_READ("Store sales - view every agent's sales", false),
    STORE_SALES("Store sales - register walk-in customers and book their orders", false),

    // Refunds
    REFUND_READ("Refunds - view", false),
    REFUND_MANAGEMENT("Refunds - request and sync", false),
    REFUND_APPROVAL("Refunds - approve or reject", true),

    // Customer care
    CUSTOMER_SUPPORT("Support tickets - view and respond", false),
    REVIEW_MODERATION("Product reviews - moderate", false),
    // Storefront customers: profile, purchase history and cart, so customer care can follow up with them
    CUSTOMER_READ("Customers - view profiles, order history and carts", false),
    CUSTOMER_MANAGEMENT("Customers - request changes to a customer's details", false),
    CUSTOMER_APPROVAL("Customers - edit customer details", true),
    // Storefront promo codes: senior staff see them so they can tell a customer which code to use. Creating and
    // changing them is the Super Admin's alone (Permission.PROMO_CODE_MANAGE, in SYSTEM_ADMINISTRATION)
    PROMO_CODE_READ("Promo codes - view codes and who used them", false),

    // Company profile (storefront branding and legal details)
    COMPANY_PROFILE_READ("Company profile - view", false),
    COMPANY_PROFILE_MANAGEMENT("Company profile - draft changes", false),
    COMPANY_PROFILE_APPROVAL("Company profile - approve changes", true),

    // Documents
    DOCUMENT_READ("Documents - view and download", false),
    DOCUMENT_MANAGEMENT("Documents - upload and delete", false),
    DOCUMENT_APPROVAL("Documents - approve or reject", true),

    // Money
    PAYMENT_READ("Payments - view salary payments and payslips", false),
    PAYMENT_MANAGEMENT("Payments - record salary transfers", false),
    PAYMENT_APPROVAL("Payments - confirm salary transfers", true),
    FINANCE_REPORTING("Finance - company account, reports and exports", false),
    EXPENSE_READ("Expenses - view", false),
    EXPENSE_MANAGEMENT("Expenses - raise for approval", false),
    EXPENSE_APPROVAL("Expenses - approve, reject and reverse", true),
    ANALYTICS("Analytics - dashboards", false),

    // Client demos & meetings: visits to clients for demos and promotions, with their stay, food and travel costs
    CLIENT_DEMO_READ("Client demos - view", false),
    CLIENT_DEMO_MANAGEMENT("Client demos - schedule, record bills and submit", false),
    CLIENT_DEMO_APPROVAL("Client demos - approve quotations, completions and corrections", true),
    // The staff who go on demos: they see the demos they are tagged on, record their own bills there and hand the
    // product units out and back. Only roles that do field work hold it, so nobody else can be tagged on a demo
    CLIENT_DEMO_ATTENDANCE("Client demos - attend demos: own bills and product units", false),

    // Client outreach: every call, email and WhatsApp message to a business we pitch to, so nobody calls twice blind
    CLIENT_OUTREACH_READ("Client outreach - view businesses and every contact logged with them", false),
    CLIENT_OUTREACH_MANAGEMENT("Client outreach - log calls, emails and WhatsApp messages with businesses", false),

    // Meetings: online or face-to-face, scheduled without approval; attendees answer through SELF_SERVICE
    MEETING_READ("Meetings - view every meeting and its feedback", false),
    MEETING_MANAGEMENT("Meetings - schedule, cancel and complete meetings", false),

    // Careers: job openings on the storefront's Careers tab, the people who apply, their interviews and the decisions
    RECRUITMENT_READ("Careers - view openings and applications", false),
    RECRUITMENT_MANAGEMENT("Careers - post openings, screen applicants and schedule interviews", false),
    RECRUITMENT_APPROVAL("Careers - confirm shortlists and selections", true),

    // Platform
    SYSTEM_ADMINISTRATION("System - settings, integrations and audit logs", true),
    // The in-app user guide: every role's flows, downloadable as a PDF to hand to a colleague
    USER_GUIDE("User guide - view and share role guides", false),

    // Every employee, whatever their designation: their own records (payslips, the meetings they are invited to)
    SELF_SERVICE("Self service - own payslips and meetings", false),

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
