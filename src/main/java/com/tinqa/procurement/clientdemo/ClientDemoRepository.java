package com.tinqa.procurement.clientdemo;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ClientDemoRepository extends JpaRepository<ClientDemo, Long> {

    // Demos starting within [from, to] in the given statuses, newest visit first
    @Query("SELECT d FROM ClientDemo d WHERE d.startDate >= :from AND d.startDate <= :to AND d.status IN :statuses "
            + "ORDER BY d.startDate DESC, d.id DESC")
    List<ClientDemo> search(@Param("from") LocalDate from, @Param("to") LocalDate to,
                            @Param("statuses") Collection<DemoStatus> statuses, Pageable pageable);

    @Query("SELECT d FROM ClientDemo d WHERE d.status IN :statuses ORDER BY d.submittedAt ASC, d.id ASC")
    List<ClientDemo> findInStatus(@Param("statuses") Collection<DemoStatus> statuses);

    // The demos an employee attends, newest visit first
    @Query("SELECT d FROM ClientDemo d WHERE :employeeId MEMBER OF d.staffEmployeeIds AND d.status <> :hidden "
            + "ORDER BY d.startDate DESC, d.id DESC")
    List<ClientDemo> findAttendedBy(@Param("employeeId") Long employeeId, @Param("hidden") DemoStatus hidden);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM ClientDemo d WHERE d.id = :id")
    Optional<ClientDemo> findByIdForUpdate(@Param("id") Long id);
}
