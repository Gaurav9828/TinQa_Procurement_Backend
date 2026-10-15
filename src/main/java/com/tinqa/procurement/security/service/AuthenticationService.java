package com.tinqa.procurement.security.service;

import com.tinqa.procurement.security.dto.AdminLoginRequest;
import com.tinqa.procurement.security.dto.LoginResponse;
import com.tinqa.procurement.security.dto.LogoutResponse;
import com.tinqa.procurement.security.dto.MobileLoginRequest;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Signing in and out. Password changes are in {@link PasswordService}.
 */
public interface AuthenticationService {

    LoginResponse loginAdmin(AdminLoginRequest request);

    LoginResponse loginMobile(MobileLoginRequest request);

    /** Revokes the bearer token of the request, if any. Idempotent. */
    LogoutResponse logout(HttpServletRequest request);
}
