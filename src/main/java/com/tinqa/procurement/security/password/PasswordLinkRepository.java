package com.tinqa.procurement.security.password;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PasswordLinkRepository extends JpaRepository<PasswordLink, Long> {

    Optional<PasswordLink> findByTokenHashAndUsedAtIsNull(String tokenHash);

    boolean existsByUserIdAndPurposeAndCreatedAtAfter(Long userId, PasswordLinkPurpose purpose, LocalDateTime since);

    /** Retires every link of the user still open (a new link, or a password just set, replaces them). */
    @Modifying(flushAutomatically = true)
    @Query("update PasswordLink link set link.usedAt = :now where link.userId = :userId and link.usedAt is null")
    int retireOpenLinks(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
