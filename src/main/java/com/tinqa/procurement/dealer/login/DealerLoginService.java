package com.tinqa.procurement.dealer.login;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.dealer.entity.Dealer;
import com.tinqa.procurement.dealer.repository.DealerRepository;
import com.tinqa.procurement.employee.config.EmployeeProperties;
import com.tinqa.procurement.notification.NotificationLinks;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.model.AuthClient;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Dealer logins: a web account with role DEALER tied to one dealer. A Dealer Manager creates it with the default
 * password (changed at first sign-in); it can't sign in until someone other than its creator approves it. The account
 * is enabled only while the login is ACTIVE and the dealer is active, so deactivating a dealer ends its sessions.
 */
@Service
@RequiredArgsConstructor
public class DealerLoginService {

    private final DealerLoginRepository dealerLoginRepository;
    private final DealerRepository dealerRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmployeeProperties employeeProperties;
    private final CurrentUserProvider currentUserProvider;
    private final NotificationService notificationService;
    private final Clock clock;

    /** The dealer's login, or empty when it has none yet. */
    @Transactional(readOnly = true)
    public Optional<DealerLoginDTOs.Response> getLogin(Long dealerId) {
        Dealer dealer = findDealer(dealerId);
        return dealerLoginRepository.findByDealerId(dealerId).map(login -> toResponse(login, dealer));
    }

    @Transactional(readOnly = true)
    public List<DealerLoginDTOs.Response> getLogins(DealerLoginStatus status) {
        List<DealerLogin> logins = status == null
                ? dealerLoginRepository.findAll()
                : dealerLoginRepository.findByStatusOrderByCreatedAtAsc(status);
        Map<Long, Dealer> dealers = dealerRepository.findAllById(logins.stream().map(DealerLogin::getDealerId).toList())
                .stream().collect(Collectors.toMap(Dealer::getId, Function.identity()));
        return logins.stream().map(login -> toResponse(login, dealers.get(login.getDealerId()))).toList();
    }

    /** Creates the dealer's login, or submits a rejected one again with the given username and email. */
    @Transactional
    public DealerLoginDTOs.Response createLogin(Long dealerId, DealerLoginDTOs.CreateRequest request) {
        User creator = currentUserProvider.getCurrentUser();
        Dealer dealer = findDealer(dealerId);
        if (!Boolean.TRUE.equals(dealer.getIsActive())) {
            throw ApiErrors.conflict(ErrorCodes.DEALER_INACTIVE, "Dealer " + dealer.getName()
                    + " is inactive. Activate the dealer before giving them a login.");
        }
        Optional<DealerLogin> existing = dealerLoginRepository.findByDealerIdForUpdate(dealerId);
        if (existing.isPresent() && existing.get().getStatus() != DealerLoginStatus.REJECTED) {
            throw ApiErrors.conflict(ErrorCodes.DEALER_LOGIN_EXISTS, "Dealer " + dealer.getName() + " already has a login ("
                    + label(existing.get().getStatus()) + ").");
        }

        String username = request.getUsername().trim();
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        Long ownUserId = existing.map(DealerLogin::getUserId).orElseGet(() -> orphanAccountId(username, email));
        checkAccountUnique(username, email, ownUserId);

        Instant now = clock.instant();
        DealerLogin login;
        try {
            User account = ownUserId == null ? newAccount() : userRepository.findById(ownUserId).orElseThrow();
            account.setUsername(username);
            account.setEmail(email);
            account.setPassword(passwordEncoder.encode(employeeProperties.defaultPassword()));
            account.setFirstLogin(true);
            // Nobody signs in with it until it is approved
            account.setEnabled(false);
            account.setUpdatedAt(LocalDateTime.now(clock));
            User saved = userRepository.saveAndFlush(account);

            login = existing.orElseGet(() -> DealerLogin.builder().dealerId(dealerId).userId(saved.getId()).build());
            login.setStatus(DealerLoginStatus.APPROVAL_PENDING);
            login.setCreatedBy(creator.getId());
            login.setCreatedAt(now);
            login.setDecidedBy(null);
            login.setDecidedAt(null);
            login.setRejectionReason(null);
            login.setUpdatedAt(now);
            login = dealerLoginRepository.saveAndFlush(login);
        } catch (DataIntegrityViolationException exception) {
            // Another request took the username or email between our check and the insert
            throw ApiErrors.conflict(ErrorCodes.DUPLICATE_VALUE,
                    "This username or email was just used for another account. Choose different ones and try again.");
        }

        notificationService.createForPermission(Permission.DEALER_LOGIN_APPROVE, creator.getId(), "Dealer login awaiting approval",
                "A login for dealer " + dealer.getName() + " (username " + username + ") was created by " + creator.getUsername()
                        + " and can't sign in until someone approves it.", NotificationLinks.APPROVALS);
        return toResponse(login, dealer);
    }

    @Transactional
    public DealerLoginDTOs.Response decide(Long dealerId, DealerLoginDTOs.DecisionRequest request) {
        User approver = currentUserProvider.getCurrentUser();
        Dealer dealer = findDealer(dealerId);
        DealerLogin login = lockLogin(dealer);
        if (login.getStatus() != DealerLoginStatus.APPROVAL_PENDING) {
            throw ApiErrors.conflict(ErrorCodes.DEALER_LOGIN_NOT_PENDING, "The login for dealer " + dealer.getName()
                    + " is " + label(login.getStatus()) + ", not waiting for approval.");
        }
        // Maker-checker: the person who created a login never approves it
        if (login.getCreatedBy().equals(approver.getId())) {
            throw ApiErrors.forbidden(ErrorCodes.DEALER_LOGIN_SELF_DECISION,
                    "You created this dealer login, so someone else has to approve or reject it.");
        }

        String reason = OptionalText.blankToNull(request.getRejectionReason());
        boolean approved = request.getDecision() == DealerLoginDTOs.Decision.APPROVED;
        if (!approved && reason == null) {
            throw ApiErrors.fieldError("rejectionReason", "A reason is required to reject a dealer login.");
        }
        if (approved && !Boolean.TRUE.equals(dealer.getIsActive())) {
            throw ApiErrors.conflict(ErrorCodes.DEALER_INACTIVE, "Dealer " + dealer.getName()
                    + " is inactive, so its login can't be approved.");
        }

        Instant now = clock.instant();
        login.setStatus(approved ? DealerLoginStatus.ACTIVE : DealerLoginStatus.REJECTED);
        login.setDecidedBy(approver.getId());
        login.setDecidedAt(now);
        login.setRejectionReason(approved ? null : reason);
        login.setUpdatedAt(now);
        dealerLoginRepository.save(login);
        User account = syncAccount(login, dealer);

        notificationService.createForUser(login.getCreatedBy(), approved ? "Dealer login approved" : "Dealer login rejected",
                "The login for dealer " + dealer.getName() + " (username " + account.getUsername() + ") was "
                        + (approved ? "approved" : "rejected") + " by " + approver.getUsername()
                        + (approved ? ". The dealer can now sign in with the default password and must change it."
                        : ". Reason: " + reason), NotificationLinks.DEALERS);
        return toResponse(login, dealer);
    }

    /** Switches an approved login off or on again; turning it off ends the dealer's open sessions. */
    @Transactional
    public DealerLoginDTOs.Response setEnabled(Long dealerId, boolean enabled) {
        Dealer dealer = findDealer(dealerId);
        DealerLogin login = lockLogin(dealer);
        DealerLoginStatus from = login.getStatus();
        if (from != DealerLoginStatus.ACTIVE && from != DealerLoginStatus.DISABLED) {
            throw ApiErrors.conflict(ErrorCodes.DEALER_LOGIN_NOT_APPROVED, "The login for dealer " + dealer.getName()
                    + " is " + label(from) + ". Only an approved login can be switched on or off.");
        }
        login.setStatus(enabled ? DealerLoginStatus.ACTIVE : DealerLoginStatus.DISABLED);
        login.setUpdatedAt(clock.instant());
        dealerLoginRepository.save(login);
        syncAccount(login, dealer);
        return toResponse(login, dealer);
    }

    /** Puts the default password back; the dealer must change it at their next sign-in, and open sessions end. */
    @Transactional
    public DealerLoginDTOs.Response resetPassword(Long dealerId) {
        Dealer dealer = findDealer(dealerId);
        DealerLogin login = lockLogin(dealer);
        if (login.getStatus() != DealerLoginStatus.ACTIVE && login.getStatus() != DealerLoginStatus.DISABLED) {
            throw ApiErrors.conflict(ErrorCodes.DEALER_LOGIN_NOT_APPROVED, "The login for dealer " + dealer.getName()
                    + " is " + label(login.getStatus()) + ", so its password can't be reset.");
        }
        User account = userRepository.findById(login.getUserId()).orElseThrow();
        account.setPassword(passwordEncoder.encode(employeeProperties.defaultPassword()));
        account.setFirstLogin(true);
        account.setPasswordChangedAt(clock.instant());
        account.setUpdatedAt(LocalDateTime.now(clock));
        userRepository.save(account);
        return toResponse(login, dealer);
    }

    /** Call after a dealer is activated or deactivated, so its login follows. */
    @Transactional
    public void dealerStatusChanged(Dealer dealer) {
        dealerLoginRepository.findByDealerIdForUpdate(dealer.getId()).ifPresent(login -> syncAccount(login, dealer));
    }

    /** The dealer a DEALER account belongs to, for scoping everything that account does to its own records. */
    @Transactional(readOnly = true)
    public Optional<Long> dealerIdOf(Long userId) {
        return dealerLoginRepository.findByUserId(userId).map(DealerLogin::getDealerId);
    }

    /** The account a dealer signs in with, while that login is approved; for notifying the dealer. */
    @Transactional(readOnly = true)
    public Optional<Long> activeUserIdOf(Long dealerId) {
        return dealerLoginRepository.findByDealerId(dealerId)
                .filter(login -> login.getStatus() == DealerLoginStatus.ACTIVE)
                .map(DealerLogin::getUserId);
    }

    /** Why a disabled dealer account can't sign in, or empty when the account isn't a dealer login. */
    @Transactional(readOnly = true)
    public Optional<String> signInRefusal(User user) {
        return dealerLoginRepository.findByUserId(user.getId()).map(login -> switch (login.getStatus()) {
            case APPROVAL_PENDING -> "Your dealer login is waiting for approval. You can sign in once it is approved.";
            case REJECTED -> "Your dealer login was not approved. Please contact your dealer manager.";
            case DISABLED -> "Your dealer login has been disabled. Please contact your dealer manager.";
            case ACTIVE -> "Your dealer account is inactive. Please contact your dealer manager.";
        });
    }

    // The account may sign in only while the login is approved and the dealer is active
    private User syncAccount(DealerLogin login, Dealer dealer) {
        User account = userRepository.findById(login.getUserId()).orElseThrow();
        boolean enabled = login.getStatus() == DealerLoginStatus.ACTIVE && Boolean.TRUE.equals(dealer.getIsActive());
        if (account.isEnabled() != enabled) {
            account.setEnabled(enabled);
            account.setUpdatedAt(LocalDateTime.now(clock));
            userRepository.save(account);
        }
        return account;
    }

    private User newAccount() {
        LocalDateTime now = LocalDateTime.now(clock);
        return User.builder()
                .role(Role.DEALER)
                .authClient(AuthClient.WEB)
                .accountNonLocked(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    // A DEALER account no login points at (left behind by an earlier, interrupted attempt) is reused, not reported as taken
    private Long orphanAccountId(String username, String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .or(() -> userRepository.findByUsernameIgnoreCase(username))
                .filter(user -> user.getRole() == Role.DEALER)
                .filter(user -> dealerLoginRepository.findByUserId(user.getId()).isEmpty())
                .map(User::getId)
                .orElse(null);
    }

    private void checkAccountUnique(String username, String email, Long ownUserId) {
        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        userRepository.findByUsernameIgnoreCase(username)
                .filter(user -> !user.getId().equals(ownUserId))
                .ifPresent(user -> errors.add(ApiErrors.error("username",
                        "Username '" + username + "' is already taken. Choose a different username.")));
        userRepository.findByEmailIgnoreCase(email)
                .filter(user -> !user.getId().equals(ownUserId))
                .ifPresent(user -> errors.add(ApiErrors.error("email", "Email '" + email + "' is already used by another account.")));
        if (!errors.isEmpty()) {
            throw new ApiException(ApiErrors.joinMessages(errors), ErrorCodes.DUPLICATE_VALUE, HttpStatus.CONFLICT, errors);
        }
    }

    private DealerLogin lockLogin(Dealer dealer) {
        return dealerLoginRepository.findByDealerIdForUpdate(dealer.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Dealer " + dealer.getName() + " has no login yet."));
    }

    private Dealer findDealer(Long dealerId) {
        return dealerRepository.findById(dealerId)
                .orElseThrow(() -> new ResourceNotFoundException("Dealer not found with id: " + dealerId));
    }

    private DealerLoginDTOs.Response toResponse(DealerLogin login, Dealer dealer) {
        Set<Long> userIds = Stream.of(login.getUserId(), login.getCreatedBy(), login.getDecidedBy())
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, User> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        User account = users.get(login.getUserId());
        return DealerLoginDTOs.Response.builder()
                .id(login.getId())
                .dealerId(login.getDealerId())
                .dealerName(dealer != null ? dealer.getName() : null)
                .dealerActive(dealer != null && Boolean.TRUE.equals(dealer.getIsActive()))
                .username(account != null ? account.getUsername() : null)
                .email(account != null ? account.getEmail() : null)
                .status(login.getStatus())
                .passwordChangePending(account != null && account.isFirstLogin())
                .createdBy(login.getCreatedBy())
                .createdByUsername(usernameOf(users, login.getCreatedBy()))
                .createdAt(login.getCreatedAt())
                .decidedByUsername(usernameOf(users, login.getDecidedBy()))
                .decidedAt(login.getDecidedAt())
                .rejectionReason(login.getRejectionReason())
                .build();
    }

    private static String usernameOf(Map<Long, User> users, Long userId) {
        User user = userId == null ? null : users.get(userId);
        return user != null ? user.getUsername() : null;
    }

    private static String label(DealerLoginStatus status) {
        return switch (status) {
            case APPROVAL_PENDING -> "waiting for approval";
            case ACTIVE -> "active";
            case REJECTED -> "rejected";
            case DISABLED -> "disabled";
        };
    }
}
