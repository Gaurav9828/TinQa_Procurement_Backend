package com.tinqa.procurement.dealer.dto;

import java.util.List;

/**
 * Optional filters of the admin dealer listing; null or empty means "any".
 *
 * @param search      matched (case-insensitive, contains) against name, trade name, GSTIN, PAN, phone, email and city
 * @param categoryIds dealers in any of these categories
 * @param cities      any of these cities (case-insensitive)
 * @param isActive    active or inactive dealers only
 */
public record DealerFilter(String search, List<Long> categoryIds, List<String> cities, Boolean isActive) {
}
