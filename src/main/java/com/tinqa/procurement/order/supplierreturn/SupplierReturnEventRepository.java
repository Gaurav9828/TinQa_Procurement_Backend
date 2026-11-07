package com.tinqa.procurement.order.supplierreturn;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SupplierReturnEventRepository extends JpaRepository<SupplierReturnEvent, Long> {

    // An order's returns and its timeline (idx_supplier_return_event_order)
    List<SupplierReturnEvent> findByOrderIdOrderByAtAscIdAsc(Long orderId);

    // The returns of a queue (idx_supplier_return_event_return)
    List<SupplierReturnEvent> findByReturnIdInOrderByAtAscIdAsc(Collection<Long> returnIds);
}
