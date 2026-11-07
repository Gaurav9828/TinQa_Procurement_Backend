package com.tinqa.procurement.order.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SupplierRefundClaimRepository extends JpaRepository<SupplierRefundClaim, Long> {

    Optional<SupplierRefundClaim> findByOrderId(Long orderId);
}
