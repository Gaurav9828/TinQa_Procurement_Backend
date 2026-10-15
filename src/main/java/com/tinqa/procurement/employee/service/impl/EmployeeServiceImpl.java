package com.tinqa.procurement.employee.service.impl;

import com.tinqa.procurement.common.constant.AppConstants;
import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.common.validation.PhoneNumbers;
import com.tinqa.procurement.employee.config.EmployeeProperties;
import com.tinqa.procurement.employee.dto.CreateEmployeeRequest;
import com.tinqa.procurement.employee.dto.EmployeeApprovalDecisionRequest;
import com.tinqa.procurement.employee.dto.EmployeeResponse;
import com.tinqa.procurement.employee.dto.UpdateEmployeeRequest;
import com.tinqa.procurement.employee.enums.EmployeeStatus;
import com.tinqa.procurement.employee.mapper.EmployeeMapper;
import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.employee.service.EmployeeContactValidator;
import com.tinqa.procurement.employee.service.EmployeeService;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.security.access.CurrentAccess;
import com.tinqa.procurement.security.access.Designation;
import com.tinqa.procurement.security.access.DesignationAccess;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.model.AuthClient;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class EmployeeServiceImpl implements EmployeeService {

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserProvider currentUserProvider;
    private final CurrentAccess currentAccess;
    private final EmployeeContactValidator contactValidator;
    private final NotificationService notificationService;
    private final EmployeeMapper employeeMapper;
    private final EmployeeProperties employeeProperties;
    private final Clock clock;

    @Override
    @Transactional
    public EmployeeResponse createEmployee(CreateEmployeeRequest request) {
        User creator = currentUserProvider.getCurrentUser();

        List<ApiResponse.ApiErrorItem> invalid = new ArrayList<>();
        checkJoiningAfterAdulthood(request.getDateOfBirth(), request.getJoiningDate(), invalid);
        ApiErrors.throwIfAny(invalid);

        List<ApiResponse.ApiErrorItem> duplicates = new ArrayList<>();
        contactValidator.checkNewAccount(request.getUsername(), request.getEmail(), duplicates);
        contactValidator.checkContacts(null, request.getPhone(), request.getAlternatePhone(), request.getPersonalEmail(),
                EmployeeContactValidator.Fields.EMPLOYEE, duplicates);
        contactValidator.throwIfAny(duplicates);

        Role requestedRole = OptionalText.blankToNull(request.getRole()) == null
                ? Role.ADMIN_L1
                : Role.valueOf(request.getRole().trim().toUpperCase(Locale.ROOT));

        Designation designation = Designation.fromCode(request.getDesignation()).orElseThrow();
        // Level 2 only exists for designations with approval powers
        Role role = DesignationAccess.effectiveLevel(requestedRole, designation);
        List<ApiResponse.ApiErrorItem> departmentErrors = new ArrayList<>();
        checkDepartmentMatches(request.getDepartment(), designation, departmentErrors);
        ApiErrors.throwIfAny(departmentErrors);

        // Maker-checker: an account waits for approval unless its creator can approve employees, and a new
        // ADMIN_L2 or Super Admin always needs a second approver, so nobody can create a powerful account alone
        boolean needsApproval = !currentAccess.has(Permission.EMPLOYEE_APPROVE)
                || role == Role.ADMIN_L2
                || designation == Designation.SUPER_ADMIN;
        LocalDateTime now = LocalDateTime.now(clock);

        User account = User.builder()
                .username(request.getUsername().trim())
                .email(request.getEmail().trim().toLowerCase(Locale.ROOT))
                .password(passwordEncoder.encode(employeeProperties.defaultPassword()))
                .role(role)
                .authClient(AuthClient.WEB)
                // A pending employee cannot sign in until an employee approver approves them
                .enabled(!needsApproval)
                .accountNonLocked(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        User savedAccount;
        Employee savedEmployee;
        try {
            savedAccount = userRepository.saveAndFlush(account);
            employeeRepository.lockEmployeeCodeSequence();
            savedEmployee = employeeRepository.saveAndFlush(Employee.builder()
                    .userId(savedAccount.getId())
                    .employeeCode(String.format(AppConstants.EMPLOYEE_CODE_FORMAT, employeeRepository.findHighestEmployeeCodeNumber() + 1))
                    .firstName(request.getFirstName().trim())
                    .middleName(OptionalText.blankToNull(request.getMiddleName()))
                    .lastName(request.getLastName().trim())
                    .displayName(displayName(request))
                    .dateOfBirth(request.getDateOfBirth())
                    .gender(request.getGender())
                    .designation(designation)
                    .department(designation.department())
                    .employmentType(request.getEmploymentType())
                    .joiningDate(request.getJoiningDate())
                    .salaryAmount(Objects.requireNonNullElse(request.getSalaryAmount(), BigDecimal.ZERO))
                    .salaryCurrency(Objects.requireNonNullElse(request.getSalaryCurrency(), AppConstants.DEFAULT_CURRENCY))
                    .phone(PhoneNumbers.normalize(request.getPhone()))
                    .alternatePhone(PhoneNumbers.normalize(request.getAlternatePhone()))
                    .personalEmail(OptionalText.lowerCaseOrNull(request.getPersonalEmail()))
                    .status(needsApproval ? EmployeeStatus.APPROVAL_PENDING : EmployeeStatus.FIRST_LOGIN)
                    .createdBy(creator.getId())
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        } catch (DataIntegrityViolationException exception) {
            // Another request took one of these values between our checks and the insert
            throw ApiErrors.conflict(ErrorCodes.DUPLICATE_VALUE,
                    "These details were just used for another employee. Review the form and try again.");
        }

        if (needsApproval) {
            notificationService.createForPermission(Permission.EMPLOYEE_APPROVE, creator.getId(), "Employee awaiting approval",
                    savedEmployee.getDisplayName() + " (" + savedEmployee.getEmployeeCode() + ", username " + savedAccount.getUsername()
                            + ", role " + role + ", " + designation.label() + ") was created by " + creator.getUsername()
                            + " and can't sign in until someone approves them.");
        }
        return toResponse(savedEmployee);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EmployeeResponse> getAllEmployees(EmployeeStatus status, String search, Pageable pageable) {
        String term = OptionalText.lowerCaseOrNull(search);
        Page<Employee> employees;
        if (status != null && term != null) {
            employees = employeeRepository.findByStatusAndSearchTerm(status, term, pageable);
        } else if (status != null) {
            employees = employeeRepository.findByStatus(status, pageable);
        } else if (term != null) {
            employees = employeeRepository.findBySearchTerm(term, pageable);
        } else {
            employees = employeeRepository.findAll(pageable);
        }
        Map<Long, User> users = usersFor(employees.getContent());
        return employees.map(employee -> employeeMapper.toResponse(employee, users));
    }

    @Override
    @Transactional(readOnly = true)
    public EmployeeResponse getEmployeeById(Long id) {
        return toResponse(findEmployee(id));
    }

    @Override
    @Transactional
    public EmployeeResponse updateEmployee(Long id, UpdateEmployeeRequest request) {
        Employee employee = employeeRepository.findByIdForUpdate(id)
                .orElseThrow(() -> employeeNotFound(id));

        List<ApiResponse.ApiErrorItem> invalid = new ArrayList<>();
        LocalDate joining = request.getJoiningDate() != null ? request.getJoiningDate() : employee.getJoiningDate();
        checkJoiningAfterAdulthood(request.getDateOfBirth() != null ? request.getDateOfBirth() : employee.getDateOfBirth(), joining, invalid);
        if (request.getLeavingDate() != null && joining != null && request.getLeavingDate().isBefore(joining)) {
            invalid.add(ApiErrors.error("leavingDate", "Leaving date cannot be before the joining date (" + joining + ")."));
        }
        checkStatusChange(employee.getStatus(), request.getStatus(), invalid);
        checkDesignationChange(employee, request, invalid);
        checkDepartmentMatches(request.getDepartment(),
                Designation.fromCode(request.getDesignation()).orElse(employee.getDesignation()), invalid);
        User account = userRepository.findById(employee.getUserId()).orElseThrow(() -> employeeNotFound(id));
        Role requestedRole = checkRoleChange(employee, account, request, invalid);
        ApiErrors.throwIfAny(invalid);

        List<ApiResponse.ApiErrorItem> duplicates = new ArrayList<>();
        contactValidator.checkContacts(employee.getId(),
                request.getPhone() != null ? request.getPhone() : employee.getPhone(),
                request.getAlternatePhone() != null ? request.getAlternatePhone() : employee.getAlternatePhone(),
                request.getPersonalEmail(), EmployeeContactValidator.Fields.EMPLOYEE, duplicates);
        contactValidator.throwIfAny(duplicates);

        applyUpdate(employee, request);
        // Level 2 only exists for designations with approval powers, so a move to one without them is Level 1
        Role targetRole = DesignationAccess.effectiveLevel(requestedRole != null ? requestedRole : account.getRole(),
                employee.getDesignation());
        if (targetRole != account.getRole()) {
            account.setRole(targetRole);
            userRepository.save(account);
        }
        employee.setUpdatedAt(LocalDateTime.now(clock));
        return toResponse(employeeRepository.save(employee));
    }

    @Override
    @Transactional
    public EmployeeResponse requestEmployeeDeletion(Long id) {
        Employee employee = findEmployee(id);
        if (employee.getStatus() == EmployeeStatus.WAITING_FOR_DELETION) {
            throw new BadRequestException("Employee is already waiting for deletion");
        }
        employee.setStatus(EmployeeStatus.WAITING_FOR_DELETION);
        employee.setUpdatedAt(LocalDateTime.now(clock));
        return toResponse(employeeRepository.save(employee));
    }

    @Override
    @Transactional
    public void finalizeDeleteEmployee(Long id) {
        Employee employee = findEmployee(id);
        if (employee.getStatus() != EmployeeStatus.WAITING_FOR_DELETION) {
            throw new BadRequestException("Employee status must be 'WAITING_FOR_DELETION' to finalize deletion");
        }

        // The profile first, then its login account
        Long userId = employee.getUserId();
        employeeRepository.delete(employee);
        if (userId != null && userRepository.existsById(userId)) {
            userRepository.deleteById(userId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmployeeResponse> getPendingApprovals() {
        List<Employee> pending = employeeRepository.findByStatusOrderByCreatedAtAscIdAsc(EmployeeStatus.APPROVAL_PENDING);
        Map<Long, User> users = usersFor(pending);
        return pending.stream().map(employee -> employeeMapper.toResponse(employee, users)).toList();
    }

    @Override
    @Transactional
    public EmployeeResponse decideApproval(Long id, EmployeeApprovalDecisionRequest request) {
        User reviewer = currentUserProvider.getCurrentUser();
        boolean approve = request.isApproved();
        String reason = request.getReason() == null ? null : request.getReason().trim();
        if (!approve && (reason == null || reason.isEmpty())) {
            throw ApiErrors.fieldError("reason", "A reason is required to reject an employee.");
        }

        Employee employee = employeeRepository.findByIdForUpdate(id)
                .orElseThrow(() -> employeeNotFound(id));
        if (employee.getStatus() != EmployeeStatus.APPROVAL_PENDING) {
            throw ApiErrors.conflict(ErrorCodes.EMPLOYEE_NOT_PENDING_APPROVAL, "Employee " + employee.getEmployeeCode()
                    + " is not waiting for approval (status " + employee.getStatus() + ").");
        }
        if (reviewer.getId().equals(employee.getCreatedBy()) || reviewer.getId().equals(employee.getUserId())) {
            throw ApiErrors.forbidden(ErrorCodes.EMPLOYEE_APPROVAL_SELF_DECISION,
                    "You created this employee, so another Admin L2 has to review them.");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        User account = userRepository.findById(employee.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Login account not found for employee " + employee.getEmployeeCode()));
        account.setEnabled(approve);
        account.setUpdatedAt(now);
        userRepository.save(account);

        employee.setStatus(approve
                ? (account.isFirstLogin() ? EmployeeStatus.FIRST_LOGIN : EmployeeStatus.ACTIVE)
                : EmployeeStatus.IN_ACTIVE);
        employee.setApprovalDecidedBy(reviewer.getId());
        employee.setApprovalDecidedAt(now);
        employee.setApprovalRejectionReason(approve ? null : reason);
        employee.setUpdatedAt(now);
        Employee saved = employeeRepository.save(employee);

        if (saved.getCreatedBy() != null) {
            String who = saved.getDisplayName() + " (" + saved.getEmployeeCode() + ")";
            notificationService.createForUser(saved.getCreatedBy(), approve ? "Employee approved" : "Employee rejected",
                    approve
                            ? who + " was approved by " + reviewer.getUsername() + " and can now sign in."
                            : who + " was rejected by " + reviewer.getUsername() + ". Reason: " + reason
                            + ". Their account has been deactivated.");
        }
        return toResponse(saved);
    }

    // Approval and deletion statuses are reached only through their own workflows
    private void checkStatusChange(EmployeeStatus current, EmployeeStatus requested, List<ApiResponse.ApiErrorItem> errors) {
        if (requested == null || requested == current) {
            return;
        }
        if (current == EmployeeStatus.APPROVAL_PENDING) {
            errors.add(ApiErrors.error("status",
                    "This employee is waiting for Admin L2 approval; their status changes only through the approval decision."));
        } else if (requested == EmployeeStatus.APPROVAL_PENDING || requested == EmployeeStatus.WAITING_FOR_DELETION) {
            errors.add(ApiErrors.error("status", "Status " + requested + " can't be set here; use the approval or deletion request instead."));
        }
    }

    // Only the fields that were sent change; a blank middle name removes it
    private void applyUpdate(Employee employee, UpdateEmployeeRequest request) {
        if (request.getFirstName() != null) employee.setFirstName(request.getFirstName());
        if (request.getMiddleName() != null) employee.setMiddleName(OptionalText.blankToNull(request.getMiddleName()));
        if (request.getLastName() != null) employee.setLastName(request.getLastName());
        if (request.getDisplayName() != null) employee.setDisplayName(request.getDisplayName());
        if (request.getDateOfBirth() != null) employee.setDateOfBirth(request.getDateOfBirth());
        if (request.getGender() != null) employee.setGender(request.getGender());
        if (request.getDesignation() != null) employee.setDesignation(Designation.fromCode(request.getDesignation()).orElseThrow());
        // The department always follows the designation
        if (employee.getDesignation() != null) employee.setDepartment(employee.getDesignation().department());
        if (request.getEmploymentType() != null) employee.setEmploymentType(request.getEmploymentType());
        if (request.getJoiningDate() != null) employee.setJoiningDate(request.getJoiningDate());
        if (request.getLeavingDate() != null) employee.setLeavingDate(request.getLeavingDate());
        if (request.getSalaryAmount() != null) employee.setSalaryAmount(request.getSalaryAmount());
        if (request.getSalaryCurrency() != null) employee.setSalaryCurrency(request.getSalaryCurrency());
        if (request.getPhone() != null) employee.setPhone(PhoneNumbers.normalize(request.getPhone()));
        if (request.getAlternatePhone() != null) employee.setAlternatePhone(PhoneNumbers.normalize(request.getAlternatePhone()));
        if (request.getPersonalEmail() != null) employee.setPersonalEmail(OptionalText.lowerCaseOrNull(request.getPersonalEmail()));
        if (request.getStatus() != null) employee.setStatus(request.getStatus());
    }

    // The designation decides access: nobody changes their own, and only an employee approver can make someone Super Admin
    private void checkDesignationChange(Employee employee, UpdateEmployeeRequest request, List<ApiResponse.ApiErrorItem> errors) {
        Designation requested = Designation.fromCode(request.getDesignation()).orElse(null);
        if (requested == null || requested == employee.getDesignation()) {
            return;
        }
        if (employee.getUserId().equals(currentUserProvider.getCurrentUserId())) {
            errors.add(ApiErrors.error("designation", "You can't change your own designation. Ask another administrator."));
        } else if (requested == Designation.SUPER_ADMIN && !currentAccess.has(Permission.EMPLOYEE_APPROVE)) {
            errors.add(ApiErrors.error("designation", "Only an administrator who can approve employees can assign the Super Admin designation."));
        }
    }

    // The level decides whether someone may approve, so only an employee approver changes it, never for themselves,
    // and only between the two admin levels. Returns the new level, or null when it doesn't change.
    private Role checkRoleChange(Employee employee, User account, UpdateEmployeeRequest request, List<ApiResponse.ApiErrorItem> errors) {
        if (OptionalText.blankToNull(request.getRole()) == null) {
            return null;
        }
        Role requested = Role.valueOf(request.getRole().trim().toUpperCase(Locale.ROOT));
        if (requested == account.getRole()) {
            return null;
        }
        if (account.getRole() != Role.ADMIN_L1 && account.getRole() != Role.ADMIN_L2) {
            errors.add(ApiErrors.error("role", "Only administrator accounts have an admin level."));
        } else if (employee.getUserId().equals(currentUserProvider.getCurrentUserId())) {
            errors.add(ApiErrors.error("role", "You can't change your own admin level. Ask another administrator."));
        } else if (!currentAccess.has(Permission.EMPLOYEE_APPROVE)) {
            errors.add(ApiErrors.error("role", "Only an administrator who can approve employees can change an admin level."));
        } else if (employee.getStatus() == EmployeeStatus.APPROVAL_PENDING) {
            errors.add(ApiErrors.error("role", "This employee is waiting for approval; decide on the account first."));
        }
        return requested;
    }

    // The department is derived from the designation; a client may send it, but only the matching value
    private static void checkDepartmentMatches(String requested, Designation designation, List<ApiResponse.ApiErrorItem> errors) {
        String department = OptionalText.blankToNull(requested);
        if (department != null && designation != null && !department.trim().equalsIgnoreCase(designation.department())) {
            errors.add(ApiErrors.error("department", "The department follows the designation: " + designation.label()
                    + " belongs to " + designation.department() + "."));
        }
    }

    // Nobody joins before turning 18
    private void checkJoiningAfterAdulthood(LocalDate dateOfBirth, LocalDate joiningDate, List<ApiResponse.ApiErrorItem> errors) {
        if (dateOfBirth == null || joiningDate == null) {
            return;
        }
        LocalDate adulthood = dateOfBirth.plusYears(AppConstants.MINIMUM_EMPLOYEE_AGE);
        if (joiningDate.isBefore(adulthood)) {
            errors.add(ApiErrors.error("joiningDate", "Joining date must be on or after the employee's 18th birthday (" + adulthood + ")."));
        }
    }

    // "First Middle Last" when no display name was given
    private static String displayName(CreateEmployeeRequest request) {
        String given = OptionalText.blankToNull(request.getDisplayName());
        if (given != null) {
            return given;
        }
        String middle = OptionalText.blankToNull(request.getMiddleName());
        return (request.getFirstName() + " " + (middle != null ? middle + " " : "") + request.getLastName()).trim();
    }

    private Employee findEmployee(Long id) {
        return employeeRepository.findById(id).orElseThrow(() -> employeeNotFound(id));
    }

    private static ResourceNotFoundException employeeNotFound(Long id) {
        return new ResourceNotFoundException("Employee not found with ID: " + id);
    }

    private EmployeeResponse toResponse(Employee employee) {
        return employeeMapper.toResponse(employee, usersFor(List.of(employee)));
    }

    // Every account the responses mention, loaded in one query
    private Map<Long, User> usersFor(Collection<Employee> employees) {
        List<Long> ids = employees.stream()
                .flatMap(employee -> Stream.of(employee.getUserId(), employee.getCreatedBy(), employee.getApprovalDecidedBy()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return ids.isEmpty() ? Map.of() : userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }
}
