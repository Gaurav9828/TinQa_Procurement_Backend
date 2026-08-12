package com.tinqa.procurement.approval.repository;

import com.tinqa.procurement.approval.entity.EmployeeProfileChangeRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EmployeeProfileChangeRequestRepository
        extends JpaRepository<EmployeeProfileChangeRequest, Long> {

    Optional<EmployeeProfileChangeRequest>
    findFirstByEmployeeIdAndStatusOrderByRequestedAtDesc(
            Long employeeId,
            String status
    );

    List<EmployeeProfileChangeRequest>
    findByEmployeeIdOrderByRequestedAtDesc(
            Long employeeId
    );

    boolean existsByEmployeeIdAndStatus(
            Long employeeId,
            String status
    );

    List<EmployeeProfileChangeRequest>
    findByStatusOrderByRequestedAtDesc(
            String status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r
            FROM EmployeeProfileChangeRequest r
            WHERE r.id = :requestId
            """)
    Optional<EmployeeProfileChangeRequest> findByIdForUpdate(
            @Param("requestId") Long requestId
    );
}