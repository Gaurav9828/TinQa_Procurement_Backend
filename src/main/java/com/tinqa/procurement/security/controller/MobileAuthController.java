package com.tinqa.procurement.security.controller;

import com.tinqa.procurement.security.dto.LoginResponse;
import com.tinqa.procurement.security.dto.MobileLoginRequest;
import com.tinqa.procurement.security.service.MobileAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth/mobile")
@RequiredArgsConstructor
public class MobileAuthController {

    private final MobileAuthService mobileAuthService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody MobileLoginRequest request) {
        return ResponseEntity.ok(mobileAuthService.login(request));
    }
}