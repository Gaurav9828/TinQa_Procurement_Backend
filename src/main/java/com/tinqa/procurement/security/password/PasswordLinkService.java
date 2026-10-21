package com.tinqa.procurement.security.password;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.util.SecureTokens;
import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.mail.StaffMailErrors;
import com.tinqa.procurement.mail.StaffMailException;
import com.tinqa.procurement.mail.StaffMailer;
import com.tinqa.procurement.notification.NotificationLinks;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.security.audit.AuthAuditEventType;
import com.tinqa.procurement.security.config.AccountEmailProperties;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.password.dto.PasswordLinkResetRequest;
import com.tinqa.procurement.security.password.dto.PasswordLinkResponse;
import com.tinqa.procurement.security.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Emailed password links for the staff portal: SETUP when a new employee's account is enabled (instead of a shared
 * default password), RESET for "Forgot password?". A link carries a random token stored only as its SHA-256, works
 * once and expires after tinqa.account.password-link.validity (15 minutes). Account emails go to the employee's
 * verified personal email, or the login email for accounts without one (dealers, the bootstrap Super Admin).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordLinkService {

    private final PasswordLinkRepository linkRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final StaffMailer mailer;
    private final PasswordUpdater passwordUpdater;
    private final NotificationService notificationService;
    private final AccountEmailProperties properties;
    private final Clock clock;

    /** Where a user's account emails go, and how they are greeted. */
    private record Mailbox(String to, String name) {
    }

    /**
     * A set-password link for a new account, emailed once the account change commits. A failed send is not undone
     * (the account stays as approved); whoever created the employee is told, and the employee can use "Forgot password?".
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void sendSetupLinkAfterCommit(User account, Employee employee) {
        String token = issue(account, PasswordLinkPurpose.SETUP);
        Mailbox mailbox = mailbox(account, Optional.of(employee));
        long validMinutes = properties.passwordLink().validity().toMinutes();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    mailer.sendPasswordLink(mailbox.to(), mailbox.name(), token, true, validMinutes);
                } catch (StaffMailException exception) {
                    log.warn("Set-password link for {} could not be emailed: {}", account.getUsername(), exception.getMessage());
                    if (employee.getCreatedBy() != null) {
                        notificationService.createForUser(employee.getCreatedBy(), "Set-password email not sent",
                                "The set-password link for " + employee.getDisplayName() + " (username " + account.getUsername()
                                        + ") could not be emailed to " + mailbox.to() + ". Ask them to use \"Forgot password?\" on "
                                        + "the sign-in page with their username once email is working.", NotificationLinks.EMPLOYEES);
                    }
                }
            }
        });
    }

    /**
     * "Forgot password?": emails a reset link to the account signed in with this username or email (login or the
     * employee's personal email). Never says whether the account exists; an unknown name, a disabled account, or a
     * request right after another sends nothing.
     */
    @Transactional
    public void requestReset(String identifier) {
        String value = identifier == null ? "" : identifier.trim();
        Optional<User> found = userRepository.findByUsernameIgnoreCase(value)
                .or(() -> userRepository.findByEmailIgnoreCase(value))
                .or(() -> employeeRepository.findFirstByPersonalEmailIgnoreCaseAndIdNot(value, -1L)
                        .flatMap(employee -> userRepository.findById(employee.getUserId())));
        LocalDateTime since = LocalDateTime.now(clock).minus(properties.passwordLink().resendCooldown());
        Optional<User> account = found
                .filter(user -> user.isEnabled() && user.isAccountNonLocked())
                .filter(user -> !linkRepository.existsByUserIdAndPurposeAndCreatedAtAfter(user.getId(), PasswordLinkPurpose.RESET, since));
        if (account.isEmpty()) {
            return;
        }
        String token = issue(account.get(), PasswordLinkPurpose.RESET);
        Mailbox mailbox = mailbox(account.get(), employeeRepository.findByUserId(account.get().getId()));
        try {
            mailer.sendPasswordLink(mailbox.to(), mailbox.name(), token, false, properties.passwordLink().validity().toMinutes());
        } catch (StaffMailException exception) {
            throw StaffMailErrors.toApiException(exception);
        }
    }

    /** Whether a link still works, and whose it is. */
    @Transactional(readOnly = true)
    public PasswordLinkResponse check(String token) {
        PasswordLink link = requireOpen(token);
        User user = requireAccount(link);
        return new PasswordLinkResponse(link.getPurpose(), user.getUsername(), link.getExpiresAt());
    }

    /** Sets the password chosen on the link's page; signs out every session and retires the account's open links. */
    @Transactional
    public void reset(PasswordLinkResetRequest request, HttpServletRequest httpRequest) {
        PasswordLink link = requireOpen(request.getToken());
        User user = requireAccount(link);
        PasswordPolicy.requireNewPassword(request.getNewPassword(), request.getConfirmNewPassword());
        passwordUpdater.apply(user, request.getNewPassword(), AuthAuditEventType.PASSWORD_SET_BY_LINK, httpRequest);
        linkRepository.retireOpenLinks(user.getId(), LocalDateTime.now(clock));
    }

    private String issue(User user, PasswordLinkPurpose purpose) {
        LocalDateTime now = LocalDateTime.now(clock);
        linkRepository.retireOpenLinks(user.getId(), now);
        String token = SecureTokens.urlToken();
        linkRepository.save(PasswordLink.builder()
                .userId(user.getId())
                .tokenHash(SecureTokens.sha256Hex(token))
                .purpose(purpose)
                .expiresAt(now.plus(properties.passwordLink().validity()))
                .createdAt(now)
                .build());
        return token;
    }

    private Mailbox mailbox(User user, Optional<Employee> employee) {
        String personal = employee.map(Employee::getPersonalEmail).filter(email -> !email.isBlank()).orElse(null);
        String name = employee.map(Employee::getFirstName).orElse(user.getUsername());
        return new Mailbox(personal != null ? personal : user.getEmail(), name);
    }

    private PasswordLink requireOpen(String token) {
        if (token == null || token.isBlank()) {
            throw invalid();
        }
        PasswordLink link = linkRepository.findByTokenHashAndUsedAtIsNull(SecureTokens.sha256Hex(token.trim()))
                .orElseThrow(PasswordLinkService::invalid);
        if (!link.getExpiresAt().isAfter(LocalDateTime.now(clock))) {
            throw ApiErrors.of(HttpStatus.BAD_REQUEST, ErrorCodes.PASSWORD_LINK_EXPIRED,
                    "This link has expired. Ask for a new one with \"Forgot password?\" on the sign-in page.");
        }
        return link;
    }

    private User requireAccount(PasswordLink link) {
        return userRepository.findById(link.getUserId())
                .filter(user -> user.isEnabled() && user.isAccountNonLocked())
                .orElseThrow(PasswordLinkService::invalid);
    }

    private static ApiException invalid() {
        return ApiErrors.of(HttpStatus.BAD_REQUEST, ErrorCodes.PASSWORD_LINK_INVALID,
                "This link is not valid or has already been used. Ask for a new one with \"Forgot password?\" on the sign-in page.");
    }
}
