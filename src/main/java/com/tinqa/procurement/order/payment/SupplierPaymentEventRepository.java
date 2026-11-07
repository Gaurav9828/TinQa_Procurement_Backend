package com.tinqa.procurement.order.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SupplierPaymentEventRepository extends JpaRepository<SupplierPaymentEvent, Long> {

    // An order's payments and its timeline (idx_supplier_payment_event_order)
    List<SupplierPaymentEvent> findByOrderIdOrderByAtAscIdAsc(Long orderId);

    // The entries of a queue (idx_supplier_payment_event_payment)
    List<SupplierPaymentEvent> findByPaymentIdInOrderByAtAscIdAsc(Collection<Long> paymentIds);

    boolean existsByPaymentIdAndProofDocumentId(Long paymentId, Long proofDocumentId);
}
