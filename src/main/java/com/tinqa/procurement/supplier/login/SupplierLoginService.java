package com.tinqa.procurement.supplier.login;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.supplier.entity.Supplier;
import com.tinqa.procurement.supplier.repository.SupplierRepository;
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
 * Supplier logins: a web account with role SUPPLIER tied to one supplier. A Supplier Manager creates it with the default
 * password (changed at first sign-in); it can't sign in until someone other than its creator approves it. The account
 * is enabled only while the login is ACTIVE and the supplier is active, so deactivating a supplier ends its sessions.
 */
@Service
@RequiredArgsConstructor
public class SupplierLoginService {

    private final SupplierLoginRepository supplierLoginRepository;
    private final SupplierRepository supplierRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmployeeProperties employeeProperties;
    private final CurrentUserProvider currentUserProvider;
    private final NotificationService notificationService;
    private final Clock clock;

    /** The supplier's login, or empty when it has none yet. */
    @Transactional(readOnly = true)
    public Optional<SupplierLoginDTOs.Response> getLogin(Long supplierId) {
        Supplier supplier = findSupplier(supplierId);
        return supplierLoginRepository.findBySupplierId(supplierId).map(login -> toResponse(login, supplier));
    }

    @Transactional(readOnly = true)
    public List<SupplierLoginDTOs.Response> getLogins(SupplierLoginStatus status) {
        List<SupplierLogin> logins = status == null
                ? supplierLoginRepository.findAll()
                : supplierLoginRepository.findByStatusOrderByCreatedAtAsc(status);
        Map<Long, Supplier> suppliers = supplierRepository.findAllById(logins.stream().map(SupplierLogin::getSupplierId).toList())
                .stream().collect(Collectors.toMap(Supplier::getId, Function.identity()));
        return logins.stream().map(login -> toResponse(login, suppliers.get(login.getSupplierId()))).toList();
    }

    /** Creates the supplier's login, or submits a rejected one again with the given username and email. */
    @Transactional
    public SupplierLoginDTOs.Response createLogin(Long supplierId, SupplierLoginDTOs.CreateRequest request) {
        User creator = currentUserProvider.getCurrentUser();
        Supplier supplier = findSupplier(supplierId);
        if (!Boolean.TRUE.equals(supplier.getIsActive())) {
            throw ApiErrors.conflict(ErrorCodes.SUPPLIER_INACTIVE, "Supplier " + supplier.getName()
                    + " is inactive. Activate the supplier before giving them a login.");
        }
        Optional<SupplierLogin> existing = supplierLoginRepository.findBySupplierIdForUpdate(supplierId);
        if (existing.isPresent() && existing.get().getStatus() != SupplierLoginStatus.REJECTED) {
            throw ApiErrors.conflict(ErrorCodes.SUPPLIER_LOGIN_EXISTS, "Supplier " + supplier.getName() + " already has a login ("
                    + label(existing.get().getStatus()) + ").");
        }

        String username = request.getUsername().trim();
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        Long ownUserId = existing.map(SupplierLogin::getUserId).orElseGet(() -> orphanAccountId(username, email));
        checkAccountUnique(username, email, ownUserId);

        Instant now = clock.instant();
        SupplierLogin login;
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

            login = existing.orElseGet(() -> SupplierLogin.builder().supplierId(supplierId).userId(saved.getId()).build());
            login.setStatus(SupplierLoginStatus.APPROVAL_PENDING);
            login.setCreatedBy(creator.getId());
            login.setCreatedAt(now);
            login.setDecidedBy(null);
            login.setDecidedAt(null);
            login.setRejectionReason(null);
            login.setUpdatedAt(now);
            login = supplierLoginRepository.saveAndFlush(login);
        } catch (DataIntegrityViolationException exception) {
            // Another request took the username or email between our check and the insert
            throw ApiErrors.conflict(ErrorCodes.DUPLICATE_VALUE,
                    "This username or email was just used for another account. Choose different ones and try again.");
        }

        notificationService.createForPermission(Permission.SUPPLIER_LOGIN_APPROVE, creator.getId(), "Supplier login awaiting approval",
                "A login for supplier " + supplier.getName() + " (username " + username + ") was created by " + creator.getUsername()
                        + " and can't sign in until someone approves it.", NotificationLinks.APPROVALS);
        return toResponse(login, supplier);
    }

    @Transactional
    public SupplierLoginDTOs.Response decide(Long supplierId, SupplierLoginDTOs.DecisionRequest request) {
        User approver = currentUserProvider.getCurrentUser();
        Supplier supplier = findSupplier(supplierId);
        SupplierLogin login = lockLogin(supplier);
        if (login.getStatus() != SupplierLoginStatus.APPROVAL_PENDING) {
            throw ApiErrors.conflict(ErrorCodes.SUPPLIER_LOGIN_NOT_PENDING, "The login for supplier " + supplier.getName()
                    + " is " + label(login.getStatus()) + ", not waiting for approval.");
        }
        // Maker-checker: the person who created a login never approves it
        if (login.getCreatedBy().equals(approver.getId())) {
            throw ApiErrors.forbidden(ErrorCodes.SUPPLIER_LOGIN_SELF_DECISION,
                    "You created this supplier login, so someone else has to approve or reject it.");
        }

        String reason = OptionalText.blankToNull(request.getRejectionReason());
        boolean approved = request.getDecision() == SupplierLoginDTOs.Decision.APPROVED;
        if (!approved && reason == null) {
            throw ApiErrors.fieldError("rejectionReason", "A reason is required to reject a supplier login.");
        }
        if (approved && !Boolean.TRUE.equals(supplier.getIsActive())) {
            throw ApiErrors.conflict(ErrorCodes.SUPPLIER_INACTIVE, "Supplier " + supplier.getName()
                    + " is inactive, so its login can't be approved.");
        }

        Instant now = clock.instant();
        login.setStatus(approved ? SupplierLoginStatus.ACTIVE : SupplierLoginStatus.REJECTED);
        login.setDecidedBy(approver.getId());
        login.setDecidedAt(now);
        login.setRejectionReason(approved ? null : reason);
        login.setUpdatedAt(now);
        supplierLoginRepository.save(login);
        User account = syncAccount(login, supplier);

        notificationService.createForUser(login.getCreatedBy(), approved ? "Supplier login approved" : "Supplier login rejected",
                "The login for supplier " + supplier.getName() + " (username " + account.getUsername() + ") was "
                        + (approved ? "approved" : "rejected") + " by " + approver.getUsername()
                        + (approved ? ". The supplier can now sign in with the default password and must change it."
                        : ". Reason: " + reason), NotificationLinks.SUPPLIERS);
        return toResponse(login, supplier);
    }

    /** Switches an approved login off or on again; turning it off ends the supplier's open sessions. */
    @Transactional
    public SupplierLoginDTOs.Response setEnabled(Long supplierId, boolean enabled) {
        Supplier supplier = findSupplier(supplierId);
        SupplierLogin login = lockLogin(supplier);
        SupplierLoginStatus from = login.getStatus();
        if (from != SupplierLoginStatus.ACTIVE && from != SupplierLoginStatus.DISABLED) {
            throw ApiErrors.conflict(ErrorCodes.SUPPLIER_LOGIN_NOT_APPROVED, "The login for supplier " + supplier.getName()
                    + " is " + label(from) + ". Only an approved login can be switched on or off.");
        }
        login.setStatus(enabled ? SupplierLoginStatus.ACTIVE : SupplierLoginStatus.DISABLED);
        login.setUpdatedAt(clock.instant());
        supplierLoginRepository.save(login);
        syncAccount(login, supplier);
        return toResponse(login, supplier);
    }

    /** Puts the default password back; the supplier must change it at their next sign-in, and open sessions end. */
    @Transactional
    public SupplierLoginDTOs.Response resetPassword(Long supplierId) {
        Supplier supplier = findSupplier(supplierId);
        SupplierLogin login = lockLogin(supplier);
        if (login.getStatus() != SupplierLoginStatus.ACTIVE && login.getStatus() != SupplierLoginStatus.DISABLED) {
            throw ApiErrors.conflict(ErrorCodes.SUPPLIER_LOGIN_NOT_APPROVED, "The login for supplier " + supplier.getName()
                    + " is " + label(login.getStatus()) + ", so its password can't be reset.");
        }
        User account = userRepository.findById(login.getUserId()).orElseThrow();
        account.setPassword(passwordEncoder.encode(employeeProperties.defaultPassword()));
        account.setFirstLogin(true);
        account.setPasswordChangedAt(clock.instant());
        account.setUpdatedAt(LocalDateTime.now(clock));
        userRepository.save(account);
        return toResponse(login, supplier);
    }

    /** Call after a supplier is activated or deactivated, so its login follows. */
    @Transactional
    public void supplierStatusChanged(Supplier supplier) {
        supplierLoginRepository.findBySupplierIdForUpdate(supplier.getId()).ifPresent(login -> syncAccount(login, supplier));
    }

    /** The supplier a SUPPLIER account belongs to, for scoping everything that account does to its own records. */
    @Transactional(readOnly = true)
    public Optional<Long> supplierIdOf(Long userId) {
        return supplierLoginRepository.findByUserId(userId).map(SupplierLogin::getSupplierId);
    }

    /** The account a supplier signs in with, while that login is approved; for notifying the supplier. */
    @Transactional(readOnly = true)
    public Optional<Long> activeUserIdOf(Long supplierId) {
        return supplierLoginRepository.findBySupplierId(supplierId)
                .filter(login -> login.getStatus() == SupplierLoginStatus.ACTIVE)
                .map(SupplierLogin::getUserId);
    }

    /** Why a disabled supplier account can't sign in, or empty when the account isn't a supplier login. */
    @Transactional(readOnly = true)
    public Optional<String> signInRefusal(User user) {
        return supplierLoginRepository.findByUserId(user.getId()).map(login -> switch (login.getStatus()) {
            case APPROVAL_PENDING -> "Your supplier login is waiting for approval. You can sign in once it is approved.";
            case REJECTED -> "Your supplier login was not approved. Please contact your supplier manager.";
            case DISABLED -> "Your supplier login has been disabled. Please contact your supplier manager.";
            case ACTIVE -> "Your supplier account is inactive. Please contact your supplier manager.";
        });
    }

    // The account may sign in only while the login is approved and the supplier is active
    private User syncAccount(SupplierLogin login, Supplier supplier) {
        User account = userRepository.findById(login.getUserId()).orElseThrow();
        boolean enabled = login.getStatus() == SupplierLoginStatus.ACTIVE && Boolean.TRUE.equals(supplier.getIsActive());
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
                .role(Role.SUPPLIER)
                .authClient(AuthClient.WEB)
                .accountNonLocked(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    // A SUPPLIER account no login points at (left behind by an earlier, interrupted attempt) is reused, not reported as taken
    private Long orphanAccountId(String username, String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .or(() -> userRepository.findByUsernameIgnoreCase(username))
                .filter(user -> user.getRole() == Role.SUPPLIER)
                .filter(user -> supplierLoginRepository.findByUserId(user.getId()).isEmpty())
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

    private SupplierLogin lockLogin(Supplier supplier) {
        return supplierLoginRepository.findBySupplierIdForUpdate(supplier.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Supplier " + supplier.getName() + " has no login yet."));
    }

    private Supplier findSupplier(Long supplierId) {
        return supplierRepository.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found with id: " + supplierId));
    }

    private SupplierLoginDTOs.Response toResponse(SupplierLogin login, Supplier supplier) {
        Set<Long> userIds = Stream.of(login.getUserId(), login.getCreatedBy(), login.getDecidedBy())
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, User> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        User account = users.get(login.getUserId());
        return SupplierLoginDTOs.Response.builder()
                .id(login.getId())
                .supplierId(login.getSupplierId())
                .supplierName(supplier != null ? supplier.getName() : null)
                .supplierActive(supplier != null && Boolean.TRUE.equals(supplier.getIsActive()))
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

    private static String label(SupplierLoginStatus status) {
        return switch (status) {
            case APPROVAL_PENDING -> "waiting for approval";
            case ACTIVE -> "active";
            case REJECTED -> "rejected";
            case DISABLED -> "disabled";
        };
    }
}
