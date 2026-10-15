package com.tinqa.procurement.security.service.impl;

import com.tinqa.procurement.common.exception.UnauthorizedException;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CurrentUserProviderImpl implements CurrentUserProvider {

    private static final String AUTHENTICATION_REQUIRED = "Authentication is required";

    private final UserRepository userRepository;

    @Override
    public User getCurrentUser() {
        return userRepository.findByUsername(getCurrentUsername())
                .orElseThrow(() -> new UnauthorizedException(AUTHENTICATION_REQUIRED));
    }

    @Override
    public Long getCurrentUserId() {
        return getCurrentUser().getId();
    }

    @Override
    public String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken || authentication.getName() == null) {
            throw new UnauthorizedException(AUTHENTICATION_REQUIRED);
        }
        return authentication.getName();
    }
}
