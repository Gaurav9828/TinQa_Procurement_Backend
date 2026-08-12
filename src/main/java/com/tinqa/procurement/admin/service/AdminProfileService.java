package com.tinqa.procurement.admin.service;

import com.tinqa.procurement.admin.dto.AdminProfileResponse;
import com.tinqa.procurement.admin.dto.AdminProfileUpdateRequest;
import com.tinqa.procurement.admin.dto.ProfileUpdateResult;
import com.tinqa.procurement.employee.model.Employee;

public interface AdminProfileService {

    AdminProfileResponse getProfile();

    AdminProfileResponse updateProfile(AdminProfileUpdateRequest request);

    boolean hasApprovalChangesInCurrentRequest(Employee employee, AdminProfileUpdateRequest request);

    ProfileUpdateResult updateProfileWithResult(AdminProfileUpdateRequest request);
}