package com.tinqa.procurement.admin.service;

import com.tinqa.procurement.admin.dto.AdminProfileResponse;
import com.tinqa.procurement.admin.dto.AdminProfileUpdateRequest;
import com.tinqa.procurement.admin.dto.ProfileUpdateResult;

/**
 * The signed-in administrator's own employee profile.
 */
public interface AdminProfileService {

    AdminProfileResponse getProfile();

    /**
     * Contact details change at once. Approval-controlled fields change at once for Admin L2; for Admin L1 they
     * become a change request that waits for Admin L2 ({@link ProfileUpdateResult#isApprovalRequired()}).
     */
    ProfileUpdateResult updateProfile(AdminProfileUpdateRequest request);
}
