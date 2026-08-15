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