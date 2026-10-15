package com.tinqa.procurement.security.controller;

import com.tinqa.procurement.security.dto.AdminLoginRequest;
import com.tinqa.procurement.security.dto.LoginResponse;
import com.tinqa.procurement.security.service.AuthenticationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/admin")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AuthenticationService authenticationService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody AdminLoginRequest request) {
        return ResponseEntity.ok(authenticationService.loginAdmin(request));
    }
}
