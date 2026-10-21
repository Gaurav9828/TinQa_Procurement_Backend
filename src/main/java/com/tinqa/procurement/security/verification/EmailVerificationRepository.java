package com.tinqa.procurement.security.verification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    /** The latest code sent to this address. */
    Optional<EmailVerification> findFirstByEmailOrderByCreatedAtDescIdDesc(String email);

    long countByEmailAndCreatedAtAfter(String email, LocalDateTime since);

    Optional<EmailVerification> findByTokenHash(String tokenHash);
}
