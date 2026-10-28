package com.tinqa.procurement.clientdemo;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * An amount booked to the company account for a demo: positive is money spent, negative money a correction took back.
 * Append-only; the e-commerce service posts each row once by its posting key.
 */
@Entity
@Table(name = "client_demo_posting")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DemoPosting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "demo_id", nullable = false, updatable = false)
    private Long demoId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private DemoPostingKind kind;

    @Column(nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "occurred_on", nullable = false, updatable = false)
    private LocalDate occurredOn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public String postingKey() {
        return "PROCUREMENT_DEMO:" + id;
    }
}
