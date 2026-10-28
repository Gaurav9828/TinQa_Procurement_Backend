package com.tinqa.procurement.order.repository;

import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.enums.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    // Row lock so stock recorded against the order cannot exceed its quantity under concurrent requests
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.orderNumber = :orderNumber")
    Optional<Order> findByOrderNumberForUpdate(@Param("orderNumber") String orderNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);
    List<Order> findByOrderStatus(OrderStatus orderStatus);

    List<Order> findByOrderStatusInOrderByIdAsc(Collection<OrderStatus> orderStatuses);
    List<Order> findByDealerId(Long dealerId);

    // Orders in this status with no stock entry yet (any approval status counts, as stock creation allows one per order)
    @Query("SELECT o FROM Order o WHERE o.orderStatus = :status"
            + " AND NOT EXISTS (SELECT 1 FROM Stock s WHERE s.order = o) ORDER BY o.createdAt DESC")
    List<Order> findByOrderStatusWithoutStock(@Param("status") OrderStatus status);

    // Every dealer record with this email counts as the same dealer (e.g. a dealer re-onboarded under a new record)
    @Query("SELECT o FROM Order o WHERE LOWER(o.dealer.email) = LOWER(:email)")
    List<Order> findByDealerEmail(@Param("email") String email);
}