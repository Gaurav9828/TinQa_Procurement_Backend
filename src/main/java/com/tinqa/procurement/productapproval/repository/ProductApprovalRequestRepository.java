package com.tinqa.procurement.productapproval.repository;

import com.tinqa.procurement.productapproval.entity.ProductApprovalRequest;
import com.tinqa.procurement.productapproval.enums.ProductApprovalStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductApprovalRequestRepository extends JpaRepository<ProductApprovalRequest, Long> {

    /**
     * Serialises submissions for one product for the rest of the transaction (there is no parent row to lock).
     */
    @Query(value = "SELECT CAST(pg_advisory_xact_lock(hashtextextended('product-approval:' || :productId, 0)) AS text)",
            nativeQuery = true)
    String lockProduct(@Param("productId") Long productId);

    Optional<ProductApprovalRequest> findFirstByEcommerceProductIdOrderByRequestedAtDescIdDesc(Long ecommerceProductId);

    List<ProductApprovalRequest> findByEcommerceProductIdOrderByRequestedAtDescIdDesc(Long ecommerceProductId);

    List<ProductApprovalRequest> findByStatusOrderByRequestedAtAscIdAsc(ProductApprovalStatus status);

    boolean existsByEcommerceProductIdAndStatus(Long ecommerceProductId, ProductApprovalStatus status);

    // Newest request of each listed product; products without requests are absent
    @Query(value = """
            SELECT DISTINCT ON (ecommerce_product_id) *
            FROM product_approval_requests
            WHERE ecommerce_product_id IN (:productIds)
            ORDER BY ecommerce_product_id, requested_at DESC, id DESC
            """, nativeQuery = true)
    List<ProductApprovalRequest> findLatestForProducts(@Param("productIds") Collection<Long> productIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ProductApprovalRequest r WHERE r.id = :id")
    Optional<ProductApprovalRequest> findByIdForUpdate(@Param("id") Long id);
}
