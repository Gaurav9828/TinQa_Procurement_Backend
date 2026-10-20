package com.tinqa.procurement.item.dto;

import java.util.List;

/**
 * Optional filters of the admin item listing; null or empty means "any".
 *
 * @param search      matched (case-insensitive, contains) against name, SKU and brand
 * @param categoryIds any of these categories
 * @param units       any of these units of measure (case-insensitive)
 * @param isActive    active or inactive items only
 */
public record ItemFilter(String search, List<Long> categoryIds, List<String> units, Boolean isActive) {
}
