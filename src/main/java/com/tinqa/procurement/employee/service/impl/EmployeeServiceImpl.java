package com.tinqa.procurement.employee.service.impl;

import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.common.validation.PhoneNumbers;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.employee.constants.EmployeeConstants;
import com.tinqa.procurement.employee.dto.CreateEmployeeRequest;
import com.tinqa.procurement.employee.dto.EmployeeApprovalDecisionRequest;
import com.tinqa.procurement.employee.dto.EmployeeResponse;
import com.tinqa.procurement.employee.dto.UpdateEmployeeRequest;
import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.employee.service.EmployeeContactValidator;
import com.tinqa.procurement.employee.service.EmployeeService;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.security.model.AuthClient;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class EmployeeServiceImpl implements EmployeeService {

    private static final String DEFAULT_PASSWORD = "TinQa";

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserProvider currentUserProvider;
    private final EmployeeContactValidator contactValidator;
    private final NotificationService notificationService;

    @Override
    @Transactional
    public EmployeeResponse createEmployee(CreateEmployeeRequest request) {
        User creator = currentUserProvider.getCurrentUser();

        List<ApiResponse.ApiErrorItem> invalid = new ArrayList<>();
        checkJoiningAfterAdulthood(request.getDateOfBirth(), request.getJoiningDate(), invalid);
        throwIfInvalid(invalid);

        List<ApiResponse.ApiErrorItem> duplicates = new ArrayList<>();
        contactValidator.checkNewAccount(request.getUsername(), request.getEmail(), duplicates);
        contactValidator.checkContacts(null, request.getPhone(), request.getAlternatePhone(), request.getPersonalEmail(),
                EmployeeContactValidator.Fields.EMPLOYEE, duplicates);
        contactValidator.throwIfAny(duplicates);

        Role userRole = request.getRole() == null || request.getRole().isBlank()
                ? Role.ADMIN_L1
                : Role.valueOf(request.getRole().trim().toUpperCase());

        // Same rule as stock, documents and profile changes: what an ADMIN_L1 creates waits for Admin L2, and a new
        // ADMIN_L2 account always needs a second Admin L2, so nobody can create L2 accounts alone
        boolean needsApproval = creator.getRole() != Role.ADMIN_L2 || userRole == Role.ADMIN_L2;

        User user = User.builder()
                .username(request.getUsername().trim())
                .email(request.getEmail().trim().toLowerCase(Locale.ROOT))
                .password(passwordEncoder.encode(DEFAULT_PASSWORD))
                .role(userRole)
                .authClient(AuthClient.WEB)
                // A pending employee cannot sign in until Admin L2 approves them
                .enabled(!needsApproval)
                .accountNonLocked(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        String displayName = request.getDisplayName();
        if (displayName == null || displayName.isBlank()) {
            displayName = (request.getFirstName() + " " +
                    (OptionalText.blankToNull(request.getMiddleName()) != null ? OptionalText.blankToNull(request.getMiddleName()) + " " : "") +
                    request.getLastName()).trim();
        }

        User savedUser;
        Employee savedEmployee;
        try {
            savedUser = userRepository.saveAndFlush(user);
            employeeRepository.lockEmployeeCodeSequence();
            Employee employee = Employee.builder()
                    .userId(savedUser.getId())
                    .employeeCode(String.format("EMP-%04d", employeeRepository.findHighestEmployeeCodeNumber() + 1))
                    .firstName(request.getFirstName().trim())
                    .middleName(OptionalText.blankToNull(request.getMiddleName()))
                    .lastName(request.getLastName().trim())
                    .displayName(displayName.trim())
                    .dateOfBirth(request.getDateOfBirth())
                    .gender(request.getGender())
                    .designation(request.getDesignation().trim())
                    .department(request.getDepartment().trim())
                    .employmentType(request.getEmploymentType())
                    .joiningDate(request.getJoiningDate())
                    .salaryAmount(request.getSalaryAmount() != null ? request.getSalaryAmount() : BigDecimal.ZERO)
                    .salaryCurrency(request.getSalaryCurrency() != null ? request.getSalaryCurrency() : "INR")
                    .phone(PhoneNumbers.normalize(request.getPhone()))
                    .alternatePhone(PhoneNumbers.normalize(request.getAlternatePhone()))
                    .personalEmail(lowerOrNull(request.getPersonalEmail()))
                    .status(needsApproval ? EmployeeConstants.APPROVAL_PENDING : EmployeeConstants.FIRST_LOGIN)
                    .createdBy(creator.getId())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            savedEmployee = employeeRepository.saveAndFlush(employee);
        } catch (DataIntegrityViolationException exception) {
            // Another request took one of these values between our checks and the insert
            throw new ApiException("These details were just used for another employee. Review the form and try again.",
                    EmployeeContactValidator.DUPLICATE_VALUE, HttpStatus.CONFLICT);
        }

        if (needsApproval) {
            notificationService.createForRole(Role.ADMIN_L2, creator.getId(), "Employee awaiting approval",
                    savedEmployee.getDisplayName() + " (" + savedEmployee.getEmployeeCode() + ", username " + savedUser.getUsername()
                            + ", role " + userRole + ") was created by " + creator.getUsername()
                            + " and can't sign in until an Admin L2 approves them.");
        }
        return mapToResponse(savedUser, savedEmployee);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EmployeeResponse> getAllEmployees(EmployeeConstants status, String search, Pageable pageable) {
        Page<Employee> employees;

        if (status != null && search != null && !search.isBlank()) {
            employees = employeeRepository.findByStatusAndSearchTerm(status, search.trim().toLowerCase(), pageable);
        } else if (status != null) {
            employees = employeeRepository.findByStatus(status, pageable);
        } else if (search != null && !search.isBlank()) {
            employees = employeeRepository.findBySearchTerm(search.trim().toLowerCase(), pageable);
        } else {
            employees = employeeRepository.findAll(pageable);
        }

        return employees.map(emp -> {
            User user = userRepository.findById(emp.getUserId()).orElse(null);
            return mapToResponse(user, emp);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public EmployeeResponse getEmployeeById(Long id) {
        Employee employee = findEmployeeOrThrow(id);
        User user = userRepository.findById(employee.getUserId()).orElse(null);
        return mapToResponse(user, employee);
    }

    @Override
    @Transactional
    public EmployeeResponse updateEmployee(Long id, UpdateEmployeeRequest request) {
        Employee employee = employeeRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with ID: " + id));

        List<ApiResponse.ApiErrorItem> invalid = new ArrayList<>();
        checkJoiningAfterAdulthood(
                request.getDateOfBirth() != null ? request.getDateOfBirth() : employee.getDateOfBirth(),
                request.getJoiningDate() != null ? request.getJoiningDate() : employee.getJoiningDate(), invalid);
        LocalDate joining = request.getJoiningDate() != null ? request.getJoiningDate() : employee.getJoiningDate();
        if (request.getLeavingDate() != null && joining != null && request.getLeavingDate().isBefore(joining)) {
            invalid.add(new ApiResponse.ApiErrorItem("leavingDate", "Leaving date cannot be before the joining date (" + joining + ")."));
        }
        if (request.getStatus() != null && request.getStatus() != employee.getStatus()) {
            if (employee.getStatus() == EmployeeConstants.APPROVAL_PENDING) {
                invalid.add(new ApiResponse.ApiErrorItem("status",
                        "This employee is waiting for Admin L2 approval; their status changes only through the approval decision."));
            } else if (request.getStatus() == EmployeeConstants.APPROVAL_PENDING
                    || request.getStatus() == EmployeeConstants.WAITING_FOR_DELETION) {
                invalid.add(new ApiResponse.ApiErrorItem("status", "Status " + request.getStatus()
                        + " can't be set here; use the approval or deletion request instead."));
            }
        }
        throwIfInvalid(invalid);

        List<ApiResponse.ApiErrorItem> duplicates = new ArrayList<>();
        contactValidator.checkContacts(employee.getId(),
                request.getPhone() != null ? request.getPhone() : employee.getPhone(),
                request.getAlternatePhone() != null ? request.getAlternatePhone() : employee.getAlternatePhone(),
                request.getPersonalEmail(), EmployeeContactValidator.Fields.EMPLOYEE, duplicates);
        contactValidator.throwIfAny(duplicates);

        if (request.getFirstName() != null) employee.setFirstName(request.getFirstName());
        // Middle name is optional: blank removes it
        if (request.getMiddleName() != null) employee.setMiddleName(OptionalText.blankToNull(request.getMiddleName()));
        if (request.getLastName() != null) employee.setLastName(request.getLastName());
        if (request.getDisplayName() != null) employee.setDisplayName(request.getDisplayName());
        if (request.getDateOfBirth() != null) employee.setDateOfBirth(request.getDateOfBirth());
        if (request.getGender() != null) employee.setGender(request.getGender());
        if (request.getDesignation() != null) employee.setDesignation(request.getDesignation());
        if (request.getDepartment() != null) employee.setDepartment(request.getDepartment());
        if (request.getEmploymentType() != null) employee.setEmploymentType(request.getEmploymentType());
        if (request.getJoiningDate() != null) employee.setJoiningDate(request.getJoiningDate());
        if (request.getLeavingDate() != null) employee.setLeavingDate(request.getLeavingDate());
        if (request.getSalaryAmount() != null) employee.setSalaryAmount(request.getSalaryAmount());
        if (request.getSalaryCurrency() != null) employee.setSalaryCurrency(request.getSalaryCurrency());
        if (request.getPhone() != null) employee.setPhone(PhoneNumbers.normalize(request.getPhone()));
        if (request.getAlternatePhone() != null) employee.setAlternatePhone(PhoneNumbers.normalize(request.getAlternatePhone()));
        if (request.getPersonalEmail() != null) employee.setPersonalEmail(lowerOrNull(request.getPersonalEmail()));
        if (request.getStatus() != null) employee.setStatus(request.getStatus());

        employee.setUpdatedAt(LocalDateTime.now());
        Employee updatedEmployee = employeeRepository.save(employee);
        User user = userRepository.findById(updatedEmployee.getUserId()).orElse(null);

        return mapToResponse(user, updatedEmployee);
    }

    @Override
    @Transactional
    public EmployeeResponse requestEmployeeDeletion(Long id) {
        Employee employee = findEmployeeOrThrow(id);

        if (employee.getStatus() == EmployeeConstants.WAITING_FOR_DELETION) {
            throw new BadRequestException("Employee is already waiting for deletion");
        }

        employee.setStatus(EmployeeConstants.WAITING_FOR_DELETION);
        employee.setUpdatedAt(LocalDateTime.now());
        Employee savedEmployee = employeeRepository.save(employee);

        User user = userRepository.findById(savedEmployee.getUserId()).orElse(null);
        return mapToResponse(user, savedEmployee);
    }

    @Override
    @Transactional
    public void finalizeDeleteEmployee(Long id) {
        Employee employee = findEmployeeOrThrow(id);

        if (employee.getStatus() != EmployeeConstants.WAITING_FOR_DELETION) {
            throw new BadRequestException("Employee status must be 'WAITING_FOR_DELETION' to finalize deletion");
        }

        Long userId = employee.getUserId();

        // 1. Delete Employee profile
        employeeRepository.delete(employee);

        // 2. Delete corresponding Security User
        if (userId != null && userRepository.existsById(userId)) {
            userRepository.deleteById(userId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmployeeResponse> getPendingApprovals() {
        return employeeRepository.findByStatusOrderByCreatedAtAscIdAsc(EmployeeConstants.APPROVAL_PENDING).stream()
                .map(employee -> mapToResponse(userRepository.findById(employee.getUserId()).orElse(null), employee))
                .toList();
    }

    @Override
    @Transactional
    public EmployeeResponse decideApproval(Long id, EmployeeApprovalDecisionRequest request) {
        User reviewer = currentUserProvider.getCurrentUser();
        boolean approve = "APPROVED".equals(request.getDecision());
        String reason = request.getReason() == null ? null : request.getReason().trim();
        if (!approve && (reason == null || reason.isEmpty())) {
            throwIfInvalid(List.of(new ApiResponse.ApiErrorItem("reason", "A reason is required to reject an employee.")));
        }

        Employee employee = employeeRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with ID: " + id));
        if (employee.getStatus() != EmployeeConstants.APPROVAL_PENDING) {
            throw new ApiException("Employee " + employee.getEmployeeCode() + " is not waiting for approval (status "
                    + employee.getStatus() + ").", "EMPLOYEE_NOT_PENDING_APPROVAL", HttpStatus.CONFLICT);
        }
        if (reviewer.getId().equals(employee.getCreatedBy()) || reviewer.getId().equals(employee.getUserId())) {
            throw new ApiException("You created this employee, so another Admin L2 has to review them.",
                    "EMPLOYEE_APPROVAL_SELF_DECISION", HttpStatus.FORBIDDEN);
        }

        User user = userRepository.findById(employee.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Login account not found for employee " + employee.getEmployeeCode()));
        user.setEnabled(approve);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        employee.setStatus(approve
                ? (user.isFirstLogin() ? EmployeeConstants.FIRST_LOGIN : EmployeeConstants.ACTIVE)
                : EmployeeConstants.IN_ACTIVE);
        employee.setApprovalDecidedBy(reviewer.getId());
        employee.setApprovalDecidedAt(LocalDateTime.now());
        employee.setApprovalRejectionReason(approve ? null : reason);
        employee.setUpdatedAt(LocalDateTime.now());
        Employee saved = employeeRepository.save(employee);

        if (saved.getCreatedBy() != null) {
            String who = saved.getDisplayName() + " (" + saved.getEmployeeCode() + ")";
            notificationService.createForUser(saved.getCreatedBy(), approve ? "Employee approved" : "Employee rejected",
                    approve
                            ? who + " was approved by " + reviewer.getUsername() + " and can now sign in."
                            : who + " was rejected by " + reviewer.getUsername() + ". Reason: " + reason
                            + ". Their account has been deactivated.");
        }
        return mapToResponse(user, saved);
    }

    // Nobody joins before turning 18
    private void checkJoiningAfterAdulthood(LocalDate dateOfBirth, LocalDate joiningDate, List<ApiResponse.ApiErrorItem> errors) {
        if (dateOfBirth != null && joiningDate != null && joiningDate.isBefore(dateOfBirth.plusYears(18))) {
            errors.add(new ApiResponse.ApiErrorItem("joiningDate", "Joining date must be on or after the employee's 18th birthday ("
                    + dateOfBirth.plusYears(18) + ")."));
        }
    }

    private void throwIfInvalid(List<ApiResponse.ApiErrorItem> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(errors.size() == 1 ? errors.getFirst().getMessage() : "One or more request fields are invalid.",
                    "VALIDATION_FAILED", HttpStatus.BAD_REQUEST, errors);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String lowerOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private Employee findEmployeeOrThrow(Long id) {
        return employeeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with ID: " + id));
    }

    private EmployeeResponse mapToResponse(User user, Employee employee) {
        return EmployeeResponse.builder()
                .id(employee.getId())
                .userId(employee.getUserId())
                .username(user != null ? user.getUsername() : null)
                .employeeCode(employee.getEmployeeCode())
                .firstName(employee.getFirstName())
                .middleName(employee.getMiddleName())
                .lastName(employee.getLastName())
                .displayName(employee.getDisplayName())
                .dateOfBirth(employee.getDateOfBirth())
                .gender(employee.getGender())
                .designation(employee.getDesignation())
                .department(employee.getDepartment())
                .employmentType(employee.getEmploymentType())
                .joiningDate(employee.getJoiningDate())
                .leavingDate(employee.getLeavingDate())
                .salaryAmount(employee.getSalaryAmount())
                .salaryCurrency(employee.getSalaryCurrency())
                .phone(employee.getPhone())
                .alternatePhone(employee.getAlternatePhone())
                .personalEmail(employee.getPersonalEmail())
                .workEmail(Objects.nonNull(user) ? user.getEmail() : "")
                .status(employee.getStatus())
                .createdBy(employee.getCreatedBy())
                .createdByUsername(usernameOf(employee.getCreatedBy()))
                .approvalDecidedBy(employee.getApprovalDecidedBy())
                .approvalDecidedByUsername(usernameOf(employee.getApprovalDecidedBy()))
                .approvalDecidedAt(employee.getApprovalDecidedAt())
                .approvalRejectionReason(employee.getApprovalRejectionReason())
                .createdAt(employee.getCreatedAt())
                .updatedAt(employee.getUpdatedAt())
                .build();
    }

    private String usernameOf(Long userId) {
        return userId == null ? null : userRepository.findById(userId).map(User::getUsername).orElse(null);
    }
}
