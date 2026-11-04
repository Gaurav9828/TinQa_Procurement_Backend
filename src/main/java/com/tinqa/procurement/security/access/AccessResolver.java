package com.tinqa.procurement.security.access;

import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.security.access.role.CustomRole;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out an account's access from the database: its role, and for admins the designation (built-in or custom role)
 * on their employee profile. Called on every authenticated request, so designation and level changes take effect
 * immediately.
 */
@Service
@RequiredArgsConstructor
public class AccessResolver {

    private final EmployeeRepository employeeRepository;

    public UserAccess resolve(User user) {
        if (!isAdmin(user.getRole())) {
            return UserAccess.of(user.getRole(), null);
        }
        List<Employee> employees = employeeRepository.findWithRoleByUserId(user.getId());
        return employees.isEmpty() ? UserAccess.of(user.getRole(), null) : accessOf(user.getRole(), employees.getFirst());
    }

    /** {@link #resolve} for many accounts at once, with one query for all their designations and custom roles. */
    public Map<Long, UserAccess> resolveAll(Collection<User> users) {
        List<Long> adminIds = users.stream().filter(user -> isAdmin(user.getRole())).map(User::getId).toList();
        Map<Long, Employee> employees = new HashMap<>();
        if (!adminIds.isEmpty()) {
            employeeRepository.findWithRoleByUserIdIn(adminIds).forEach(e -> employees.putIfAbsent(e.getUserId(), e));
        }
        Map<Long, UserAccess> access = new HashMap<>();
        users.forEach(user -> {
            Employee employee = isAdmin(user.getRole()) ? employees.get(user.getId()) : null;
            access.put(user.getId(), employee == null ? UserAccess.of(user.getRole(), null) : accessOf(user.getRole(), employee));
        });
        return access;
    }

    /** A custom role's grant, detached from the database (its groups must be loaded). */
    public static CustomRoleGrant grantOf(CustomRole role) {
        return role == null ? null
                : new CustomRoleGrant(role.getId(), role.getCode(), role.getLabel(), role.getDepartment(), role.getGroups());
    }

    private static UserAccess accessOf(Role role, Employee employee) {
        return UserAccess.of(role, employee.getDesignation(), grantOf(employee.getCustomRole()));
    }

    private static boolean isAdmin(Role role) {
        return role == Role.ADMIN_L1 || role == Role.ADMIN_L2;
    }
}
