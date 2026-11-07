package com.tinqa.procurement.order.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DealerRefundClaimRepository extends JpaRepository<DealerRefundClaim, Long> {

    Optional<DealerRefundClaim> findByOrderId(Long orderId);
}
