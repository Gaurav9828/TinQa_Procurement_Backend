package com.tinqa.procurement.security.access;

import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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

    private static boolean isAdmin(Role role) {
        return role == Role.ADMIN_L1 || role == Role.ADMIN_L2;
    }
}
