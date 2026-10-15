package com.tinqa.procurement.security.access;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The job an internal employee does. It decides which {@link PermissionGroup}s they hold (see {@link DesignationAccess});
 * the account's admin level (ADMIN_L1 / ADMIN_L2) then decides whether they may also approve within those groups.
 * External partners (registered dealers) are not employees and have no designation: their access comes from Role.DEALER.
 *
 * <p>Each designation also fixes the employee's department, so the two can never disagree: the department is always
 * derived from the designation, never chosen.
 */
public enum Designation {

    SUPER_ADMIN("Super Admin",
            "Administration",
            "Ultimate system owner: global configuration, payment gateways, shipping rates and every user account."),
    STORE_MANAGER("Store Manager",
            "Store Operations",
            "Business and operations lead: sales analytics, banners, coupons, pricing, order and delivery overrides."),
    DEALER_MANAGER("Dealer Manager",
            "Procurement",
            "Procurement / B2B lead: dealer applications, RFQs, dealer quotes, negotiation and final dealer confirmations."),
    CATALOG_SPECIALIST("Catalog Specialist",
            "Catalog & Content",
            "Storefront content: product listings, descriptions, images, variants and SEO keywords."),
    FULFILLMENT_SPECIALIST("Fulfillment & Warehouse Specialist",
            "Warehouse & Fulfillment",
            "Receives and packs orders, prints packaging slips, deducts inventory and hands packages to riders."),
    DELIVERY_RIDER("Delivery Rider / Courier",
            "Logistics & Delivery",
            "Last-mile delivery: marks orders out for delivery or delivered, records failed attempts and re-attempts."),
    FLEET_COORDINATOR("Fleet & Logistics Coordinator",
            "Logistics & Delivery",
            "Delivery supervisor: groups orders by route, assigns batches to riders, tracks riders and vehicles."),
    CUSTOMER_SUPPORT("Customer Support Representative",
            "Customer Support",
            "Help desk: searches order history, resolves complaints, updates delivery details, initiates returns."),
    FINANCIAL_AUDITOR("Financial Auditor",
            "Finance & Accounts",
            "Bookkeeping and tax review: sales, tax and revenue reports, COD collections and dealer payouts.");

    private final String label;
    private final String department;
    private final String description;

    Designation(String label, String department, String description) {
        this.label = label;
        this.department = department;
        this.description = description;
    }

    public String label() {
        return label;
    }

    /** The department every employee with this designation belongs to. */
    public String department() {
        return department;
    }

    public String description() {
        return description;
    }

    /** The designation with this code, ignoring case and surrounding spaces; empty for anything else. */
    public static Optional<Designation> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(designation -> designation.name().equals(normalized)).findFirst();
    }

    /** Every code, for validation messages. */
    public static String codes() {
        return String.join(", ", Arrays.stream(values()).map(Enum::name).toList());
    }
}
