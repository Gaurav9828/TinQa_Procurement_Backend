package com.tinqa.procurement.payroll;

import com.tinqa.procurement.common.validation.MaxAmount;
import com.tinqa.procurement.common.validation.ValidationPatterns;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PayrollDTOs {

    /** The most employees one request records or decides. */
    public static final int MAX_BATCH = 500;
    /** The most extra lines on one month's payslip. */
    public static final int MAX_LINES = 20;

    private PayrollDTOs() {
    }

    // ------------------------------------------------------------------ requests

    /**
     * Records that salaries of a month were transferred: one entry per employee (one for a single employee, every
     * unpaid employee for "mark all as paid"). Date, reference and note apply to every entry unless an entry gives
     * its own reference.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecordRequest {
        @NotNull(message = "Transfer date is required")
        private LocalDate transferDate;

        @Size(max = 100, message = "Bank reference cannot exceed 100 characters")
        @Pattern(regexp = "^\\s*$|" + ValidationPatterns.SINGLE_LINE_TEXT, message = "Bank reference " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
        private String bankReference;

        @Size(max = 500, message = "Note cannot exceed 500 characters")
        private String note;

        @NotEmpty(message = "Choose at least one employee")
        @Size(max = MAX_BATCH, message = "At most " + MAX_BATCH + " employees can be recorded at once")
        @Valid
        private List<Entry> entries;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Entry {
        @NotNull(message = "Employee is required")
        private Long employeeId;

        // This employee's own transfer reference; the request's reference when blank
        @Size(max = 100, message = "Bank reference cannot exceed 100 characters")
        @Pattern(regexp = "^\\s*$|" + ValidationPatterns.SINGLE_LINE_TEXT, message = "Bank reference " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
        private String bankReference;

        @Size(max = MAX_LINES, message = "At most " + MAX_LINES + " extra earnings and deductions per employee")
        @Valid
        private List<Line> lines;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        @NotNull(message = "Choose earning or deduction")
        private SalaryLineKind kind;

        @NotBlank(message = "Describe the earning or deduction")
        @Size(max = 100, message = "The description cannot exceed 100 characters")
        @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "The description " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
        private String label;

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be more than zero")
        @MaxAmount
        private BigDecimal amount;
    }

    public enum Decision {
        APPROVED,
        REJECTED
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DecisionRequest {
        @NotEmpty(message = "Choose at least one payment")
        @Size(max = MAX_BATCH, message = "At most " + MAX_BATCH + " payments can be decided at once")
        private List<@NotNull Long> paymentIds;

        @NotNull(message = "Decision is required")
        private Decision decision;

        // Required to reject (checked in the service)
        @Size(max = 500, message = "Rejection reason cannot exceed 500 characters")
        private String rejectionReason;
    }

    // ------------------------------------------------------------------ responses

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineResponse {
        private SalaryLineKind kind;
        private String label;
        private BigDecimal amount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentResponse {
        private Long id;
        private Long employeeId;
        private int payYear;
        private int payMonth;
        private SalaryPaymentStatus status;
        private String currency;
        private BigDecimal monthlySalary;
        private BigDecimal totalEarnings;
        private BigDecimal totalDeductions;
        private BigDecimal netPay;
        private List<LineResponse> lines;
        private LocalDate transferDate;
        private String bankReference;
        private String note;
        private Long recordedBy;
        private String recordedByName;
        private Instant recordedAt;
        private String decidedByName;
        private Instant decidedAt;
        private String rejectionReason;
        /** Where the payslip went (or will go); null until approved */
        private String payslipEmailTo;
        private Instant payslipEmailedAt;
        /** Why the last try failed; the job keeps trying a few times, then "Resend" starts over */
        private String payslipEmailError;
        private int payslipEmailAttempts;
    }

    /** One employee in a month: whether they are paid that month and their latest payment for it. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthRow {
        private Long employeeId;
        /** The employee's sign-in account: nobody confirms their own salary */
        private Long userId;
        private String employeeCode;
        private String name;
        private String designation;
        private String department;
        private BigDecimal monthlySalary;
        private String currency;
        /** False when no salary can be recorded for them this month; the reason says why */
        private boolean payable;
        private String notPayableReason;
        /** Pending or approved payment, else the latest rejected / withdrawn one, else null */
        private PaymentResponse payment;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthView {
        private int year;
        private int month;
        /** False for months that haven't started yet */
        private boolean open;
        private int payableEmployees;
        private int notRecorded;
        private int pendingApproval;
        private int approved;
        private BigDecimal pendingNetPay;
        private BigDecimal approvedNetPay;
        private List<MonthRow> rows;
    }

    public enum MonthState {
        /** Not started yet: nothing can be recorded */
        UPCOMING,
        /** Nobody to pay that month */
        NO_EMPLOYEES,
        NOT_STARTED,
        /** Some salaries recorded or approved, others not recorded yet */
        IN_PROGRESS,
        /** Everyone is recorded; some still wait for approval */
        AWAITING_APPROVAL,
        /** Every payable employee's salary is approved */
        PAID
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthSummary {
        private int month;
        private MonthState state;
        private int payableEmployees;
        private int pendingApproval;
        private int approved;
        private BigDecimal approvedNetPay;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class YearSummary {
        private int year;
        private List<MonthSummary> months;
        private BigDecimal approvedNetPay;
    }

    /** What happened to each payment of a batch decision. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DecisionResult {
        private int decided;
        private List<PaymentResponse> payments;
    }

    /** One of the signed-in employee's own payslips. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MyPayslip {
        private Long id;
        private int payYear;
        private int payMonth;
        private String currency;
        private BigDecimal totalEarnings;
        private BigDecimal totalDeductions;
        private BigDecimal netPay;
        private LocalDate transferDate;
        private Instant approvedAt;
    }
}
