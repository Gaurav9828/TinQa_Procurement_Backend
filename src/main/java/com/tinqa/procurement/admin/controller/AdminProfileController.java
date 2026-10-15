package com.tinqa.procurement.admin.controller;

import com.tinqa.procurement.admin.dto.AdminProfileResponse;
import com.tinqa.procurement.admin.dto.AdminProfileUpdateRequest;
import com.tinqa.procurement.admin.dto.ProfileUpdateResult;
import com.tinqa.procurement.admin.service.AdminProfileService;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.security.config.Authorities;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/profile")
@PreAuthorize(Authorities.ADMIN)
@RequiredArgsConstructor
public class AdminProfileController {

    private final AdminProfileService adminProfileService;

    @GetMapping
    public ResponseEntity<ApiResponse<AdminProfileResponse>> getProfile() {
        return ApiResponses.ok(adminProfileService.getProfile(), "Profile fetched successfully");
    }

    @PutMapping
    public ResponseEntity<ApiResponse<AdminProfileResponse>> updateProfile(@Valid @RequestBody AdminProfileUpdateRequest request) {
        ProfileUpdateResult result = adminProfileService.updateProfile(request);
        return ApiResponses.ok(result.getProfile(), result.isApprovalRequired()
                ? "Profile Changes Submitted For Approval"
                : "Profile Changes Saved Successfully");
    }
}
