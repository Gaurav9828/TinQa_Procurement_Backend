package com.tinqa.procurement.dealer.login;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface DealerLoginRepository extends JpaRepository<DealerLogin, Long> {

    Optional<DealerLogin> findByDealerId(Long dealerId);

    Optional<DealerLogin> findByUserId(Long userId);

    // Row lock, so two approvers (or an approver and a manager) can't act on the same login at once
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM DealerLogin l WHERE l.dealerId = :dealerId")
    Optional<DealerLogin> findByDealerIdForUpdate(@Param("dealerId") Long dealerId);

    List<DealerLogin> findByStatusOrderByCreatedAtAsc(DealerLoginStatus status);
}
