package com.tinqa.procurement.security.access;

import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out an account's access from the database: its role, and for admins the designation on their employee
 * profile. Called on every authenticated request, so designation and level changes take effect immediately.
 */
@Service
@RequiredArgsConstructor
public class AccessResolver {

    private final EmployeeRepository employeeRepository;

    public UserAccess resolve(User user) {
        Designation designation = isAdmin(user.getRole())
                ? employeeRepository.findByUserId(user.getId()).map(Employee::getDesignation).orElse(null)
                : null;
        return UserAccess.of(user.getRole(), designation);
    }

    /** {@link #resolve} for many accounts at once, with one query for all their designations (by user id). */
    public Map<Long, UserAccess> resolveAll(Collection<User> users) {
        List<Long> adminIds = users.stream().filter(user -> isAdmin(user.getRole())).map(User::getId).toList();
        Map<Long, Designation> designations = new HashMap<>();
        if (!adminIds.isEmpty()) {
            employeeRepository.findByUserIdIn(adminIds).forEach(e -> designations.putIfAbsent(e.getUserId(), e.getDesignation()));
        }
        Map<Long, UserAccess> access = new HashMap<>();
        users.forEach(user -> access.put(user.getId(),
                UserAccess.of(user.getRole(), isAdmin(user.getRole()) ? designations.get(user.getId()) : null)));
        return access;
    }

    private static boolean isAdmin(Role role) {
        return role == Role.ADMIN_L1 || role == Role.ADMIN_L2;
    }
}
