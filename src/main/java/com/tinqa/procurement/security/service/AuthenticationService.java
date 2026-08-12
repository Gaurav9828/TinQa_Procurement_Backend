package com.tinqa.procurement.security.service;

import com.tinqa.procurement.security.dto.*;
import jakarta.servlet.http.HttpServletRequest;

public interface AuthenticationService {

    LoginResponse authenticateAdmin(AdminLoginRequest request);

    LoginResponse authenticateMobile(MobileLoginRequest request);

    LogoutResponse logout(HttpServletRequest request);

    PasswordChangeResponse changePassword(ChangePasswordRequest request, HttpServletRequest httpRequest);

}