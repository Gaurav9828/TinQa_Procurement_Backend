package com.tinqa.procurement.security.access.role;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CustomRoleRepository extends JpaRepository<CustomRole, Long> {

    // Every custom role with its groups; a handful of rows by design (roles are made by hand)
    @Query("SELECT DISTINCT r FROM CustomRole r LEFT JOIN FETCH r.groups ORDER BY r.label")
    List<CustomRole> findAllWithGroups();

    @Query("SELECT DISTINCT r FROM CustomRole r LEFT JOIN FETCH r.groups WHERE r.active = true ORDER BY r.label")
    List<CustomRole> findActiveWithGroups();

    @Query("SELECT r FROM CustomRole r LEFT JOIN FETCH r.groups WHERE r.code = :code")
    Optional<CustomRole> findWithGroupsByCode(@Param("code") String code);

    boolean existsByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM CustomRole r WHERE r.id = :id")
    Optional<CustomRole> findByIdForUpdate(@Param("id") Long id);

    // Employees per designation (built-in name or "#<custom role id>"), deleted employees left out, in one statement
    @Query(value = """
            SELECT COALESCE(designation, '#' || custom_role_id) AS role_key, COUNT(*) AS employees
            FROM employee
            WHERE status <> 'DELETED'
            GROUP BY designation, custom_role_id
            """, nativeQuery = true)
    List<Object[]> countEmployeesByRole();

    // Any employee row (deleted ones too: they keep their designation for history) holding the role
    @Query(value = "SELECT EXISTS (SELECT 1 FROM employee WHERE custom_role_id = :roleId)", nativeQuery = true)
    boolean isInUse(@Param("roleId") Long roleId);

    // The department follows the designation, also when a custom role's department is renamed
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE employee SET department = :department, updated_at = now() WHERE custom_role_id = :roleId "
            + "AND department <> :department", nativeQuery = true)
    int updateEmployeeDepartments(@Param("roleId") Long roleId, @Param("department") String department);
}
