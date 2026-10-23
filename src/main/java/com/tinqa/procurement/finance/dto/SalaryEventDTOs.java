package com.tinqa.procurement.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Confirmed salary payments, read by the Ecommerce service's company account. */
public final class SalaryEventDTOs {

    private SalaryEventDTOs() {
    }

    /**
     * One employee's salary for one month, confirmed by a Level 2 approver: {@code netPay} left the bank on
     * {@code transferDate} (money out). Salaries carry no GST. {@code postingKey} never repeats, so the reader posts each
     * event once; {@code changedAt} is when it was confirmed.
     */
    public record SalaryEvent(
            Long eventId,
            String postingKey,
            String employeeCode,
            String employeeName,
            int payYear,
            int payMonth,
            BigDecimal grossPay,
            BigDecimal deductions,
            BigDecimal netPay,
            String bankReference,
            LocalDate transferDate,
            Instant changedAt) {
    }

    public record SalaryEventPage(List<SalaryEvent> events, boolean hasMore) {
    }
}
