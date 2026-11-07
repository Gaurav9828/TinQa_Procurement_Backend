package com.tinqa.procurement.security.service.impl;

import com.tinqa.procurement.common.constant.AppConstants;
import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.supplier.login.SupplierLoginService;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.security.access.AccessDTOs;
import com.tinqa.procurement.security.access.AccessResolver;
import com.tinqa.procurement.security.access.UserAccess;
import com.tinqa.procurement.security.dto.AdminLoginRequest;
import com.tinqa.procurement.security.dto.LoginResponse;
import com.tinqa.procurement.security.dto.LogoutResponse;
import com.tinqa.procurement.security.dto.MobileLoginRequest;
import com.tinqa.procurement.security.model.AuthClient;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.AuthenticationService;
import com.tinqa.procurement.security.service.jwt.JwtService;
import com.tinqa.procurement.security.service.jwt.TokenClaims;
import com.tinqa.procurement.security.service.token.TokenRevocationService;
import com.tinqa.procurement.security.web.BearerTokenResolver;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.EnumSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuthenticationServiceImpl implements AuthenticationService {

    // The web app is for employees and for suppliers with a supplier login (who only hold the supplier portal permissions)
    private static final Set<Role> WEB_ROLES = EnumSet.of(Role.ADMIN_L1, Role.ADMIN_L2, Role.SUPPLIER);
    private static final String LOGOUT_MESSAGE = "Logout successful";

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final SupplierLoginService supplierLoginService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenRevocationService tokenRevocationService;
    private final AccessResolver accessResolver;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public LoginResponse loginAdmin(AdminLoginRequest request) {
        User user = authenticate(request.getUsername(), request.getPassword(), AuthClient.WEB);
        if (!WEB_ROLES.contains(user.getRole())) {
            throw new BadRequestException("User does not have the required role");
        }
        return buildLoginResponse(user);
    }

    @Override
    @Transactional(readOnly = true)
    public LoginResponse loginMobile(MobileLoginRequest request) {
        // Mobile accounts use their mobile number as username
        return buildLoginResponse(authenticate(request.getMobileNumber(), request.getPassword(), AuthClient.MOBILE));
    }

    @Override
    public LogoutResponse logout(HttpServletRequest request) {
        // Idempotent: without a usable token the client is already effectively logged out
        BearerTokenResolver.resolve(request).ifPresent(token -> {
            try {
                TokenClaims claims = jwtService.parse(token);
                tokenRevocationService.revoke(claims.jti(), claims.remainingLifetime(clock));
            } catch (JwtException | IllegalArgumentException ignored) {
                // Expired or invalid: nothing left to revoke
            }
        });
        return LogoutResponse.builder()
                .success(true)
                .message(LOGOUT_MESSAGE)
                .build();
    }

    // Password first, so account state is only revealed to someone who knows the password
    private User authenticate(String username, String rawPassword, AuthClient channel) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BadRequestException(AppConstants.MESSAGE_INVALID_CREDENTIALS));
        if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
            throw new BadRequestException(AppConstants.MESSAGE_INVALID_CREDENTIALS);
        }
        if (!user.isEnabled()) {
            throw new BadRequestException(disabledReason(user));
        }
        if (!user.isAccountNonLocked()) {
            throw new BadRequestException(AppConstants.MESSAGE_ACCOUNT_LOCKED);
        }
        if (user.getAuthClient() != channel) {
            throw new BadRequestException("This account is not authorized for this authentication channel");
        }
        return user;
    }

    // Accounts that are disabled because of the employee or supplier login approval workflow say why
    private String disabledReason(User user) {
        if (user.getRole() == Role.SUPPLIER) {
            return supplierLoginService.signInRefusal(user).orElse(AppConstants.MESSAGE_ACCOUNT_DISABLED);
        }
        return employeeRepository.findByUserId(user.getId())
                .map(employee -> switch (employee.getStatus()) {
                    case APPROVAL_PENDING -> "Your account is waiting for Admin L2 approval. You can sign in once it is approved.";
                    case IN_ACTIVE -> employee.getApprovalRejectionReason() != null
                            ? "Your account request was not approved. Please contact your administrator."
                            : AppConstants.MESSAGE_ACCOUNT_DISABLED;
                    default -> AppConstants.MESSAGE_ACCOUNT_DISABLED;
                })
                .orElse(AppConstants.MESSAGE_ACCOUNT_DISABLED);
    }

    // The employee's display name (else first and last name); other accounts have none
    private String displayName(User user) {
        return employeeRepository.findByUserId(user.getId())
                .map(employee -> employee.getDisplayName() != null && !employee.getDisplayName().isBlank()
                        ? employee.getDisplayName().trim()
                        : Stream.of(employee.getFirstName(), employee.getLastName())
                                .filter(part -> part != null && !part.isBlank())
                                .map(String::trim)
                                .collect(Collectors.joining(" ")))
                .filter(name -> !name.isBlank())
                .orElse(null);
    }

    private LoginResponse buildLoginResponse(User user) {
        UserAccess access = accessResolver.resolve(user);
        AccessDTOs.MyAccess summary = AccessDTOs.MyAccess.of(access);
        return LoginResponse.builder()
                .accessToken(jwtService.generateToken(user, access, displayName(user)))
                .tokenType(AppConstants.TOKEN_TYPE_BEARER)
                .expiresIn(jwtService.getExpirationTime())
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole())
                .isFirstLogin(user.isFirstLogin())
                .authClient(user.getAuthClient())
                .designation(summary.designation())
                .designationLabel(summary.designationLabel())
                .permissionGroups(summary.permissionGroups())
                .permissions(summary.permissions())
                .build();
    }
}
