package com.tinqa.procurement.security.service;

import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.security.dto.AdminLoginRequest;
import com.tinqa.procurement.security.dto.LoginRequest;
import com.tinqa.procurement.security.dto.LoginResponse;
import com.tinqa.procurement.security.model.AuthClient;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationService authenticationService;

    public User authenticate(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BadRequestException("Invalid username or password"));

        validateAdmin(user);
        validatePassword(request.getPassword(), user.getPassword());

        return user;
    }

    private void validateAdmin(User user) {
        if ((user.getRole() != Role.ADMIN_L1 && user.getRole() != Role.ADMIN_L2) || user.getAuthClient() != AuthClient.WEB) {
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
        System.out.println(passwordEncoder.encode("Admin@123"));
        if (!passwordEncoder.matches(rawPassword, encodedPassword)) {
            throw new BadRequestException("Invalid username or password");
        }
    }

    public LoginResponse login(AdminLoginRequest request) {
        return authenticationService.authenticateAdmin(request);
    }
}