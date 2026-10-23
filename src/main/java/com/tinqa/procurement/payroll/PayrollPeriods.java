package com.tinqa.procurement.payroll;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** How pay months, dates and amounts are told apart and written on payslips, emails and notifications. */
final class PayrollPeriods {

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private PayrollPeriods() {
    }

    /** The month it is now in India: salaries of later months can't be recorded yet. */
    static YearMonth currentMonth(Clock clock) {
        return YearMonth.now(clock.withZone(BUSINESS_ZONE));
    }

    /** "October 2026" */
    static String label(YearMonth period) {
        return MONTH.format(period);
    }

    /** "22 Oct 2026" */
    static String date(LocalDate date) {
        return date == null ? "-" : DATE.format(date);
    }

    /** "Rs. 45,000.00": the standard PDF fonts have no rupee sign, so the same text is used everywhere. */
    static String rupees(BigDecimal amount) {
        return amount == null ? "-" : "Rs. " + String.format(Locale.ENGLISH, "%,.2f", amount);
    }
}
