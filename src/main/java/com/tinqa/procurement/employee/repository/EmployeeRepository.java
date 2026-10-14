package com.tinqa.procurement.employee.repository;

import com.tinqa.procurement.employee.constants.EmployeeConstants;
import com.tinqa.procurement.employee.model.Employee;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {
    Page<Employee> findByStatus(EmployeeConstants status, Pageable pageable);

    @Query("SELECT e FROM Employee e WHERE " +
            "(LOWER(e.firstName) LIKE %:search% OR " +
            "LOWER(e.lastName) LIKE %:search% OR " +
            "LOWER(e.employeeCode) LIKE %:search% OR " +
            "LOWER(e.department) LIKE %:search% OR " +
            "LOWER(e.phone) LIKE %:search%)")
    Page<Employee> findBySearchTerm(@Param("search") String search, Pageable pageable);

    @Query("SELECT e FROM Employee e WHERE e.status = :status AND " +
            "(LOWER(e.firstName) LIKE %:search% OR " +
            "LOWER(e.lastName) LIKE %:search% OR " +
            "LOWER(e.employeeCode) LIKE %:search% OR " +
            "LOWER(e.department) LIKE %:search% OR " +
            "LOWER(e.phone) LIKE %:search%)")
    Page<Employee> findByStatusAndSearchTerm(@Param("status") EmployeeConstants status, @Param("search") String search, Pageable pageable);

    Optional<Employee> findByUserId(Long userId);

    // Another employee using any of these numbers as phone or alternate phone (excludeEmployeeId = -1 for none)
    @Query("""
            SELECT e FROM Employee e
            WHERE (e.phone IN :numbers OR e.alternatePhone IN :numbers)
              AND e.id <> :excludeEmployeeId
            """)
    List<Employee> findUsingAnyPhone(@Param("numbers") Collection<String> numbers,
                                     @Param("excludeEmployeeId") Long excludeEmployeeId);

    Optional<Employee> findFirstByPersonalEmailIgnoreCaseAndIdNot(String personalEmail, Long employeeId);

    List<Employee> findByStatusOrderByCreatedAtAscIdAsc(EmployeeConstants status);

    // Highest numeric EMP-#### code in use (codes are never reused, even after deletions)
    @Query(value = "SELECT COALESCE(MAX(CAST(substring(employee_code FROM 5) AS INTEGER)), 0) FROM employee "
            + "WHERE employee_code ~ '^EMP-[0-9]+$'", nativeQuery = true)
    int findHighestEmployeeCodeNumber();

    @Query(value = "SELECT CAST(pg_advisory_xact_lock(hashtextextended('employee-code', 0)) AS text)", nativeQuery = true)
    String lockEmployeeCodeSequence();

    boolean existsByPersonalEmailIgnoreCaseAndIdNot(
            String personalEmail,
            Long employeeId
    );

    boolean existsByPhoneAndIdNot(
            String phone,
            Long employeeId
    );

    boolean existsByAlternatePhoneAndIdNot(
            String alternatePhone,
            Long employeeId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT e
        FROM Employee e
        WHERE e.id = :employeeId
        """)
    Optional<Employee> findByIdForUpdate(
            @Param("employeeId") Long employeeId
    );
}