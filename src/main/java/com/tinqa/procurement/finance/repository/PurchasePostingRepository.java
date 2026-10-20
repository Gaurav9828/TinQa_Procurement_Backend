package com.tinqa.procurement.finance.repository;

import com.tinqa.procurement.finance.entity.PurchasePosting;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Repository
public interface PurchasePostingRepository extends JpaRepository<PurchasePosting, Long> {

    // What is booked for an order now (read under the order lock)
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM PurchasePosting p WHERE p.order.id = :orderId")
    BigDecimal sumBookedForOrder(@Param("orderId") Long orderId);

    // GST booked for an order now, per head: cgst, sgst, igst, cess (read under the order lock)
    @Query("SELECT COALESCE(SUM(p.cgst), 0) AS cgst, COALESCE(SUM(p.sgst), 0) AS sgst, COALESCE(SUM(p.igst), 0) AS igst, "
            + "COALESCE(SUM(p.cess), 0) AS cess FROM PurchasePosting p WHERE p.order.id = :orderId")
    BookedGst sumGstBookedForOrder(@Param("orderId") Long orderId);

    interface BookedGst {
        BigDecimal getCgst();

        BigDecimal getSgst();

        BigDecimal getIgst();

        BigDecimal getCess();
    }

    // Finance feed, oldest first
    @Query("SELECT p FROM PurchasePosting p JOIN FETCH p.order o LEFT JOIN FETCH o.dealer LEFT JOIN FETCH o.item "
            + "LEFT JOIN FETCH p.stock WHERE p.createdAt >= :since ORDER BY p.createdAt ASC, p.id ASC")
    List<PurchasePosting> findSince(@Param("since") Instant since, Pageable pageable);
}
