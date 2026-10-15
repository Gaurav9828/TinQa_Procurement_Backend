package com.tinqa.procurement.security.service.impl;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.employee.enums.EmployeeStatus;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.security.audit.AuthAuditEventType;
import com.tinqa.procurement.security.audit.AuthAuditService;
import com.tinqa.procurement.security.dto.ChangePasswordRequest;
import com.tinqa.procurement.security.dto.FirstLoginPasswordRequest;
import com.tinqa.procurement.security.dto.PasswordChangeResponse;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.password.PasswordAttemptLimiter;
import com.tinqa.procurement.security.password.PasswordPolicy;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.AuthenticationService;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import com.tinqa.procurement.security.service.PasswordService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class PasswordServiceImpl implements PasswordService {

    private final CurrentUserProvider currentUserProvider;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordAttemptLimiter passwordAttemptLimiter;
    private final AuthAuditService authAuditService;
    private final AuthenticationService authenticationService;
    private final Clock clock;

    @Override
    @Transactional
    public PasswordChangeResponse changePassword(ChangePasswordRequest request, HttpServletRequest httpRequest) {
        // The user is the one the JWT filter authenticated, never a username from the request
        User user = currentUserProvider.getCurrentUser();

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new BadRequestException("Current password is incorrect");
        }
        validateNewPassword(request.getNewPassword(), request.getConfirmNewPassword());
        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            throw new BadRequestException("New password must be different from the current password");
        }

        // A FIRST_LOGIN user may finish first login this way too
        applyNewPassword(user, request.getNewPassword(), AuthAuditEventType.PASSWORD_CHANGED, httpRequest);

        return PasswordChangeResponse.builder()
                .success(true)
                .message("Password updated successfully. Please login again.")
                .code(ErrorCodes.PASSWORD_CHANGED)
                .requiresLogin(true)
                .build();
    }

    @Override
    @Transactional
    public PasswordChangeResponse setFirstLoginPassword(FirstLoginPasswordRequest request, HttpServletRequest httpRequest) {
        // Status comes from the database, not the token
        User user = currentUserProvider.getCurrentUser();
        passwordAttemptLimiter.recordAttempt(user.getId());

        if (!user.isFirstLogin()) {
            throw ApiErrors.conflict(ErrorCodes.FIRST_LOGIN_NOT_REQUIRED, "Your password has already been set.");
        }
        validateNewPassword(request.getNewPassword(), request.getConfirmNewPassword());
        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            throw ApiErrors.of(HttpStatus.BAD_REQUEST, ErrorCodes.PASSWORD_REUSED,
                    "New password must be different from your temporary password.");
        }

        applyNewPassword(user, request.getNewPassword(), AuthAuditEventType.FIRST_LOGIN_PASSWORD_SET, httpRequest);

        return PasswordChangeResponse.builder()
                .success(true)
                .message("Password set. Sign in with your new password.")
                .requiresLogin(true)
                .build();
    }

    // The value is checked exactly as sent: a password is never trimmed
    private void validateNewPassword(String newPassword, String confirmNewPassword) {
        if (!PasswordPolicy.isSatisfiedBy(newPassword)) {
            throw ApiErrors.fieldError("newPassword", PasswordPolicy.MESSAGE);
        }
        if (!newPassword.equals(confirmNewPassword)) {
            throw ApiErrors.fieldError("confirmNewPassword", "New password and confirm password do not match.");
        }
    }

    private void applyNewPassword(User user, String newPassword, AuthAuditEventType eventType, HttpServletRequest httpRequest) {
        Instant now = clock.instant();

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setFirstLogin(false);
        // The JWT filter rejects every token issued before this, which signs out all other sessions
        user.setPasswordChangedAt(now);
        user.setUpdatedAt(LocalDateTime.now(clock));
        userRepository.save(user);

        employeeRepository.findByUserId(user.getId())
                .filter(employee -> employee.getStatus() == EmployeeStatus.FIRST_LOGIN)
                .ifPresent(employee -> {
                    employee.setStatus(EmployeeStatus.ACTIVE);
                    employee.setUpdatedAt(LocalDateTime.now(clock));
                    employeeRepository.save(employee);
                });

        authAuditService.record(user, eventType, httpRequest, now);

        // JWT issued-at has second precision, so a token issued in the same second as the change would survive the
        // check above: revoke the exact token used for this request as well
        authenticationService.logout(httpRequest);
    }
}
