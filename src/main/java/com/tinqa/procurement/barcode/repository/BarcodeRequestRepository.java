package com.tinqa.procurement.barcode.repository;

import com.tinqa.procurement.barcode.entity.BarcodeRequest;
import com.tinqa.procurement.barcode.enums.BarcodeRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BarcodeRequestRepository extends JpaRepository<BarcodeRequest, Long> {

    @EntityGraph(attributePaths = {"barcode", "stock"})
    List<BarcodeRequest> findByStatusOrderByRequestedAtAscIdAsc(BarcodeRequestStatus status);

    @EntityGraph(attributePaths = {"barcode", "stock"})
    List<BarcodeRequest> findByBarcodeIdOrderByIdDesc(Long barcodeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM BarcodeRequest r WHERE r.id = :id")
    Optional<BarcodeRequest> findByIdForUpdate(@Param("id") Long id);
}
