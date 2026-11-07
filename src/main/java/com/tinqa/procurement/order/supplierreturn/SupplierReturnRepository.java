package com.tinqa.procurement.order.supplierreturn;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SupplierReturnRepository extends JpaRepository<SupplierReturn, Long> {

    // An order's returns, newest first (idx_supplier_return_order). Small by design: a few per order at most
    List<SupplierReturn> findByOrderIdOrderByRequestedAtDescIdDesc(Long orderId);

    // The Super Admin's queue, oldest first (idx_supplier_return_waiting). Small by design: returns leave it once decided
    List<SupplierReturn> findByStatusOrderByRequestedAtAscIdAsc(SupplierReturnStatus status);

    // The units the order's returns hold (every status but rejected and cancelled), summed in SQL
    @Query("""
            SELECT COALESCE(SUM(r.units), 0) FROM SupplierReturn r
            WHERE r.orderId = :orderId AND r.status NOT IN (com.tinqa.procurement.order.supplierreturn.SupplierReturnStatus.REJECTED,
                com.tinqa.procurement.order.supplierreturn.SupplierReturnStatus.CANCELLED)
            """)
    BigDecimal unitsHeld(@Param("orderId") Long orderId);

    interface OrderCount {
        Long getOrderId();

        long getCount();
    }

    // My Orders: per order, the returns waiting for the supplier (idx_supplier_return_to_answer), one statement for the list
    @Query("""
            SELECT r.orderId AS orderId, COUNT(r) AS count FROM SupplierReturn r
            WHERE r.orderId IN :orderIds AND r.status IN (com.tinqa.procurement.order.supplierreturn.SupplierReturnStatus.AWAITING_SUPPLIER,
                com.tinqa.procurement.order.supplierreturn.SupplierReturnStatus.IN_TRANSIT)
            GROUP BY r.orderId
            """)
    List<OrderCount> countToAnswer(@Param("orderIds") Collection<Long> orderIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM SupplierReturn r WHERE r.id = :id")
    Optional<SupplierReturn> findByIdForUpdate(@Param("id") Long id);
}
