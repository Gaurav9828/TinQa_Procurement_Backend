package com.tinqa.procurement.order.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SupplierRefundClaimRepository extends JpaRepository<SupplierRefundClaim, Long> {

    Optional<SupplierRefundClaim> findByOrderId(Long orderId);

    // One claim per order at most (unique order_id)
    List<SupplierRefundClaim> findByOrderIdIn(Collection<Long> orderIds);
}
