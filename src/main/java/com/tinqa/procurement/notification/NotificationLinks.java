package com.tinqa.procurement.notification;

/**
 * Admin UI routes a notification opens. Keep in sync with the routes in the Procurement Web UI (src/App.tsx).
 */
public final class NotificationLinks {

    /** Approval queues: anything waiting for the recipient's decision */
    public static final String APPROVALS = "/approvals";
    public static final String ORDERS = "/orders";
    public static final String PRODUCTS = "/products";
    public static final String STOCKS = "/item/stocks";
    public static final String EMPLOYEES = "/employees";
    public static final String DEALERS = "/dealers";
    /** Customer Management; a customer's own page is CUSTOMERS + "/" + their Ecommerce customer id */
    public static final String CUSTOMERS = "/customers";
    /** Dealer logins: their own quotations, and their orders once confirmed */
    public static final String DEALER_QUOTATIONS = "/dealer/quotations";
    public static final String DEALER_ORDERS = "/dealer/orders";
    /** The recipient's own profile: documents and profile change requests */
    public static final String PROFILE = "/profile";
    /** Salary payments by month (Payments & Chalans), and an employee's own payslips */
    public static final String PAYROLL = "/payments";
    public static final String MY_PAYSLIPS = "/my-payslips";

    private NotificationLinks() {
    }
}
