package com.tinqa.procurement.payroll;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SalaryPaymentRepository extends JpaRepository<SalaryPayment, Long> {

    // Every payment of a month, oldest first (the month view keeps each employee's latest)
    @Query("SELECT DISTINCT p FROM SalaryPayment p LEFT JOIN FETCH p.lines "
            + "WHERE p.payYear = :year AND p.payMonth = :month ORDER BY p.id ASC")
    List<SalaryPayment> findMonth(@Param("year") int year, @Param("month") int month);

    // A year's payments in the given statuses (lines not loaded)
    @Query("SELECT p FROM SalaryPayment p WHERE p.payYear = :year AND p.status IN :statuses")
    List<SalaryPayment> findYearInStatus(@Param("year") int year, @Param("statuses") Collection<SalaryPaymentStatus> statuses);

    @Query("SELECT COUNT(p) > 0 FROM SalaryPayment p WHERE p.employeeId = :employeeId AND p.payYear = :year "
            + "AND p.payMonth = :month AND p.status IN :statuses")
    boolean existsInStatus(@Param("employeeId") Long employeeId, @Param("year") int year, @Param("month") int month,
                           @Param("statuses") Collection<SalaryPaymentStatus> statuses);

    // Row locks in id order, so two approvers deciding overlapping batches can't deadlock or decide one twice
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM SalaryPayment p WHERE p.id IN :ids ORDER BY p.id ASC")
    List<SalaryPayment> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM SalaryPayment p WHERE p.id = :id")
    Optional<SalaryPayment> findByIdForUpdate(@Param("id") Long id);

    // An employee's approved months, newest first
    @Query("SELECT p FROM SalaryPayment p WHERE p.employeeId = :employeeId AND p.status = :status "
            + "ORDER BY p.payYear DESC, p.payMonth DESC")
    List<SalaryPayment> findByEmployeeAndStatus(@Param("employeeId") Long employeeId,
                                                @Param("status") SalaryPaymentStatus status);

    // Company account feed: approved payments in approval order
    @Query("SELECT p FROM SalaryPayment p WHERE p.status = :status AND p.decidedAt >= :since "
            + "ORDER BY p.decidedAt ASC, p.id ASC")
    List<SalaryPayment> findDecidedSince(@Param("status") SalaryPaymentStatus status, @Param("since") Instant since,
                                         Pageable pageable);

    // Approved payslips not emailed yet whose next try is due; rows another job instance holds are skipped
    @Query(value = """
            SELECT id FROM salary_payment
            WHERE status = 'APPROVED' AND payslip_emailed_at IS NULL AND payslip_email_attempts < :maxAttempts
              AND (payslip_email_last_attempt_at IS NULL OR payslip_email_last_attempt_at < :retryBefore)
            ORDER BY id
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findPayslipEmailsDue(@Param("maxAttempts") int maxAttempts, @Param("retryBefore") Instant retryBefore,
                                    @Param("limit") int limit);

    @Query(value = "SELECT * FROM salary_payment WHERE id = :id AND status = 'APPROVED' AND payslip_emailed_at IS NULL "
            + "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<SalaryPayment> claimPayslipEmail(@Param("id") Long id);
}
