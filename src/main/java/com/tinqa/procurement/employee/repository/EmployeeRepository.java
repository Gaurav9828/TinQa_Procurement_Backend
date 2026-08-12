package com.tinqa.procurement.employee.repository;

import com.tinqa.procurement.employee.model.Employee;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

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