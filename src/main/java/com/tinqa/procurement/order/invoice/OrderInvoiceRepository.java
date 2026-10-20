package com.tinqa.procurement.order.invoice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderInvoiceRepository extends JpaRepository<OrderInvoice, Long> {

    /** The invoice that counts for an order: the latest one uploaded. */
    Optional<OrderInvoice> findFirstByOrderIdOrderByIdDesc(Long orderId);
}
