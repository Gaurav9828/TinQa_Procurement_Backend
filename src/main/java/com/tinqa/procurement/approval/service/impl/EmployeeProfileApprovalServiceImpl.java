package com.tinqa.procurement.approval.service.impl;

import com.tinqa.procurement.approval.dto.EmployeeProfileApprovalRequest;
import com.tinqa.procurement.approval.dto.EmployeeProfileApprovalResponse;
import com.tinqa.procurement.approval.entity.EmployeeProfileChangeRequest;
import com.tinqa.procurement.approval.repository.EmployeeProfileChangeRequestRepository;
import com.tinqa.procurement.approval.service.EmployeeProfileApprovalService;
import com.tinqa.procurement.common.constant.ApprovalStatus;
import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.common.exception.ConflictException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.notification.dto.NotificationResponse;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.CurrentUserProvider;

import jakarta.transaction.Transactional;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class EmployeeProfileApprovalServiceImpl
        implements EmployeeProfileApprovalService {

    private final EmployeeProfileChangeRequestRepository
            changeRequestRepository;

    private final EmployeeRepository employeeRepository;

    private final UserRepository userRepository;

    private final CurrentUserProvider currentUserProvider;

    private final NotificationService notificationService;

    String title;
    String message;

    @Override
    public List<EmployeeProfileApprovalResponse> getPendingRequests() {

        validateAdminL2();

        return changeRequestRepository
                .findByStatusOrderByRequestedAtDesc(
                        ApprovalStatus.PENDING
                )
                .stream()
                .map(this::buildResponse)
                .toList();
    }

    @Override
    public EmployeeProfileApprovalResponse getPendingRequest(
            Long requestId) {

        validateAdminL2();

        EmployeeProfileChangeRequest request =
                findPendingRequest(requestId);

        return buildResponse(request);
    }

    @Override
    @Transactional
    public EmployeeProfileApprovalResponse processRequest(
            Long requestId,
            EmployeeProfileApprovalRequest approvalRequest) {

        User currentUser = validateAdminL2();

        EmployeeProfileChangeRequest changeRequest =
                findPendingRequest(requestId);

        if ("REJECT".equals(approvalRequest.getDecision())
                && (approvalRequest.getRejectionReason() == null
                || approvalRequest.getRejectionReason().isBlank())) {

            throw new BadRequestException(
                    "Rejection reason is required"
            );
        }

        Employee employee =
                employeeRepository.findByIdForUpdate(
                        changeRequest.getEmployeeId()
                ).orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Employee profile not found"
                        ));

        if ("APPROVE".equals(approvalRequest.getDecision())) {

            applyApprovedChanges(
                    employee,
                    changeRequest
            );

            changeRequest.setStatus(
                    ApprovalStatus.APPROVED
            );

            title = "Profile Change Request Approved";
            message = "Your profile change request has been approved by " + currentUser.getUsername() + ".";

        } else {

            changeRequest.setStatus(
                    ApprovalStatus.REJECTED
            );

            changeRequest.setRejectionReason(
                    approvalRequest.getRejectionReason()
            );

            title = "Profile Change Request Rejected";
            message = "Your profile change request has been rejected. Reason: "
                    + approvalRequest.getRejectionReason();
        }

        NotificationResponse notificationResponse = notificationService.createForUser(
                changeRequest.getRequestedBy(),
                title,
                message
        );

        changeRequest.setApprovedBy(
                currentUser.getId()
        );

        changeRequest.setReviewedAt(
                LocalDateTime.now()
        );

        changeRequest.setUpdatedAt(
                LocalDateTime.now()
        );

        employee.setUpdatedAt(
                LocalDateTime.now()
        );

        if ("APPROVE".equals(approvalRequest.getDecision())) {
            employeeRepository.save(employee);
        }

        changeRequestRepository.save(changeRequest);

        return buildResponse(changeRequest);
    }

    private void applyApprovedChanges(
            Employee employee,
            EmployeeProfileChangeRequest request) {

        if (Objects.nonNull(request.getDateOfBirth()) && !Objects.equals(employee.getDateOfBirth(), request.getDateOfBirth())) {
            employee.setDateOfBirth(request.getDateOfBirth());
        }

        if (Objects.nonNull(request.getFirstName()) && !Objects.equals(employee.getFirstName(), request.getFirstName())) {
            employee.setFirstName(request.getFirstName());
        }

        if (Objects.nonNull(request.getMiddleName()) && !Objects.equals(employee.getMiddleName(), request.getMiddleName())) {
            employee.setMiddleName(request.getMiddleName());
        }

        if (Objects.nonNull(request.getLastName()) && !Objects.equals(employee.getLastName(), request.getLastName())) {
            employee.setLastName(request.getLastName());
        }

        if (Objects.nonNull(request.getDepartment()) && !Objects.equals(employee.getDepartment(), request.getDepartment())) {
            employee.setDepartment(request.getDepartment());
        }

        if (Objects.nonNull(request.getDesignation()) && !Objects.equals(employee.getDesignation(), request.getDesignation())) {
            employee.setDesignation(request.getDesignation());
        }

        if (Objects.nonNull(request.getGender()) && !Objects.equals(employee.getGender(), request.getGender())) {
            employee.setGender(request.getGender());
        }
    }

    private EmployeeProfileChangeRequest findPendingRequest(
            Long requestId) {

        EmployeeProfileChangeRequest request =
                changeRequestRepository
                        .findByIdForUpdate(requestId)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Profile change request not found"
                                ));

        if (!ApprovalStatus.PENDING.equals(request.getStatus())) {

            throw new ConflictException(
                    "Profile change request has already been processed"
            );
        }

        return request;
    }

    private User validateAdminL2() {

        User user =
                currentUserProvider.getCurrentUser();

        if (user.getRole() != Role.ADMIN_L2) {

            throw new BadRequestException(
                    "Only ADMIN_L2 can process profile approvals"
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

        return user;
    }

    private EmployeeProfileApprovalResponse buildResponse(
            EmployeeProfileChangeRequest request) {

        String requestedByUsername =
                userRepository.findById(request.getRequestedBy())
                        .map(User::getUsername)
                        .orElse(null);

        Employee employee =
                employeeRepository.findById(
                        request.getEmployeeId()
                ).orElse(null);

        return EmployeeProfileApprovalResponse.builder()
                .requestId(request.getId())
                .employeeId(request.getEmployeeId())
                .employeeCode(
                        employee != null
                                ? employee.getEmployeeCode()
                                : null
                )
                .status(request.getStatus())
                .requestedBy(request.getRequestedBy())
                .requestedByUsername(requestedByUsername)
                .requestedAt(request.getRequestedAt())
                .displayName(request.getDisplayName())
                .primaryPhone(request.getPrimaryPhone())
                .alternatePhone(request.getAlternatePhone())
                .personalEmail(request.getPersonalEmail())
                .dateOfBirth(request.getDateOfBirth())
                .firstName(request.getFirstName())
                .middleName(request.getMiddleName())
                .lastName(request.getLastName())
                .department(request.getDepartment())
                .designation(request.getDesignation())
                .gender(request.getGender())
                .build();
    }
}