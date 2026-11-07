package com.tinqa.procurement.order.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SupplierPaymentRepository extends JpaRepository<SupplierPayment, Long> {

    /** The sum of one kind (and milestone) of entry in one status, for one order. */
    interface Total {
        SupplierPaymentKind getKind();

        PaymentMilestone getMilestone();

        SupplierPaymentStatus getStatus();

        BigDecimal getTotal();
    }

    // One statement per order, summed in SQL (idx_supplier_payment_order); rejected and withdrawn entries never count
    @Query("""
            SELECT p.kind AS kind, p.milestone AS milestone, p.status AS status, SUM(p.amount) AS total
            FROM SupplierPayment p
            WHERE p.orderId = :orderId AND p.status IN :statuses
            GROUP BY p.kind, p.milestone, p.status
            """)
    List<Total> totalsForOrder(@Param("orderId") Long orderId, @Param("statuses") Collection<SupplierPaymentStatus> statuses);

    List<SupplierPayment> findByOrderIdOrderByRecordedAtAscIdAsc(Long orderId);

    List<SupplierPayment> findByOrderIdAndStatusOrderByRecordedAtAscIdAsc(Long orderId, SupplierPaymentStatus status);

    // The Approvals Hub queue (idx_supplier_payment_waiting). Small by design: entries leave it once decided
    List<SupplierPayment> findByStatusOrderByRecordedAtAscIdAsc(SupplierPaymentStatus status);

    boolean existsByReversesIdAndStatusIn(Long reversesId, Collection<SupplierPaymentStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM SupplierPayment p WHERE p.id = :id")
    Optional<SupplierPayment> findByIdForUpdate(@Param("id") Long id);
}
