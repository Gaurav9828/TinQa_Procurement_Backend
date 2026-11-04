package com.tinqa.procurement.employee.repository;

import com.tinqa.procurement.employee.enums.EmployeeStatus;
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
    Page<Employee> findByStatus(EmployeeStatus status, Pageable pageable);

    // "All statuses" leaves out one status (DELETED), which is only listed when asked for
    Page<Employee> findByStatusNot(EmployeeStatus excluded, Pageable pageable);

    @Query("SELECT e FROM Employee e WHERE e.status <> :excluded AND " +
            "(LOWER(e.firstName) LIKE %:search% OR " +
            "LOWER(e.lastName) LIKE %:search% OR " +
            "LOWER(CONCAT(e.firstName, ' ', e.lastName)) LIKE %:search% OR " +
            "LOWER(e.employeeCode) LIKE %:search% OR " +
            "LOWER(e.department) LIKE %:search% OR " +
            "LOWER(e.phone) LIKE %:search%)")
    Page<Employee> findBySearchTermExcludingStatus(@Param("search") String search, @Param("excluded") EmployeeStatus excluded,
                                                  Pageable pageable);

    @Query("SELECT e FROM Employee e WHERE e.status = :status AND " +
            "(LOWER(e.firstName) LIKE %:search% OR " +
            "LOWER(e.lastName) LIKE %:search% OR " +
            "LOWER(CONCAT(e.firstName, ' ', e.lastName)) LIKE %:search% OR " +
            "LOWER(e.employeeCode) LIKE %:search% OR " +
            "LOWER(e.department) LIKE %:search% OR " +
            "LOWER(e.phone) LIKE %:search%)")
    Page<Employee> findByStatusAndSearchTerm(@Param("status") EmployeeStatus status, @Param("search") String search, Pageable pageable);

    Optional<Employee> findByUserId(Long userId);

    List<Employee> findByUserIdIn(Collection<Long> userIds);

    // Meeting invitees: active employees by name (search over lower(display_name) uses its trigram index)
    @Query("SELECT e FROM Employee e WHERE e.status IN :statuses ORDER BY e.displayName, e.id")
    Page<Employee> findByStatusInOrderByName(@Param("statuses") Collection<EmployeeStatus> statuses, Pageable pageable);

    @Query("SELECT e FROM Employee e WHERE e.status IN :statuses AND LOWER(e.displayName) LIKE :pattern ORDER BY e.displayName, e.id")
    Page<Employee> searchByStatusInOrderByName(@Param("statuses") Collection<EmployeeStatus> statuses,
                                               @Param("pattern") String pattern, Pageable pageable);

    // Display names of user accounts: the employee's display name, else the username (one row per account)
    @Query(value = """
            SELECT u.id, COALESCE(NULLIF(TRIM(e.display_name), ''), u.username)
            FROM users u LEFT JOIN employee e ON e.user_id = u.id
            WHERE u.id IN (:userIds)
            """, nativeQuery = true)
    List<Object[]> findDisplayNames(@Param("userIds") Collection<Long> userIds);

    // Access checks: the employee with their custom role and its groups, in one query
    @Query("SELECT e FROM Employee e LEFT JOIN FETCH e.customRole r LEFT JOIN FETCH r.groups WHERE e.userId = :userId")
    List<Employee> findWithRoleByUserId(@Param("userId") Long userId);

    @Query("SELECT DISTINCT e FROM Employee e LEFT JOIN FETCH e.customRole r LEFT JOIN FETCH r.groups WHERE e.userId IN :userIds")
    List<Employee> findWithRoleByUserIdIn(@Param("userIds") Collection<Long> userIds);

    // Another employee using any of these numbers as phone or alternate phone (excludeEmployeeId = -1 for none)
    @Query("""
            SELECT e FROM Employee e
            WHERE (e.phone IN :numbers OR e.alternatePhone IN :numbers)
              AND e.id <> :excludeEmployeeId
            """)
    List<Employee> findUsingAnyPhone(@Param("numbers") Collection<String> numbers,
                                     @Param("excludeEmployeeId") Long excludeEmployeeId);

    Optional<Employee> findFirstByPersonalEmailIgnoreCaseAndIdNot(String personalEmail, Long employeeId);

    Optional<Employee> findFirstByPanNumberAndIdNot(String panNumber, Long employeeId);

    List<Employee> findByStatusOrderByCreatedAtAscIdAsc(EmployeeStatus status);

    // Highest numeric EMP-#### code in use (codes are never reused, even after deletions)
    @Query(value = "SELECT COALESCE(MAX(CAST(substring(employee_code FROM 5) AS INTEGER)), 0) FROM employee "
            + "WHERE employee_code ~ '^EMP-[0-9]+$'", nativeQuery = true)
    int findHighestEmployeeCodeNumber();

    @Query(value = "SELECT CAST(pg_advisory_xact_lock(hashtextextended('employee-code', 0)) AS text)", nativeQuery = true)
    String lockEmployeeCodeSequence();

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