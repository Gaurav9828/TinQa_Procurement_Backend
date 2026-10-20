package com.tinqa.procurement.order.change;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderChangeRequestRepository extends JpaRepository<OrderChangeRequest, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM OrderChangeRequest r WHERE r.id = :id")
    Optional<OrderChangeRequest> findByIdForUpdate(@Param("id") Long id);

    boolean existsByOrderIdAndStatus(Long orderId, OrderChangeStatus status);

    Optional<OrderChangeRequest> findByOrderIdAndStatus(Long orderId, OrderChangeStatus status);

    List<OrderChangeRequest> findByStatusOrderByRequestedAtAsc(OrderChangeStatus status);

    List<OrderChangeRequest> findByOrderIdOrderByRequestedAtDesc(Long orderId);

    List<OrderChangeRequest> findByOrderIdInAndStatus(Collection<Long> orderIds, OrderChangeStatus status);
}
