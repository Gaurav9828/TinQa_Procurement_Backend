package com.tinqa.procurement.approval.service.impl;

import com.tinqa.procurement.approval.dto.EmployeeProfileApprovalRequest;
import com.tinqa.procurement.approval.dto.EmployeeProfileApprovalResponse;
import com.tinqa.procurement.approval.entity.EmployeeProfileChangeRequest;
import com.tinqa.procurement.approval.profile.ApprovalControlledFields;
import com.tinqa.procurement.approval.repository.EmployeeProfileChangeRequestRepository;
import com.tinqa.procurement.approval.service.EmployeeProfileApprovalService;
import com.tinqa.procurement.common.enums.ApprovalStatus;
import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.common.exception.ConflictException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.notification.NotificationLinks;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.security.access.CurrentAccess;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.AccountGuard;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmployeeProfileApprovalServiceImpl implements EmployeeProfileApprovalService {

    private static final String PENDING = ApprovalStatus.PENDING.name();

    private final EmployeeProfileChangeRequestRepository changeRequestRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final CurrentAccess currentAccess;
    private final NotificationService notificationService;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<EmployeeProfileApprovalResponse> getPendingRequests() {
        currentAdminL2();
        return toResponses(changeRequestRepository.findByStatusOrderByRequestedAtDesc(PENDING));
    }

    @Override
    @Transactional(readOnly = true)
    public EmployeeProfileApprovalResponse getPendingRequest(Long requestId) {
        currentAdminL2();
        EmployeeProfileChangeRequest request = changeRequestRepository.findById(requestId)
                .orElseThrow(EmployeeProfileApprovalServiceImpl::changeRequestNotFound);
        ensurePending(request);
        return toResponses(List.of(request)).getFirst();
    }

    @Override
    @Transactional
    public EmployeeProfileApprovalResponse processRequest(Long requestId, EmployeeProfileApprovalRequest decision) {
        User reviewer = currentAdminL2();
        boolean approve = decision.isApproved();
        String rejectionReason = OptionalText.blankToNull(decision.getRejectionReason());
        if (!approve && rejectionReason == null) {
            throw new BadRequestException("Rejection reason is required");
        }

        // Row locks: of two reviewers deciding at once, the second sees the first one's decision
        EmployeeProfileChangeRequest changeRequest = changeRequestRepository.findByIdForUpdate(requestId)
                .orElseThrow(EmployeeProfileApprovalServiceImpl::changeRequestNotFound);
        ensurePending(changeRequest);
        Employee employee = employeeRepository.findByIdForUpdate(changeRequest.getEmployeeId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee profile not found"));

        LocalDateTime now = LocalDateTime.now(clock);
        String title;
        String message;
        if (approve) {
            ApprovalControlledFields.apply(employee, changeRequest);
            changeRequest.setStatus(ApprovalStatus.APPROVED.name());
            title = "Profile Change Request Approved";
            message = "Your profile change request has been approved by " + reviewer.getUsername() + ".";
        } else {
            changeRequest.setStatus(ApprovalStatus.REJECTED.name());
            changeRequest.setRejectionReason(decision.getRejectionReason());
            title = "Profile Change Request Rejected";
            message = "Your profile change request has been rejected. Reason: " + decision.getRejectionReason();
        }
        notificationService.createForUser(changeRequest.getRequestedBy(), title, message, NotificationLinks.PROFILE);

        changeRequest.setApprovedBy(reviewer.getId());
        changeRequest.setReviewedAt(now);
        changeRequest.setUpdatedAt(now);
        employee.setUpdatedAt(now);
        employeeRepository.save(employee);
        changeRequestRepository.save(changeRequest);
        return toResponses(List.of(changeRequest)).getFirst();
    }

    private User currentAdminL2() {
        User user = currentUserProvider.getCurrentUser();
        currentAccess.require(Permission.EMPLOYEE_PROFILE_CHANGE_APPROVE, "process profile approvals");
        AccountGuard.requireActive(user);
        return user;
    }

    private static void ensurePending(EmployeeProfileChangeRequest request) {
        if (!PENDING.equalsIgnoreCase(request.getStatus())) {
            throw new ConflictException("Profile change request has already been processed");
        }
    }

    // Requester usernames and employee codes for all requests, loaded in two queries
    private List<EmployeeProfileApprovalResponse> toResponses(List<EmployeeProfileChangeRequest> requests) {
        Map<Long, String> usernames = userRepository.findAllById(requests.stream()
                        .map(EmployeeProfileChangeRequest::getRequestedBy).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));
        Map<Long, Employee> employees = employeeRepository.findAllById(requests.stream()
                        .map(EmployeeProfileChangeRequest::getEmployeeId).distinct().toList()).stream()
                .collect(Collectors.toMap(Employee::getId, Function.identity()));

        return requests.stream()
                .map(request -> EmployeeProfileApprovalResponse.builder()
                        .requestId(request.getId())
                        .employeeId(request.getEmployeeId())
                        .employeeCode(Optional.ofNullable(employees.get(request.getEmployeeId()))
                                .map(Employee::getEmployeeCode).orElse(null))
                        .status(request.getStatus())
                        .requestedBy(request.getRequestedBy())
                        .requestedByUsername(usernames.get(request.getRequestedBy()))
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
                        .build())
                .toList();
    }

    private static ResourceNotFoundException changeRequestNotFound() {
        return new ResourceNotFoundException("Profile change request not found");
    }
}
