package com.tinqa.procurement.barcode.repository;

import com.tinqa.procurement.barcode.entity.BarcodeEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BarcodeEventRepository extends JpaRepository<BarcodeEvent, Long> {

    List<BarcodeEvent> findByBarcodeIdOrderByIdAsc(Long barcodeId);
}
