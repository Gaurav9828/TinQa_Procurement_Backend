package com.tinqa.procurement.payroll;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One employee's salary for one month, as transferred from the bank. Recorded by the payments team, confirmed by a
 * different Level 2 approver; the amounts never change after recording (a wrong one is rejected and recorded again).
 * The payslip is rendered from this row on demand and never stored.
 */
@Entity
@Table(name = "salary_payment")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalaryPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false, updatable = false)
    private Long employeeId;

    @Column(name = "pay_year", nullable = false, updatable = false)
    private Integer payYear;

    @Column(name = "pay_month", nullable = false, updatable = false)
    private Integer payMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SalaryPaymentStatus status;

    @Column(nullable = false, length = 10, updatable = false)
    private String currency;

    // The employee's monthly salary when this was recorded
    @Column(name = "monthly_salary", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal monthlySalary;

    // Monthly salary plus the extra earnings
    @Column(name = "total_earnings", nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal totalEarnings;

    @Column(name = "total_deductions", nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal totalDeductions;

    // What was transferred: total earnings less total deductions
    @Column(name = "net_pay", nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal netPay;

    @Column(nullable = false, length = 100, updatable = false)
    private String designation;

    @Column(nullable = false, length = 100, updatable = false)
    private String department;

    @Column(name = "transfer_date", nullable = false, updatable = false)
    private LocalDate transferDate;

    // UTR / transaction reference of the bank transfer
    @Column(name = "bank_reference", length = 100, updatable = false)
    private String bankReference;

    @Column(length = 500, updatable = false)
    private String note;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private Long recordedBy;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "payslip_email_to")
    private String payslipEmailTo;

    @Column(name = "payslip_emailed_at")
    private Instant payslipEmailedAt;

    @Column(name = "payslip_email_attempts", nullable = false)
    @Builder.Default
    private Integer payslipEmailAttempts = 0;

    @Column(name = "payslip_email_last_attempt_at")
    private Instant payslipEmailLastAttemptAt;

    @Column(name = "payslip_email_error", length = 500)
    private String payslipEmailError;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    @Builder.Default
    private List<SalaryPaymentLine> lines = new ArrayList<>();

    /** The posting key of this payment in the company account: never repeats, so it is posted once. */
    public String postingKey() {
        return "PROCUREMENT_SALARY:" + id;
    }
}
