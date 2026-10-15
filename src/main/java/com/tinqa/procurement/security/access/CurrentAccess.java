package com.tinqa.procurement.security.access;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.security.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Permission checks inside services, for rules that depend on who is acting (for example, whether what they create
 * needs a second person's approval). Endpoint-level checks use {@link RequiresPermission} instead.
 */
@Component
@RequiredArgsConstructor
public class CurrentAccess {

    private final AccessResolver accessResolver;

    /** Whether the signed-in caller holds the permission, from the authorities set up for this request. */
    public boolean has(Permission permission) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        String authority = permission.authority();
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            if (authority.equals(granted.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /** Whether a given account (not necessarily the caller) holds the permission. */
    public boolean has(User user, Permission permission) {
        return accessResolver.resolve(user).has(permission);
    }

    /** Refuses with 403 unless the caller holds the permission. */
    public void require(Permission permission, String action) {
        if (!has(permission)) {
            throw ApiErrors.forbidden(ErrorCodes.FORBIDDEN, "You don't have permission to " + action + ".");
        }
    }
}
