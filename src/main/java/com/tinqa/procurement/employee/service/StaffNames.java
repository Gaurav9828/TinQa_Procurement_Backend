package com.tinqa.procurement.employee.service;

import com.tinqa.procurement.employee.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Names of user accounts for views (the employee's display name, else the username), in one query for all. */
@Component
@RequiredArgsConstructor
public class StaffNames {

    private final EmployeeRepository employeeRepository;

    public Map<Long, String> of(Collection<Long> userIds) {
        Set<Long> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (Object[] row : employeeRepository.findDisplayNames(ids)) {
            names.putIfAbsent(((Number) row[0]).longValue(), (String) row[1]);
        }
        return names;
    }
}
