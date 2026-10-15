package com.tinqa.procurement.security.service;

import com.tinqa.procurement.security.dto.ChangePasswordRequest;
import com.tinqa.procurement.security.dto.FirstLoginPasswordRequest;
import com.tinqa.procurement.security.dto.PasswordChangeResponse;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Passwords the signed-in user sets for themselves. Setting one signs out every existing session.
 */
public interface PasswordService {

    PasswordChangeResponse changePassword(ChangePasswordRequest request, HttpServletRequest httpRequest);

    PasswordChangeResponse setFirstLoginPassword(FirstLoginPasswordRequest request, HttpServletRequest httpRequest);
}
