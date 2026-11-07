package com.tinqa.procurement.supplier.dto;

import java.util.List;

/**
 * Optional filters of the admin supplier listing; null or empty means "any".
 *
 * @param search      matched (case-insensitive, contains) against name, trade name, GSTIN, PAN, phone, email and city
 * @param categoryIds suppliers in any of these categories
 * @param cities      any of these cities (case-insensitive)
 * @param isActive    active or inactive suppliers only
 */
public record SupplierFilter(String search, List<Long> categoryIds, List<String> cities, Boolean isActive) {
}
