package com.tinqa.procurement.security.service.impl;

import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.exception.UnauthorizedException;
import com.tinqa.procurement.security.dto.*;
import com.tinqa.procurement.security.model.AuthClient;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.AuthenticationService;
import com.tinqa.procurement.security.service.jwt.JwtService;
import com.tinqa.procurement.security.service.token.TokenRevocationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import com.tinqa.procurement.security.dto.ChangePasswordRequest;
import com.tinqa.procurement.security.dto.PasswordChangeResponse;
import jakarta.transaction.Transactional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AuthenticationServiceImpl implements AuthenticationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenRevocationService tokenRevocationService;

    @Override
    public LoginResponse authenticateAdmin(AdminLoginRequest request) {
        User user = findUser(request.getUsername());

        validateAccount(user);
        validatePassword(request.getPassword(), user.getPassword());
        validateAuthClient(user, AuthClient.WEB);
        validateRole(user);

        return buildAuthenticationResponse(user);
    }

    @Override
    public LoginResponse authenticateMobile(MobileLoginRequest request) {
        User user = findUser(request.getMobileNumber());

        validateAccount(user);
        validatePassword(request.getPassword(), user.getPassword());
        validateAuthClient(user, AuthClient.MOBILE);

        return buildAuthenticationResponse(user);
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new BadRequestException("Invalid username or password"));
    }

    private void validateAccount(User user) {
        if (!user.isEnabled()) {
            throw new BadRequestException("User account is disabled");
        }

        if (!user.isAccountNonLocked()) {
            throw new BadRequestException("User account is locked");
        }
    }

    private void validatePassword(String rawPassword, String encodedPassword) {
        if (!passwordEncoder.matches(rawPassword, encodedPassword)) {
            throw new BadRequestException("Invalid username or password");
        }
    }

    private void validateAuthClient(User user, AuthClient expectedClient) {
        if (!expectedClient.equals(user.getAuthClient())) {
            throw new BadRequestException("This account is not authorized for this authentication channel");
        }
    }

    private void validateRole(User user) {
        if (!Role.ADMIN_L1.equals(user.getRole()) && !Role.ADMIN_L2.equals(user.getRole())) {
            throw new BadRequestException("User does not have the required role");
        }
    }

    private LoginResponse buildAuthenticationResponse(User user) {
        String token = jwtService.generateToken(user);

        return LoginResponse.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .expiresIn(jwtService.getExpirationTime())
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole())
                .authClient(user.getAuthClient())
                .build();
    }

    @Override
    public LogoutResponse logout(
            HttpServletRequest request) {

        String authorization =
                request.getHeader("Authorization");

        /*
         * Logout is intentionally idempotent.
         *
         * If there is no token, the client is effectively
         * already logged out.
         */
        if (authorization == null
                || !authorization.startsWith("Bearer ")) {

            return LogoutResponse.builder()
                    .success(true)
                    .message("Logout successful")
                    .build();
        }

        String token =
                authorization.substring(7);

        try {

            String jti =
                    jwtService.extractJti(token);

            Duration remainingLifetime =
                    jwtService.getRemainingLifetime(token);

            tokenRevocationService.revoke(
                    jti,
                    remainingLifetime
            );

        } catch (Exception ignored) {

            /*
             * Logout should remain idempotent.
             *
             * If the token is already expired/invalid,
             * there is nothing left to revoke.
             */
        }

        return LogoutResponse.builder()
                .success(true)
                .message("Logout successful")
                .build();
    }

    @Override
    @Transactional
    public PasswordChangeResponse changePassword(
            ChangePasswordRequest request,
            HttpServletRequest httpRequest) {

        /*
         * Get the currently authenticated username.
         *
         * We do NOT trust a username coming from the frontend.
         */
        Authentication authentication =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getName() == null) {

            throw new UnauthorizedException(
                    "Authentication is required"
            );
        }

        String username = authentication.getName();

        /*
         * Load the actual user from the database.
         */
        User user = userRepository
                .findByUsername(username)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "User not found"
                        )
                );

        /*
         * Validate current password.
         */
        if (!passwordEncoder.matches(
                request.getCurrentPassword(),
                user.getPassword())) {

            throw new BadRequestException(
                    "Current password is incorrect"
            );
        }

        /*
         * Validate new password confirmation.
         */
        if (!request.getNewPassword().equals(
                request.getConfirmNewPassword())) {

            throw new BadRequestException(
                    "New password and confirmation password do not match"
            );
        }

        /*
         * Prevent changing the password to the same
         * password.
         */
        if (passwordEncoder.matches(
                request.getNewPassword(),
                user.getPassword())) {

            throw new BadRequestException(
                    "New password must be different from the current password"
            );
        }

        /*
         * Encode the new password.
         *
         * NEVER store the raw password.
         */
        user.setPassword(
                passwordEncoder.encode(
                        request.getNewPassword()
                )
        );

        userRepository.save(user);

        /*
         * Password successfully changed.
         *
         * Now invalidate the EXACT JWT used for this request.
         */
        logout(httpRequest);

        return PasswordChangeResponse.builder()
                .success(true)
                .message(
                        "Password updated successfully. Please login again."
                )
                .code("PASSWORD_CHANGED")
                .requiresLogin(true)
                .build();
    }
}