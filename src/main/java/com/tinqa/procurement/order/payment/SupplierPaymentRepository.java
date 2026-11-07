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

    List<SupplierPayment> findByOrderIdAndStatusInOrderByRecordedAtAscIdAsc(Long orderId, Collection<SupplierPaymentStatus> statuses);

    /** Per order, the payments waiting for the supplier to confirm they received them. */
    interface OrderCount {
        Long getOrderId();

        long getCount();
    }

    // One statement for a whole order list (idx_supplier_payment_awaiting_supplier)
    @Query("""
            SELECT p.orderId AS orderId, COUNT(p) AS count
            FROM SupplierPayment p
            WHERE p.orderId IN :orderIds AND p.status = com.tinqa.procurement.order.payment.SupplierPaymentStatus.AWAITING_SUPPLIER
            GROUP BY p.orderId
            """)
    List<OrderCount> countAwaitingSupplier(@Param("orderIds") Collection<Long> orderIds);

    // One statement for a whole order list (idx_supplier_payment_repayment_due)
    @Query("""
            SELECT p.orderId AS orderId, COUNT(p) AS count
            FROM SupplierPayment p
            WHERE p.orderId IN :orderIds AND p.status IN :statuses
            GROUP BY p.orderId
            """)
    List<OrderCount> countByOrderAndStatuses(@Param("orderIds") Collection<Long> orderIds,
                                             @Param("statuses") Collection<SupplierPaymentStatus> statuses);

    // The company account's feed of confirmed repayments, oldest first (idx_supplier_payment_receipts)
    @Query("""
            SELECT p FROM SupplierPayment p
            WHERE p.status = com.tinqa.procurement.order.payment.SupplierPaymentStatus.APPROVED
              AND p.kind IN (com.tinqa.procurement.order.payment.SupplierPaymentKind.REFUND, com.tinqa.procurement.order.payment.SupplierPaymentKind.REVERSAL)
              AND p.decidedAt > :since
            ORDER BY p.decidedAt ASC, p.id ASC
            """)
    List<SupplierPayment> findReceiptsSince(@Param("since") java.time.Instant since, org.springframework.data.domain.Pageable page);

    // The Approvals Hub queue (idx_supplier_payment_waiting). Small by design: entries leave it once decided
    List<SupplierPayment> findByStatusOrderByRecordedAtAscIdAsc(SupplierPaymentStatus status);

    // The Finance Manager's queue (idx_supplier_payment_to_make). Small by design: entries leave it once paid or sent again
    List<SupplierPayment> findByStatusInOrderByRecordedAtAscIdAsc(Collection<SupplierPaymentStatus> statuses);

    boolean existsByReversesIdAndStatusIn(Long reversesId, Collection<SupplierPaymentStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM SupplierPayment p WHERE p.id = :id")
    Optional<SupplierPayment> findByIdForUpdate(@Param("id") Long id);
}
