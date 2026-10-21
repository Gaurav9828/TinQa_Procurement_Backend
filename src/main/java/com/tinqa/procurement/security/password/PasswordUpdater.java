package com.tinqa.procurement.security.password;

import com.tinqa.procurement.employee.enums.EmployeeStatus;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.security.audit.AuthAuditEventType;
import com.tinqa.procurement.security.audit.AuthAuditService;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.AuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Stores a password the user chose (change password, first login, or an emailed link) and everything that goes with
 * it: first login is over, every existing session is signed out, and the change is audited.
 */
@Component
@RequiredArgsConstructor
public class PasswordUpdater {

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthAuditService authAuditService;
    private final AuthenticationService authenticationService;
    private final Clock clock;

    /** Runs in the caller's transaction. */
    public void apply(User user, String newPassword, AuthAuditEventType eventType, HttpServletRequest httpRequest) {
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

        // Also revoke the exact token used for this request (none on an emailed link's page), even if issued in the same millisecond
        authenticationService.logout(httpRequest);
    }
}
