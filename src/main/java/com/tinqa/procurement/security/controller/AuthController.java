package com.tinqa.procurement.security.controller;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.security.dto.ChangePasswordRequest;
import com.tinqa.procurement.security.dto.FirstLoginPasswordRequest;
import com.tinqa.procurement.security.dto.LogoutResponse;
import com.tinqa.procurement.security.dto.PasswordChangeResponse;
import com.tinqa.procurement.security.password.PasswordLinkService;
import com.tinqa.procurement.security.password.dto.ForgotPasswordRequest;
import com.tinqa.procurement.security.password.dto.PasswordLinkCheckRequest;
import com.tinqa.procurement.security.password.dto.PasswordLinkResetRequest;
import com.tinqa.procurement.security.password.dto.PasswordLinkResponse;
import com.tinqa.procurement.security.service.AuthenticationService;
import com.tinqa.procurement.security.service.PasswordService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import com.tinqa.procurement.security.service.jwt.JwtKeys;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationService authenticationService;
    private final PasswordService passwordService;
    private final PasswordLinkService passwordLinkService;
    private final JwtKeys jwtKeys;

    // Public: the key tokens are verified with (JSON Web Key Set). Ecommerce verifies admin tokens with it and so cannot
    // create them. Plain JWKS JSON, not wrapped, as JWKS clients expect
    @GetMapping("/jwks")
    public ResponseEntity<java.util.Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePublic())
                .body(jwtKeys.jwks());
    }

    @PostMapping("/logout")
    public ResponseEntity<LogoutResponse> logout(HttpServletRequest request) {
        return ResponseEntity.ok(authenticationService.logout(request));
    }

    @PostMapping("/change-password")
    public ResponseEntity<PasswordChangeResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                                                 HttpServletRequest httpRequest) {
        return ResponseEntity.ok(passwordService.changePassword(request, httpRequest));
    }

    // Public: emails a reset link to the account with this username or email; the answer never says whether it exists
    @PostMapping("/password/forgot")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordLinkService.requestReset(request.getIdentifier());
        return ApiResponses.ok("If an account matches, we've emailed it a link to choose a new password.");
    }

    // Public: whether an emailed password link still works, and whose it is
    @PostMapping("/password/link/check")
    public ResponseEntity<ApiResponse<PasswordLinkResponse>> checkPasswordLink(@RequestBody PasswordLinkCheckRequest request) {
        return ApiResponses.ok(passwordLinkService.check(request.getToken()), "The link is valid");
    }

    // Public: the password chosen through an emailed link (new account or forgot password); not @Valid, see the request
    @PostMapping("/password/link")
    public ResponseEntity<ApiResponse<Void>> setPasswordFromLink(@RequestBody PasswordLinkResetRequest request,
                                                                 HttpServletRequest httpRequest) {
        passwordLinkService.reset(request, httpRequest);
        return ApiResponses.ok("Your password is saved. Sign in with your new password.");
    }

    // Not @Valid: both fields are checked in the service after the first-login status, so errors come in a fixed order
    @PostMapping("/first-login/password")
    public ResponseEntity<PasswordChangeResponse> setFirstLoginPassword(@RequestBody FirstLoginPasswordRequest request,
                                                                        HttpServletRequest httpRequest) {
        return ResponseEntity.ok(passwordService.setFirstLoginPassword(request, httpRequest));
    }
}
