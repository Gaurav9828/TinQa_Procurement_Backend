package com.tinqa.procurement.payroll;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/** One extra earning (bonus, overtime …) or deduction (unpaid leave, TDS, PF …) of a month's salary. */
@Entity
@Table(name = "salary_payment_line")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalaryPaymentLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "salary_payment_id", nullable = false, updatable = false)
    private SalaryPayment payment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private SalaryLineKind kind;

    @Column(nullable = false, length = 100, updatable = false)
    private String label;

    // Always positive; the kind says which way it goes
    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false)
    private Integer position;
}
