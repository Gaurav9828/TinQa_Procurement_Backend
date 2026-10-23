package com.tinqa.procurement.payroll;

/** Where a month's salary payment stands. Only APPROVED payments are booked, emailed and downloadable. */
public enum SalaryPaymentStatus {
    // Recorded as transferred; a Level 2 approver other than the recorder has to confirm it
    PENDING_APPROVAL,
    APPROVED,
    // Not confirmed (wrong amount, not actually transferred …); the month can be recorded again
    REJECTED,
    // Taken back by the payments team before anyone decided on it
    WITHDRAWN
}
