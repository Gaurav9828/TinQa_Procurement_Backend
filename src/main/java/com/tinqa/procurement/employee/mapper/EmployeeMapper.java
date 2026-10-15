package com.tinqa.procurement.employee.mapper;

import com.tinqa.procurement.employee.dto.EmployeeResponse;
import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.security.model.User;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class EmployeeMapper {

    /**
     * @param usersById the employee's own account and the accounts that created / decided it, as far as they exist
     */
    public EmployeeResponse toResponse(Employee employee, Map<Long, User> usersById) {
        User account = usersById.get(employee.getUserId());
        return EmployeeResponse.builder()
                .id(employee.getId())
                .userId(employee.getUserId())
                .username(account != null ? account.getUsername() : null)
                .employeeCode(employee.getEmployeeCode())
                .firstName(employee.getFirstName())
                .middleName(employee.getMiddleName())
                .lastName(employee.getLastName())
                .displayName(employee.getDisplayName())
                .dateOfBirth(employee.getDateOfBirth())
                .gender(employee.getGender())
                .designation(employee.getDesignation() == null ? null : employee.getDesignation().name())
                .designationLabel(employee.getDesignation() == null ? null : employee.getDesignation().label())
                .department(employee.getDepartment())
                .employmentType(employee.getEmploymentType())
                .joiningDate(employee.getJoiningDate())
                .leavingDate(employee.getLeavingDate())
                .salaryAmount(employee.getSalaryAmount())
                .salaryCurrency(employee.getSalaryCurrency())
                .phone(employee.getPhone())
                .alternatePhone(employee.getAlternatePhone())
                .personalEmail(employee.getPersonalEmail())
                .workEmail(account != null ? account.getEmail() : "")
                .role(account != null && account.getRole() != null ? account.getRole().name() : null)
                .status(employee.getStatus())
                .createdBy(employee.getCreatedBy())
                .createdByUsername(usernameOf(employee.getCreatedBy(), usersById))
                .approvalDecidedBy(employee.getApprovalDecidedBy())
                .approvalDecidedByUsername(usernameOf(employee.getApprovalDecidedBy(), usersById))
                .approvalDecidedAt(employee.getApprovalDecidedAt())
                .approvalRejectionReason(employee.getApprovalRejectionReason())
                .createdAt(employee.getCreatedAt())
                .updatedAt(employee.getUpdatedAt())
                .build();
    }

    private static String usernameOf(Long userId, Map<Long, User> usersById) {
        User user = userId == null ? null : usersById.get(userId);
        return user == null ? null : user.getUsername();
    }
}
