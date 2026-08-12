package com.tinqa.procurement.security.controller;

import com.tinqa.procurement.security.dto.ChangePasswordRequest;
import com.tinqa.procurement.security.dto.LogoutResponse;
import com.tinqa.procurement.security.dto.PasswordChangeResponse;
import com.tinqa.procurement.security.service.AuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationService authenticationService;

    @PostMapping("/logout")
    public ResponseEntity<LogoutResponse> logout(
            HttpServletRequest request) {

        LogoutResponse logoutResponse = authenticationService.logout(request);

        return ResponseEntity.ok(
                LogoutResponse.builder()
                        .success(logoutResponse.isSuccess())
                        .message(logoutResponse.getMessage())
                        .build()
        );
    }

    @PostMapping("/change-password")
    public ResponseEntity<PasswordChangeResponse> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest httpRequest) {

        return ResponseEntity.ok(
                authenticationService.changePassword(
                        request,
                        httpRequest
                )
        );
    }
}