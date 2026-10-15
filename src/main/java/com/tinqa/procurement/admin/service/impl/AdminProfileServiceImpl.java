package com.tinqa.procurement.admin.service.impl;

import com.tinqa.procurement.admin.dto.AdminProfileResponse;
import com.tinqa.procurement.admin.dto.AdminProfileUpdateRequest;
import com.tinqa.procurement.admin.dto.ProfileUpdateResult;
import com.tinqa.procurement.admin.service.AdminProfileService;
import com.tinqa.procurement.approval.entity.EmployeeProfileChangeRequest;
import com.tinqa.procurement.approval.profile.ApprovalControlledFields;
import com.tinqa.procurement.approval.repository.EmployeeProfileChangeRequestRepository;
import com.tinqa.procurement.common.enums.ApprovalStatus;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ConflictException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.common.validation.PhoneNumbers;
import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.employee.service.EmployeeContactValidator;
import com.tinqa.procurement.security.access.CurrentAccess;
import com.tinqa.procurement.security.access.Designation;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.service.AccountGuard;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AdminProfileServiceImpl implements AdminProfileService {

    private static final Set<Role> ADMIN_ROLES = EnumSet.of(Role.ADMIN_L1, Role.ADMIN_L2);
    private static final String PENDING = ApprovalStatus.PENDING.name();

    private final EmployeeRepository employeeRepository;
    private final EmployeeProfileChangeRequestRepository changeRequestRepository;
    private final CurrentUserProvider currentUserProvider;
    private final CurrentAccess currentAccess;
    private final EmployeeContactValidator contactValidator;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public AdminProfileResponse getProfile() {
        User admin = currentAdmin();
        return buildProfileResponse(employeeOf(admin), admin);
    }

    @Override
    @Transactional
    public ProfileUpdateResult updateProfile(AdminProfileUpdateRequest request) {
        User admin = currentAdmin();
        Employee employee = employeeOf(admin);

        List<ApiResponse.ApiErrorItem> duplicates = new ArrayList<>();
        contactValidator.checkContacts(employee.getId(),
                request.getPrimaryPhone() != null ? request.getPrimaryPhone() : employee.getPhone(),
                request.getAlternatePhone() != null ? request.getAlternatePhone() : employee.getAlternatePhone(),
                request.getPersonalEmail(), EmployeeContactValidator.Fields.ADMIN_PROFILE, duplicates);
        contactValidator.throwIfAny(duplicates);
        normalizeContacts(request);
        rejectDesignationChange(employee, request);
        rejectDepartmentChange(employee, request);

        // Whoever can approve profile changes applies their own directly; everyone else waits for an approver
        boolean approvalRequired = !currentAccess.has(Permission.EMPLOYEE_PROFILE_CHANGE_APPROVE)
                && !ApprovalControlledFields.changedFields(employee, request).isEmpty();

        applyContactFields(employee, request);
        if (approvalRequired) {
            // Approval-controlled fields are not written to the employee until an approver accepts them
            createChangeRequest(employee, admin, request);
        } else {
            ApprovalControlledFields.apply(employee, request);
        }
        employee.setUpdatedAt(LocalDateTime.now(clock));
        employeeRepository.save(employee);

        return ProfileUpdateResult.builder()
                .profile(buildProfileResponse(employee, admin))
                .approvalRequired(approvalRequired)
                .build();
    }

    // The designation decides what the admin can access, so they can't change their own. Sending the current value back
    // (as an edit form does) is fine.
    private static void rejectDesignationChange(Employee employee, AdminProfileUpdateRequest request) {
        if (OptionalText.blankToNull(request.getDesignation()) == null) {
            return;
        }
        boolean unchanged = Designation.fromCode(request.getDesignation())
                .map(requested -> requested == employee.getDesignation())
                .orElse(false);
        if (!unchanged) {
            throw ApiErrors.fieldError("designation",
                    "Your designation decides what you can access, so it can only be changed by an administrator in Employee Management.");
        }
    }

    // The department follows the designation; the current value may be sent back unchanged
    private static void rejectDepartmentChange(Employee employee, AdminProfileUpdateRequest request) {
        String requested = OptionalText.blankToNull(request.getDepartment());
        if (requested != null && !requested.trim().equalsIgnoreCase(employee.getDepartment())) {
            throw ApiErrors.fieldError("department",
                    "Your department follows your designation, so it can only be changed by an administrator in Employee Management.");
        }
    }

    private User currentAdmin() {
        User user = currentUserProvider.getCurrentUser();
        AccountGuard.requireRole(user, ADMIN_ROLES, "Only administrators can update admin profile");
        AccountGuard.requireActive(user);
        return user;
    }

    private Employee employeeOf(User user) {
        return employeeRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee profile not found"));
    }

    private void normalizeContacts(AdminProfileUpdateRequest request) {
        if (request.getPrimaryPhone() != null) {
            request.setPrimaryPhone(PhoneNumbers.normalize(request.getPrimaryPhone()));
        }
        if (request.getAlternatePhone() != null) {
            request.setAlternatePhone(PhoneNumbers.normalize(request.getAlternatePhone()));
        }
        if (request.getPersonalEmail() != null && !request.getPersonalEmail().isBlank()) {
            request.setPersonalEmail(OptionalText.lowerCaseOrNull(request.getPersonalEmail()));
        }
    }

    // Fields every administrator may change without approval
    private void applyContactFields(Employee employee, AdminProfileUpdateRequest request) {
        if (request.getDisplayName() != null) employee.setDisplayName(request.getDisplayName());
        if (request.getPrimaryPhone() != null) employee.setPhone(request.getPrimaryPhone());
        if (request.getAlternatePhone() != null) employee.setAlternatePhone(request.getAlternatePhone());
        if (request.getPersonalEmail() != null) employee.setPersonalEmail(request.getPersonalEmail());
    }

    private void createChangeRequest(Employee employee, User requester, AdminProfileUpdateRequest request) {
        if (changeRequestRepository.existsByEmployeeIdAndStatus(employee.getId(), PENDING)) {
            throw new ConflictException("A profile change request is already pending for this employee");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        changeRequestRepository.save(EmployeeProfileChangeRequest.builder()
                .employeeId(employee.getId())
                .requestedBy(requester.getId())
                .status(PENDING)
                // Only approval-controlled fields are stored
                .dateOfBirth(request.getDateOfBirth())
                .firstName(request.getFirstName())
                // "" = remove the middle name once approved; null = not requested
                .middleName(request.getMiddleName() == null ? null : request.getMiddleName().trim())
                .lastName(request.getLastName())
                .gender(request.getGender())
                .requestedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    // The profile is always the signed-in admin's own, so the account is the current user
    private AdminProfileResponse buildProfileResponse(Employee employee, User account) {
        Set<String> pendingFields = changeRequestRepository
                .findFirstByEmployeeIdAndStatusOrderByRequestedAtDesc(employee.getId(), PENDING)
                .map(pending -> ApprovalControlledFields.changedFields(employee, pending))
                .orElse(Set.of());

        return AdminProfileResponse.builder()
                .employeeId(employee.getId())
                .employeeCode(employee.getEmployeeCode())
                .displayName(employee.getDisplayName())
                .primaryPhone(employee.getPhone())
                .alternatePhone(employee.getAlternatePhone())
                .personalEmail(employee.getPersonalEmail())
                .firstName(employee.getFirstName())
                .middleName(employee.getMiddleName())
                .lastName(employee.getLastName())
                .department(employee.getDepartment())
                .designation(employee.getDesignation() == null ? null : employee.getDesignation().name())
                .designationLabel(employee.getDesignation() == null ? null : employee.getDesignation().label())
                .workEmail(Objects.requireNonNull(account).getEmail())
                .username(account.getUsername())
                .gender(employee.getGender())
                .employmentType(employee.getEmploymentType())
                .joiningDate(employee.getJoiningDate())
                .dateOfBirth(employee.getDateOfBirth())
                .status(employee.getStatus())
                .approvalPending(!pendingFields.isEmpty())
                .pendingApprovalFields(pendingFields)
                .build();
    }
}
