package com.tinqa.procurement.employee.service;

import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Who took a step in a flow, as histories show it: the role ("Supplier Manager", "Super Admin", "Supplier") and, for
 * staff views only, the name. Two queries for any number of users (their accounts and their employee records).
 */
@Component
@RequiredArgsConstructor
public class ActorDirectory {

    public static final String SUPPLIER = "Supplier";

    /** A user as a history shows them; {@code name} is the employee's display name, else the username. */
    public record Actor(String role, String name, boolean supplier) {
    }

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;

    public Map<Long, Actor> of(Collection<Long> userIds) {
        Set<Long> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, User> users = userRepository.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, Employee> employees = employeeRepository.findWithRoleByUserIdIn(ids).stream()
                .collect(Collectors.toMap(Employee::getUserId, Function.identity(), (first, second) -> first));
        Map<Long, Actor> actors = new HashMap<>();
        for (Long id : ids) {
            User user = users.get(id);
            if (user == null) {
                continue;
            }
            if (user.getRole() == Role.SUPPLIER) {
                actors.put(id, new Actor(SUPPLIER, user.getUsername(), true));
                continue;
            }
            Employee employee = employees.get(id);
            String role = employee != null && employee.designationLabel() != null ? employee.designationLabel() : "Admin";
            String name = employee != null && employee.getDisplayName() != null && !employee.getDisplayName().isBlank()
                    ? employee.getDisplayName() : user.getUsername();
            actors.put(id, new Actor(role, name, false));
        }
        return actors;
    }
}
