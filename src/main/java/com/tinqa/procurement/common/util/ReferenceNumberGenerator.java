package com.tinqa.procurement.common.util;

import com.tinqa.procurement.common.constant.AppConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * Generates the human-readable business identifiers: a type prefix, the creation time and a random suffix.
 */
@Component
@RequiredArgsConstructor
public class ReferenceNumberGenerator {

    private final Clock clock;

    // ORD-1728375000000-AB12
    public String orderNumber() {
        return timestamped(AppConstants.ORDER_NUMBER_PREFIX, 4);
    }

    // STK-1728375000000-AB12C
    public String stockIdentityNumber() {
        return timestamped(AppConstants.STOCK_IDENTITY_PREFIX, 5);
    }

    // PRD-1728375000000-AB12
    public String productId() {
        return timestamped(AppConstants.PRODUCT_ID_PREFIX, 4);
    }

    // BAT-20261015-42-AB12C (date, order id, random suffix)
    public String batchNumber(Long orderId) {
        String date = LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE);
        return String.format("%s-%s-%d-%s", AppConstants.BATCH_NUMBER_PREFIX, date, orderId, randomSuffix(5));
    }

    // TQ-20261028- : today's SKU prefix; the item's running number for the day follows it
    public String skuPrefix() {
        return AppConstants.SKU_PREFIX + "-" + LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
    }

    private String timestamped(String prefix, int suffixLength) {
        return prefix + "-" + clock.millis() + "-" + randomSuffix(suffixLength);
    }

    private static String randomSuffix(int length) {
        return UUID.randomUUID().toString().substring(0, length).toUpperCase(Locale.ROOT);
    }
}
