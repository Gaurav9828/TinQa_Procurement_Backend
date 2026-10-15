package com.tinqa.procurement.security.controller;

import com.tinqa.procurement.security.dto.ChangePasswordRequest;
import com.tinqa.procurement.security.dto.FirstLoginPasswordRequest;
import com.tinqa.procurement.security.dto.LogoutResponse;
import com.tinqa.procurement.security.dto.PasswordChangeResponse;
import com.tinqa.procurement.security.service.AuthenticationService;
import com.tinqa.procurement.security.service.PasswordService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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

    @PostMapping("/logout")
    public ResponseEntity<LogoutResponse> logout(HttpServletRequest request) {
        return ResponseEntity.ok(authenticationService.logout(request));
    }

    @PostMapping("/change-password")
    public ResponseEntity<PasswordChangeResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                                                 HttpServletRequest httpRequest) {
        return ResponseEntity.ok(passwordService.changePassword(request, httpRequest));
    }

    // Not @Valid: both fields are checked in the service after the first-login status, so errors come in a fixed order
    @PostMapping("/first-login/password")
    public ResponseEntity<PasswordChangeResponse> setFirstLoginPassword(@RequestBody FirstLoginPasswordRequest request,
                                                                        HttpServletRequest httpRequest) {
        return ResponseEntity.ok(passwordService.setFirstLoginPassword(request, httpRequest));
    }
}
