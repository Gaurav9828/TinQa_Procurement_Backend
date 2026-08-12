package com.tinqa.procurement.admin.service.impl;

import com.tinqa.procurement.admin.dto.AdminProfileResponse;
import com.tinqa.procurement.admin.dto.AdminProfileUpdateRequest;
import com.tinqa.procurement.admin.dto.ProfileUpdateResult;
import com.tinqa.procurement.admin.service.AdminProfileService;
import com.tinqa.procurement.approval.entity.EmployeeProfileChangeRequest;
import com.tinqa.procurement.approval.repository.EmployeeProfileChangeRequestRepository;
import com.tinqa.procurement.common.constant.ApprovalStatus;
import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.common.exception.ConflictException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AdminProfileServiceImpl implements AdminProfileService {

    private static final String REQUEST_TYPE_PROFILE_UPDATE =
            "EMPLOYEE_PROFILE_UPDATE";

    /*
     * Approval-controlled fields.
     *
     * Admin L1 cannot directly modify these fields.
     * Admin L2 can modify them directly.
     */
    private static final String FIELD_DATE_OF_BIRTH = "dateOfBirth";
    private static final String FIELD_FIRST_NAME = "firstName";
    private static final String FIELD_MIDDLE_NAME = "middleName";
    private static final String FIELD_LAST_NAME = "lastName";
    private static final String FIELD_DEPARTMENT = "department";
    private static final String FIELD_DESIGNATION = "designation";
    private static final String FIELD_GENDER = "gender";
    private final EmployeeRepository employeeRepository;
    private final EmployeeProfileChangeRequestRepository employeeProfileChangeRequestRepository;
    private final CurrentUserProvider currentUserProvider;
    private final UserRepository userRepository;


    // ============================================================
    // GET PROFILE
    // ============================================================

    @Override
    @Transactional(readOnly = true)
    public AdminProfileResponse getProfile() {
        User currentUser = getCurrentUser();
        validateAdmin(currentUser);
        Employee employee = getEmployee(currentUser);
        return buildProfileResponse(employee);
    }

    // ============================================================
    // UPDATE PROFILE
    // ============================================================

    @Override
    @Transactional
    public AdminProfileResponse updateProfile(AdminProfileUpdateRequest request) {
        return updateProfileWithResult(request).getProfile();
    }

    // ============================================================
    // UPDATE PROFILE WITH RESULT
    // ============================================================

    @Override
    @Transactional
    public ProfileUpdateResult updateProfileWithResult(AdminProfileUpdateRequest request) {
        User currentUser = currentUserProvider.getCurrentUser();
        validateAdmin(currentUser);
        Employee employee = employeeRepository.findByUserId(currentUser.getId())
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Employee profile not found"
                                ));

        boolean approvalRequired = !isAdminL2(currentUser) && hasApprovalChanges(employee, request);
        if(!approvalRequired){
            updateAllEditableFields(employee, request);
        }else{
            updateDirectlyEditableFields(employee, request);
        }

        /*
         * L1 only:
         * approval-controlled fields are NOT written
         * to employee yet.
         */
        if (approvalRequired) {
            createProfileChangeRequest(employee, currentUser, request);
        }
        employee.setUpdatedAt(LocalDateTime.now());
        employeeRepository.save(employee);
        return ProfileUpdateResult.builder()
                .profile(buildProfileResponse(employee))
                .approvalRequired(approvalRequired)
                .build();
    }

    private boolean hasApprovalChanges(Employee employee, AdminProfileUpdateRequest request) {

        return (Objects.nonNull(request.getDateOfBirth()) && !Objects.equals(employee.getDateOfBirth(), request.getDateOfBirth()))
                || (Objects.nonNull(request.getFirstName()) && !Objects.equals(employee.getFirstName(), request.getFirstName()))
                || (Objects.nonNull(request.getMiddleName()) && !Objects.equals(employee.getMiddleName(), request.getMiddleName()))
                || (Objects.nonNull(request.getLastName()) && !Objects.equals(employee.getLastName(), request.getLastName()))
                || (Objects.nonNull(request.getDepartment()) && !Objects.equals(employee.getDepartment(), request.getDepartment()))
                || (Objects.nonNull(request.getDesignation()) && !Objects.equals(employee.getDesignation(), request.getDesignation()))
                || (Objects.nonNull(request.getGender()) && !Objects.equals(employee.getGender(), request.getGender()));
    }

    // ============================================================
    // CHECK APPROVAL CHANGES
    // ============================================================

    @Override
    public boolean hasApprovalChangesInCurrentRequest(Employee employee, AdminProfileUpdateRequest request) {
        return hasChanged(employee.getDateOfBirth(), request.getDateOfBirth())
                || hasChanged(employee.getFirstName(), request.getFirstName())
                || hasChanged(employee.getMiddleName(), request.getMiddleName())
                || hasChanged(employee.getLastName(), request.getLastName())
                || hasChanged(employee.getDepartment(), request.getDepartment())
                || hasChanged(employee.getDesignation(), request.getDesignation())
                || hasChanged(employee.getGender(), request.getGender());
    }


    // ============================================================
    // GENERIC CHANGE CHECK
    // ============================================================

    private boolean hasChanged(Object currentValue, Object requestedValue) {
        if (requestedValue == null) {return false;}

        return !Objects.equals(
                currentValue,
                requestedValue
        );
    }


    // ============================================================
    // DIRECTLY EDITABLE FIELDS
    // ============================================================


    private void updateDirectlyEditableFields(Employee employee, AdminProfileUpdateRequest request) {
        if (Objects.nonNull(request.getDisplayName()) && !Objects.equals(employee.getDisplayName(), request.getDisplayName())) {
            employee.setDisplayName(request.getDisplayName());
        }
        if (Objects.nonNull(request.getPrimaryPhone()) && !Objects.equals(employee.getPhone(), request.getPrimaryPhone())) {
            employee.setPhone(request.getPrimaryPhone());
        }
        if (Objects.nonNull(request.getAlternatePhone()) && !Objects.equals(employee.getAlternatePhone(), request.getAlternatePhone())) {
            employee.setAlternatePhone(request.getAlternatePhone());
        }
        if (Objects.nonNull(request.getPersonalEmail()) && !Objects.equals(employee.getPersonalEmail(), request.getPersonalEmail())) {
            employee.setPersonalEmail(request.getPersonalEmail());
        }
    }


    // ============================================================
    // ADMIN L2 - ALL EDITABLE FIELDS
    // ============================================================

    private void updateAllEditableFields(Employee employee, AdminProfileUpdateRequest request) {
        updateDirectlyEditableFields(employee, request);
        if (Objects.nonNull(request.getDateOfBirth())) {
            employee.setDateOfBirth(request.getDateOfBirth());
        }
        if (Objects.nonNull(request.getFirstName())) {
            employee.setFirstName(request.getFirstName());
        }
        if (Objects.nonNull(request.getMiddleName())) {
            employee.setMiddleName(request.getMiddleName());
        }
        if (Objects.nonNull(request.getLastName())) {
            employee.setLastName(request.getLastName());
        }
        if (Objects.nonNull(request.getDepartment())) {
            employee.setDepartment(request.getDepartment());
        }
        if (Objects.nonNull(request.getDesignation())) {
            employee.setDesignation(request.getDesignation());
        }
        if (Objects.nonNull(request.getGender())) {
            employee.setGender(request.getGender());
        }
    }


    // ============================================================
    // BUILD REQUESTED APPROVAL VALUES
    // ============================================================

    private Map<String, Object> buildRequestedApprovalValues(Employee employee, AdminProfileUpdateRequest request) {

        Map<String, Object> changes =
                new LinkedHashMap<>();


        /*
         * Date of Birth
         */

        if (hasChanged(
                employee.getDateOfBirth(),
                request.getDateOfBirth())) {

            changes.put(
                    FIELD_DATE_OF_BIRTH,
                    request.getDateOfBirth()
            );
        }


        /*
         * First Name
         */

        if (hasChanged(
                employee.getFirstName(),
                request.getFirstName())) {

            changes.put(
                    FIELD_FIRST_NAME,
                    request.getFirstName()
            );
        }


        /*
         * Middle Name
         */

        if (hasChanged(
                employee.getMiddleName(),
                request.getMiddleName())) {

            changes.put(
                    FIELD_MIDDLE_NAME,
                    request.getMiddleName()
            );
        }


        /*
         * Last Name
         */

        if (hasChanged(
                employee.getLastName(),
                request.getLastName())) {

            changes.put(
                    FIELD_LAST_NAME,
                    request.getLastName()
            );
        }


        /*
         * Department
         */

        if (hasChanged(
                employee.getDepartment(),
                request.getDepartment())) {

            changes.put(
                    FIELD_DEPARTMENT,
                    request.getDepartment()
            );
        }


        /*
         * Designation
         */

        if (hasChanged(
                employee.getDesignation(),
                request.getDesignation())) {

            changes.put(
                    FIELD_DESIGNATION,
                    request.getDesignation()
            );
        }


        /*
         * Gender
         */

        if (hasChanged(
                employee.getGender(),
                request.getGender())) {

            changes.put(
                    FIELD_GENDER,
                    request.getGender()
            );
        }


        return changes;
    }


    // ============================================================
    // CREATE PROFILE CHANGE REQUEST
    // ============================================================

    private void createProfileChangeRequest(Employee employee, User currentUser, AdminProfileUpdateRequest request) {
        boolean pendingRequestExists =
                employeeProfileChangeRequestRepository
                        .existsByEmployeeIdAndStatus(
                                employee.getId(),
                                ApprovalStatus.PENDING
                        );

        if (pendingRequestExists) {throw new ConflictException("A profile change request is already pending for this employee");}

        EmployeeProfileChangeRequest changeRequest =
                EmployeeProfileChangeRequest.builder()
                        .employeeId(employee.getId())
                        .requestedBy(currentUser.getId())
                        .status(ApprovalStatus.PENDING)

                        /*
                         * Only approval-controlled fields are stored here.
                         */
                        .dateOfBirth(request.getDateOfBirth())
                        .firstName(request.getFirstName())
                        .middleName(request.getMiddleName())
                        .lastName(request.getLastName())
                        .department(request.getDepartment())
                        .designation(request.getDesignation())
                        .gender(request.getGender())

                        .requestedAt(LocalDateTime.now())
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();

        employeeProfileChangeRequestRepository.save(changeRequest);
    }


    // ============================================================
    // PENDING REQUEST CHECK
    // ============================================================

    private void ensureNoPendingApprovalRequest(Long employeeId) {

        boolean pending =
                employeeProfileChangeRequestRepository
                        .existsByEmployeeIdAndStatus(
                                employeeId,
                                ApprovalStatus.PENDING
                        );


        if (pending) {

            throw new ConflictException(
                    "A profile change request is already pending approval"
            );
        }
    }


    // ============================================================
    // BUILD PROFILE RESPONSE
    // ============================================================

    private AdminProfileResponse buildProfileResponse(Employee employee) {

        User user = userRepository.findById(employee.getId())
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "User account not found"
                        ));

        Set<String> pendingFields =
                getPendingApprovalFields(employee.getId());

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
                .designation(employee.getDesignation())
                .workEmail(user.getEmail())
                .username(user.getUsername())
                .gender(employee.getGender())
                .employmentType(employee.getEmploymentType())
                .joiningDate(employee.getJoiningDate())
                .dateOfBirth(employee.getDateOfBirth())
                .status(employee.getStatus())
                .approvalPending(!pendingFields.isEmpty())
                .pendingApprovalFields(pendingFields)
                .build();
    }


    // ============================================================
    // CURRENT USER
    // ============================================================

    private User getCurrentUser() {

        return currentUserProvider.getCurrentUser();
    }


    // ============================================================
    // EMPLOYEE
    // ============================================================

    private Employee getEmployee(
            User currentUser) {

        return employeeRepository
                .findByUserId(
                        currentUser.getId()
                )
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Employee profile not found"
                        )
                );
    }


    // ============================================================
    // ADMIN VALIDATION
    // ============================================================

    private void validateAdmin(User user) {

        if (user == null) {
            throw new BadRequestException(
                    "Authenticated user not found"
            );
        }

        if (user.getRole() != Role.ADMIN_L1
                && user.getRole() != Role.ADMIN_L2) {

            throw new BadRequestException(
                    "Only administrators can update admin profile"
            );
        }

        if (!user.isEnabled()) {
            throw new BadRequestException(
                    "User account is disabled"
            );
        }

        if (!user.isAccountNonLocked()) {
            throw new BadRequestException(
                    "User account is locked"
            );
        }
    }


    // ============================================================
    // ADMIN LEVEL
    // ============================================================


    private boolean isAdminL2(User user) {
        return user.getRole() == Role.ADMIN_L2;
    }


    private void validateRequest(
            AdminProfileUpdateRequest request) {

        if (request == null) {

            throw new BadRequestException(
                    "Profile update request cannot be null"
            );
        }
    }


    // ============================================================
    // VALUE HELPERS
    // ============================================================

    private String getStringValue(
            Map<String, Object> values,
            String field) {

        Object value =
                values.get(field);

        if (value == null) {
            return null;
        }

        return value.toString();
    }


    private Set<String> getPendingApprovalFields(Long employeeId) {

        Optional<EmployeeProfileChangeRequest> pendingRequest =
                employeeProfileChangeRequestRepository
                        .findFirstByEmployeeIdAndStatusOrderByRequestedAtDesc(
                                employeeId,
                                ApprovalStatus.PENDING
                        );

        if (pendingRequest.isEmpty()) {
            return Collections.emptySet();
        }

        EmployeeProfileChangeRequest request =
                pendingRequest.get();

        Employee employee =
                employeeRepository.findById(employeeId)
                        .orElseThrow(() -> new ResourceNotFoundException("Employee profile not found"));

        Set<String> fields = new HashSet<>();

        if (Objects.nonNull(request.getDateOfBirth()) && !Objects.equals(employee.getDateOfBirth(), request.getDateOfBirth())) {
            fields.add("dateOfBirth");
        }

        if (Objects.nonNull(request.getFirstName()) && !Objects.equals(employee.getFirstName(), request.getFirstName())) {
            fields.add("firstName");
        }

        if (Objects.nonNull(request.getMiddleName()) && !Objects.equals(employee.getMiddleName(), request.getMiddleName())) {
            fields.add("middleName");
        }

        if (Objects.nonNull(request.getLastName()) && !Objects.equals(employee.getLastName(), request.getLastName())) {
            fields.add("lastName");
        }

        if (Objects.nonNull(request.getDepartment()) && !Objects.equals(employee.getDepartment(), request.getDepartment())) {
            fields.add("department");
        }

        if (Objects.nonNull(request.getDesignation()) && !Objects.equals(employee.getDesignation(), request.getDesignation())) {
            fields.add("designation");
        }

        if (Objects.nonNull(request.getGender()) && !Objects.equals(employee.getGender(), request.getGender())) {
            fields.add("gender");
        }

        return fields;
    }
}