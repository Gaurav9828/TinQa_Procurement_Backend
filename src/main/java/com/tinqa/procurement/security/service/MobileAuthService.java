package com.tinqa.procurement.security.service;

import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.security.dto.LoginRequest;
import com.tinqa.procurement.security.dto.LoginResponse;
import com.tinqa.procurement.security.dto.MobileLoginRequest;
import com.tinqa.procurement.security.model.AuthClient;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MobileAuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationService authenticationService;

    public User authenticate(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BadRequestException("Invalid username or password"));

        validateMobileUser(user);
        validatePassword(request.getPassword(), user.getPassword());

        return user;
    }

    private void validateMobileUser(User user) {
        if (user.getAuthClient() != AuthClient.MOBILE) {
            throw new BadRequestException("Invalid username or password");
        }

        if (user.getRole() != Role.DEALER && user.getRole() != Role.INSPECTOR) {
            throw new BadRequestException("Invalid username or password");
        }

        if (!user.isEnabled()) {
            throw new BadRequestException("User account is disabled");
        }

        if (!user.isAccountNonLocked()) {
            throw new BadRequestException("User account is locked");
        }
    }

    private void validatePassword(String rawPassword, String encodedPassword) {
        if (!passwordEncoder.matches(rawPassword, encodedPassword)) {
            throw new BadRequestException("Invalid username or password");
        }
    }

    public LoginResponse login(MobileLoginRequest request) {
        return authenticationService.authenticateMobile(request);
    }
}