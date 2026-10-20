package com.tinqa.procurement.dealer.login;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** The link between a dealer and the user account they sign in with, and that account's approval state. */
@Entity
@Table(name = "dealer_login")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DealerLogin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dealer_id", nullable = false, unique = true, updatable = false)
    private Long dealerId;

    @Column(name = "user_id", nullable = false, unique = true, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DealerLoginStatus status;

    // Who last submitted the login for approval
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
