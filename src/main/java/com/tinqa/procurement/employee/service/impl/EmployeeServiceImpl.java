package com.tinqa.procurement.employee.service.impl;

import com.tinqa.procurement.approval.repository.EmployeeProfileChangeRequestRepository;
import com.tinqa.procurement.common.constant.AppConstants;
import com.tinqa.procurement.common.enums.ApprovalStatus;
import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.common.validation.PhoneNumbers;
import com.tinqa.procurement.common.util.SecureTokens;
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
import com.tinqa.procurement.notification.NotificationLinks;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.security.access.CurrentAccess;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.role.AssignedDesignation;
import com.tinqa.procurement.security.access.role.DesignationDirectory;
import com.tinqa.procurement.security.model.AuthClient;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.password.PasswordLinkService;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import com.tinqa.procurement.security.verification.EmailVerificationService;
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
import java.util.LinkedHashMap;
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
    private final EmailVerificationService emailVerificationService;
    private final PasswordLinkService passwordLinkService;
    private final EmployeeProfileChangeRequestRepository changeRequestRepository;
    private final DesignationDirectory designationDirectory;
    private final Clock clock;

    // employee_profile_change_request.status for a request dropped because its employee was deleted
    private static final String CHANGE_REQUEST_CANCELLED = "CANCELLED";

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
        contactValidator.checkPan(null, request.getPanNumber(), duplicates);
        contactValidator.throwIfAny(duplicates);

        // The personal email was proved with a code; if the creation fails later this is rolled back with it
        emailVerificationService.consume(request.getPersonalEmail(), request.getPersonalEmailVerificationToken(),
                "personalEmailVerificationToken");

        Role requestedRole = OptionalText.blankToNull(request.getRole()) == null
                ? Role.ADMIN_L1
                : Role.valueOf(request.getRole().trim().toUpperCase(Locale.ROOT));

        AssignedDesignation designation = designationDirectory.requireAssignable(request.getDesignation(), null);
        // Level 2 only exists for designations with approval powers
        Role role = designation.effectiveLevel(requestedRole);
        List<ApiResponse.ApiErrorItem> departmentErrors = new ArrayList<>();
        checkDepartmentMatches(request.getDepartment(), designation, departmentErrors);
        ApiErrors.throwIfAny(departmentErrors);

        // Maker-checker: an account waits for approval unless its creator can approve employees, and a new
        // ADMIN_L2 or Super Admin always needs a second approver, so nobody can create a powerful account alone
        boolean needsApproval = !currentAccess.has(Permission.EMPLOYEE_APPROVE)
                || role == Role.ADMIN_L2
                || designation.isSuperAdmin();
        LocalDateTime now = LocalDateTime.now(clock);

        User account = User.builder()
                .username(request.getUsername().trim())
                .email(request.getEmail().trim().toLowerCase(Locale.ROOT))
                // Nobody knows this password: the employee chooses theirs through the emailed set-password link
                .password(passwordEncoder.encode(SecureTokens.urlToken()))
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
            Employee employee = Employee.builder()
                    .userId(savedAccount.getId())
                    .employeeCode(String.format(AppConstants.EMPLOYEE_CODE_FORMAT, employeeRepository.findHighestEmployeeCodeNumber() + 1))
                    .firstName(request.getFirstName().trim())
                    .middleName(OptionalText.blankToNull(request.getMiddleName()))
                    .lastName(request.getLastName().trim())
                    .displayName(displayName(request))
                    .dateOfBirth(request.getDateOfBirth())
                    .gender(request.getGender())
                    .employmentType(request.getEmploymentType())
                    .joiningDate(request.getJoiningDate())
                    .salaryAmount(Objects.requireNonNullElse(request.getSalaryAmount(), BigDecimal.ZERO))
                    .salaryCurrency(Objects.requireNonNullElse(request.getSalaryCurrency(), AppConstants.DEFAULT_CURRENCY))
                    .phone(PhoneNumbers.normalize(request.getPhone()))
                    .alternatePhone(PhoneNumbers.normalize(request.getAlternatePhone()))
                    .personalEmail(OptionalText.lowerCaseOrNull(request.getPersonalEmail()))
                    .panNumber(request.getPanNumber().trim().toUpperCase(Locale.ROOT))
                    .houseNumber(request.getHouseNumber().trim())
                    .street(request.getStreet().trim())
                    .landmark(OptionalText.blankToNull(request.getLandmark()))
                    .district(request.getDistrict().trim())
                    .state(request.getState().trim())
                    .pincode(request.getPincode().trim())
                    .googleMapsUrl(OptionalText.blankToNull(request.getGoogleMapsUrl()))
                    .status(needsApproval ? EmployeeStatus.APPROVAL_PENDING : EmployeeStatus.FIRST_LOGIN)
                    .createdBy(creator.getId())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            designation.applyTo(employee);
            savedEmployee = employeeRepository.saveAndFlush(employee);
        } catch (DataIntegrityViolationException exception) {
            // Another request took one of these values between our checks and the insert
            throw ApiErrors.conflict(ErrorCodes.DUPLICATE_VALUE,
                    "These details were just used for another employee. Review the form and try again.");
        }

        if (!needsApproval) {
            passwordLinkService.sendSetupLinkAfterCommit(savedAccount, savedEmployee);
        } else {
            notificationService.createForPermission(Permission.EMPLOYEE_APPROVE, creator.getId(), "Employee awaiting approval",
                    savedEmployee.getDisplayName() + " (" + savedEmployee.getEmployeeCode() + ", username " + savedAccount.getUsername()
                            + ", role " + role + ", " + designation.label() + ") was created by " + creator.getUsername()
                            + " and can't sign in until someone approves them.", NotificationLinks.APPROVALS);
        }
        return toResponse(savedEmployee);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EmployeeResponse> getAllEmployees(EmployeeStatus status, String search, Pageable pageable) {
        if (status == EmployeeStatus.DELETED && !currentAccess.has(Permission.EMPLOYEE_DELETE)) {
            throw ApiErrors.forbidden(ErrorCodes.FORBIDDEN, "Only an administrator who can delete employees can view deleted employees.");
        }
        String term = OptionalText.lowerCaseOrNull(search);
        Page<Employee> employees;
        if (status != null && term != null) {
            employees = employeeRepository.findByStatusAndSearchTerm(status, term, pageable);
        } else if (status != null) {
            employees = employeeRepository.findByStatus(status, pageable);
        } else if (term != null) {
            // Deleted employees are only listed when that status is asked for
            employees = employeeRepository.findBySearchTermExcludingStatus(term, EmployeeStatus.DELETED, pageable);
        } else {
            employees = employeeRepository.findByStatusNot(EmployeeStatus.DELETED, pageable);
        }
        Map<Long, User> users = usersFor(employees.getContent());
        return employees.map(employee -> employeeMapper.toResponse(employee, users));
    }

    @Override
    @Transactional(readOnly = true)
    public EmployeeResponse getEmployeeById(Long id) {
        Employee employee = findEmployee(id);
        // A deleted record is visible only to those who can delete employees
        if (employee.getStatus() == EmployeeStatus.DELETED && !currentAccess.has(Permission.EMPLOYEE_DELETE)) {
            throw employeeNotFound(id);
        }
        return toResponse(employee);
    }

    @Override
    @Transactional
    public EmployeeResponse updateEmployee(Long id, UpdateEmployeeRequest request) {
        Employee employee = employeeRepository.findByIdForUpdate(id)
                .orElseThrow(() -> employeeNotFound(id));
        ensureNotDeleted(employee);

        List<ApiResponse.ApiErrorItem> invalid = new ArrayList<>();
        LocalDate joining = request.getJoiningDate() != null ? request.getJoiningDate() : employee.getJoiningDate();
        checkJoiningAfterAdulthood(request.getDateOfBirth() != null ? request.getDateOfBirth() : employee.getDateOfBirth(), joining, invalid);
        if (request.getLeavingDate() != null && joining != null && request.getLeavingDate().isBefore(joining)) {
            invalid.add(ApiErrors.error("leavingDate", "Leaving date cannot be before the joining date (" + joining + ")."));
        }
        checkStatusChange(employee.getStatus(), request.getStatus(), invalid);
        checkRequiredAddressKept(request, invalid);
        AssignedDesignation current = AssignedDesignation.of(employee);
        AssignedDesignation requestedDesignation = OptionalText.blankToNull(request.getDesignation()) == null ? null
                : designationDirectory.requireAssignable(request.getDesignation(), current);
        checkDesignationChange(employee, current, requestedDesignation, invalid);
        checkDepartmentMatches(request.getDepartment(), requestedDesignation != null ? requestedDesignation : current, invalid);
        User account = userRepository.findById(employee.getUserId()).orElseThrow(() -> employeeNotFound(id));
        Role requestedRole = checkRoleChange(employee, account, request, invalid);
        ApiErrors.throwIfAny(invalid);

        List<ApiResponse.ApiErrorItem> duplicates = new ArrayList<>();
        contactValidator.checkContacts(employee.getId(),
                request.getPhone() != null ? request.getPhone() : employee.getPhone(),
                request.getAlternatePhone() != null ? request.getAlternatePhone() : employee.getAlternatePhone(),
                request.getPersonalEmail(), EmployeeContactValidator.Fields.EMPLOYEE, duplicates);
        contactValidator.checkPan(employee.getId(), request.getPanNumber(), duplicates);
        contactValidator.throwIfAny(duplicates);

        applyUpdate(employee, request, requestedDesignation);
        // Level 2 only exists for designations with approval powers, so a move to one without them is Level 1
        AssignedDesignation held = AssignedDesignation.of(employee);
        Role requestedLevel = requestedRole != null ? requestedRole : account.getRole();
        Role targetRole = held == null ? requestedLevel : held.effectiveLevel(requestedLevel);
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
        ensureNotDeleted(employee);
        if (employee.getStatus() == EmployeeStatus.WAITING_FOR_DELETION) {
            throw new BadRequestException("Employee is already waiting for deletion");
        }
        employee.setStatus(EmployeeStatus.WAITING_FOR_DELETION);
        employee.setUpdatedAt(LocalDateTime.now(clock));
        return toResponse(employeeRepository.save(employee));
    }

    @Override
    @Transactional
    public EmployeeResponse finalizeDeleteEmployee(Long id) {
        User deleter = currentUserProvider.getCurrentUser();
        Employee employee = employeeRepository.findByIdForUpdate(id)
                .orElseThrow(() -> employeeNotFound(id));
        if (employee.getStatus() != EmployeeStatus.WAITING_FOR_DELETION) {
            throw new BadRequestException("Employee status must be 'WAITING_FOR_DELETION' to finalize deletion");
        }
        if (deleter.getId().equals(employee.getUserId())) {
            throw ApiErrors.forbidden(ErrorCodes.FORBIDDEN, "You can't delete your own employee record. Ask another administrator.");
        }

        // Soft delete: the record and its login stay for history, but the account can no longer sign in or use a token
        LocalDateTime now = LocalDateTime.now(clock);
        userRepository.findById(employee.getUserId()).ifPresent(account -> {
            account.setEnabled(false);
            account.setUpdatedAt(now);
            userRepository.save(account);
        });
        // Profile changes still waiting for review no longer apply
        changeRequestRepository.findByEmployeeIdAndStatus(employee.getId(), ApprovalStatus.PENDING.name())
                .forEach(changeRequest -> {
                    changeRequest.setStatus(CHANGE_REQUEST_CANCELLED);
                    changeRequest.setUpdatedAt(now);
                });

        employee.setStatus(EmployeeStatus.DELETED);
        employee.setDeletedBy(deleter.getId());
        employee.setDeletedAt(now);
        employee.setUpdatedAt(now);
        return toResponse(employeeRepository.save(employee));
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
        // The account can sign in from now on: the employee chooses their first password through an emailed link
        if (approve && account.isFirstLogin()) {
            passwordLinkService.sendSetupLinkAfterCommit(account, employee);
        }

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
                            ? who + " was approved by " + reviewer.getUsername() + " and has been emailed a link to set their password."
                            : who + " was rejected by " + reviewer.getUsername() + ". Reason: " + reason
                            + ". Their account has been deactivated.", NotificationLinks.EMPLOYEES);
        }
        return toResponse(saved);
    }

    // A deleted employee is kept for history only
    private static void ensureNotDeleted(Employee employee) {
        if (employee.getStatus() == EmployeeStatus.DELETED) {
            throw ApiErrors.conflict(ErrorCodes.EMPLOYEE_DELETED,
                    "Employee " + employee.getEmployeeCode() + " has been deleted; the record can no longer be changed.");
        }
    }

    // Approval and deletion statuses are reached only through their own workflows
    private void checkStatusChange(EmployeeStatus current, EmployeeStatus requested, List<ApiResponse.ApiErrorItem> errors) {
        if (requested == null || requested == current) {
            return;
        }
        if (current == EmployeeStatus.APPROVAL_PENDING) {
            errors.add(ApiErrors.error("status",
                    "This employee is waiting for Admin L2 approval; their status changes only through the approval decision."));
        } else if (requested == EmployeeStatus.APPROVAL_PENDING || requested == EmployeeStatus.WAITING_FOR_DELETION
                || requested == EmployeeStatus.DELETED) {
            errors.add(ApiErrors.error("status", "Status " + requested + " can't be set here; use the approval or deletion request instead."));
        }
    }

    // PAN and the main address lines can be changed but not cleared; landmark and map link are optional
    private static void checkRequiredAddressKept(UpdateEmployeeRequest request, List<ApiResponse.ApiErrorItem> errors) {
        Map<String, String> required = new LinkedHashMap<>();
        required.put("panNumber", request.getPanNumber());
        required.put("houseNumber", request.getHouseNumber());
        required.put("street", request.getStreet());
        required.put("district", request.getDistrict());
        required.put("state", request.getState());
        required.put("pincode", request.getPincode());
        required.forEach((field, value) -> {
            if (value != null && value.isBlank()) {
                errors.add(ApiErrors.error(field, "This field can't be left empty."));
            }
        });
    }

    // Only the fields that were sent change; a blank middle name, landmark or map link removes it
    private void applyUpdate(Employee employee, UpdateEmployeeRequest request, AssignedDesignation designation) {
        if (request.getFirstName() != null) employee.setFirstName(request.getFirstName());
        if (request.getMiddleName() != null) employee.setMiddleName(OptionalText.blankToNull(request.getMiddleName()));
        if (request.getLastName() != null) employee.setLastName(request.getLastName());
        if (request.getDisplayName() != null) employee.setDisplayName(request.getDisplayName());
        if (request.getDateOfBirth() != null) employee.setDateOfBirth(request.getDateOfBirth());
        if (request.getGender() != null) employee.setGender(request.getGender());
        // The department always follows the designation
        AssignedDesignation target = designation != null ? designation : AssignedDesignation.of(employee);
        if (target != null) target.applyTo(employee);
        if (request.getEmploymentType() != null) employee.setEmploymentType(request.getEmploymentType());
        if (request.getJoiningDate() != null) employee.setJoiningDate(request.getJoiningDate());
        if (request.getLeavingDate() != null) employee.setLeavingDate(request.getLeavingDate());
        if (request.getSalaryAmount() != null) employee.setSalaryAmount(request.getSalaryAmount());
        if (request.getSalaryCurrency() != null) employee.setSalaryCurrency(request.getSalaryCurrency());
        if (request.getPhone() != null) employee.setPhone(PhoneNumbers.normalize(request.getPhone()));
        if (request.getAlternatePhone() != null) employee.setAlternatePhone(PhoneNumbers.normalize(request.getAlternatePhone()));
        if (request.getPersonalEmail() != null) employee.setPersonalEmail(OptionalText.lowerCaseOrNull(request.getPersonalEmail()));
        if (request.getPanNumber() != null) employee.setPanNumber(request.getPanNumber().trim().toUpperCase(Locale.ROOT));
        if (request.getHouseNumber() != null) employee.setHouseNumber(request.getHouseNumber().trim());
        if (request.getStreet() != null) employee.setStreet(request.getStreet().trim());
        if (request.getLandmark() != null) employee.setLandmark(OptionalText.blankToNull(request.getLandmark()));
        if (request.getDistrict() != null) employee.setDistrict(request.getDistrict().trim());
        if (request.getState() != null) employee.setState(request.getState().trim());
        if (request.getPincode() != null) employee.setPincode(request.getPincode().trim());
        if (request.getGoogleMapsUrl() != null) employee.setGoogleMapsUrl(OptionalText.blankToNull(request.getGoogleMapsUrl()));
        if (request.getStatus() != null) employee.setStatus(request.getStatus());
    }

    // The designation decides access: nobody changes their own, and only an employee approver can make someone Super Admin
    private void checkDesignationChange(Employee employee, AssignedDesignation current, AssignedDesignation requested,
                                        List<ApiResponse.ApiErrorItem> errors) {
        if (requested == null || requested.sameAs(current)) {
            return;
        }
        if (employee.getUserId().equals(currentUserProvider.getCurrentUserId())) {
            errors.add(ApiErrors.error("designation", "You can't change your own designation. Ask another administrator."));
        } else if (requested.isSuperAdmin() && !currentAccess.has(Permission.EMPLOYEE_APPROVE)) {
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
    private static void checkDepartmentMatches(String requested, AssignedDesignation designation, List<ApiResponse.ApiErrorItem> errors) {
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
                .flatMap(employee -> Stream.of(employee.getUserId(), employee.getCreatedBy(), employee.getApprovalDecidedBy(),
                        employee.getDeletedBy()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return ids.isEmpty() ? Map.of() : userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }
}
