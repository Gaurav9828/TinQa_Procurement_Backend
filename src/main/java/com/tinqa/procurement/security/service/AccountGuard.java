package com.tinqa.procurement.security.service;

import com.tinqa.procurement.common.constant.AppConstants;
import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;

import java.util.Set;

/**
 * Checks on the signed-in account that services repeat on top of the endpoint's role rule.
 */
public final class AccountGuard {

    private AccountGuard() {
    }

    public static void requireRole(User user, Set<Role> allowedRoles, String message) {
        if (user == null || !allowedRoles.contains(user.getRole())) {
            throw new BadRequestException(message);
        }
    }

    public static void requireActive(User user) {
        if (!user.isEnabled()) {
            throw new BadRequestException(AppConstants.MESSAGE_ACCOUNT_DISABLED);
        }
        if (!user.isAccountNonLocked()) {
            throw new BadRequestException(AppConstants.MESSAGE_ACCOUNT_LOCKED);
        }
    }
}
