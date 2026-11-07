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

public interface DealerPaymentRepository extends JpaRepository<DealerPayment, Long> {

    /** The sum of one kind (and milestone) of entry in one status, for one order. */
    interface Total {
        DealerPaymentKind getKind();

        PaymentMilestone getMilestone();

        DealerPaymentStatus getStatus();

        BigDecimal getTotal();
    }

    // One statement per order, summed in SQL (idx_dealer_payment_order); rejected and withdrawn entries never count
    @Query("""
            SELECT p.kind AS kind, p.milestone AS milestone, p.status AS status, SUM(p.amount) AS total
            FROM DealerPayment p
            WHERE p.orderId = :orderId AND p.status IN :statuses
            GROUP BY p.kind, p.milestone, p.status
            """)
    List<Total> totalsForOrder(@Param("orderId") Long orderId, @Param("statuses") Collection<DealerPaymentStatus> statuses);

    List<DealerPayment> findByOrderIdOrderByRecordedAtAscIdAsc(Long orderId);

    List<DealerPayment> findByOrderIdAndStatusOrderByRecordedAtAscIdAsc(Long orderId, DealerPaymentStatus status);

    // The Approvals Hub queue (idx_dealer_payment_waiting). Small by design: entries leave it once decided
    List<DealerPayment> findByStatusOrderByRecordedAtAscIdAsc(DealerPaymentStatus status);

    boolean existsByReversesIdAndStatusIn(Long reversesId, Collection<DealerPaymentStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM DealerPayment p WHERE p.id = :id")
    Optional<DealerPayment> findByIdForUpdate(@Param("id") Long id);
}
