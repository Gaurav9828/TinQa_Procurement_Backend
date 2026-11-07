package com.tinqa.procurement.clientdemo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface DemoUnitRepository extends JpaRepository<DemoUnit, Long> {

    /** Which other demo holds a unit: its barcode id and the demo's id, start date and length. */
    interface Holder {
        Long getBarcodeId();

        Long getDemoId();

        LocalDate getStartDate();

        Integer getNumberOfDays();
    }

    // Units of other demos that hold the barcodes: out on a demo now, or reserved by a demo still planned or running
    // whose days overlap [start, end]. Uses idx_client_demo_unit_barcode_active (V12__client_demos.sql)
    @Query(value = """
            SELECT u.barcode_id AS barcodeId, d.id AS demoId, d.start_date AS startDate, d.number_of_days AS numberOfDays
            FROM client_demo_unit u JOIN client_demo d ON d.id = u.demo_id
            WHERE u.barcode_id IN (:barcodeIds) AND u.status IN ('RESERVED', 'OUT') AND u.demo_id <> :demoId
              AND (u.status = 'OUT' OR (d.status IN ('DRAFT', 'QUOTATION_PENDING', 'APPROVED')
                   AND d.start_date <= :end AND d.start_date + (d.number_of_days - 1) >= :start))
            ORDER BY u.barcode_id, d.id""", nativeQuery = true)
    List<Holder> findHolders(@Param("barcodeIds") Collection<Long> barcodeIds, @Param("demoId") long demoId,
                             @Param("start") LocalDate start, @Param("end") LocalDate end);
}
