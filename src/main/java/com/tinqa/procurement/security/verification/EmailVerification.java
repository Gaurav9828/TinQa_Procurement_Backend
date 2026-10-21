package com.tinqa.procurement.security.verification;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One verification code sent to an email address, kept only as a hash. A correct code yields a token (kept as its
 * SHA-256) that one account creation consumes.
 */
@Entity
@Table(name = "email_verification")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String email;

    @Column(name = "code_hash", nullable = false, length = 255)
    private String codeHash;

    @Column(name = "code_expires_at", nullable = false)
    private LocalDateTime codeExpiresAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "token_hash", length = 64, unique = true)
    private String tokenHash;

    @Column(name = "token_expires_at")
    private LocalDateTime tokenExpiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    // The administrator who asked for the code
    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
