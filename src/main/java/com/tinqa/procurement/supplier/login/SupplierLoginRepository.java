package com.tinqa.procurement.supplier.login;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface SupplierLoginRepository extends JpaRepository<SupplierLogin, Long> {

    Optional<SupplierLogin> findBySupplierId(Long supplierId);

    Optional<SupplierLogin> findByUserId(Long userId);

    // Row lock, so two approvers (or an approver and a manager) can't act on the same login at once
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM SupplierLogin l WHERE l.supplierId = :supplierId")
    Optional<SupplierLogin> findBySupplierIdForUpdate(@Param("supplierId") Long supplierId);

    List<SupplierLogin> findByStatusOrderByCreatedAtAsc(SupplierLoginStatus status);
}
