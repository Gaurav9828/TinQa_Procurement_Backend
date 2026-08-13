package com.tinqa.procurement.admin.controller;

import com.tinqa.procurement.admin.dto.AdminProfileResponse;
import com.tinqa.procurement.admin.dto.AdminProfileUpdateRequest;
import com.tinqa.procurement.admin.dto.ProfileUpdateResult;
import com.tinqa.procurement.admin.service.AdminProfileService;
import com.tinqa.procurement.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/admin/profile")
@RequiredArgsConstructor
public class AdminProfileController {

    private final AdminProfileService adminProfileService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN_L1', 'ADMIN_L2')")
    public ResponseEntity<ApiResponse<AdminProfileResponse>> getProfile(
            HttpServletRequest httpRequest) {

        AdminProfileResponse response =
                adminProfileService.getProfile();

        return ResponseEntity.ok(
                ApiResponse.<AdminProfileResponse>builder()
                        .success(true)
                        .message("Profile fetched successfully")
                        .data(response)
                        .path(httpRequest.getRequestURI())
                        .build()
        );
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN_L1', 'ADMIN_L2')")
    public ResponseEntity<ApiResponse<AdminProfileResponse>> updateProfile(
            @Valid @RequestBody AdminProfileUpdateRequest request,
            HttpServletRequest httpRequest) {

        ProfileUpdateResult result =
                adminProfileService.updateProfileWithResult(request);

        String message =
                result.isApprovalRequired()
                        ? "Profile Changes Submitted For Approval"
                        : "Profile Changes Saved Successfully";

        return ResponseEntity.ok(
                ApiResponse.<AdminProfileResponse>builder()
                        .success(true)
                        .message(message)
                        .data(result.getProfile())
                        .path(httpRequest.getRequestURI())
                        .build()
        );
    }
}