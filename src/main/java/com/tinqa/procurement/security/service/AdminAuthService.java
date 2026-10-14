package com.tinqa.procurement.security.service;

import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
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
    private final EmployeeRepository employeeRepository;
    private final AuthenticationService authenticationService;

    public User authenticate(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BadRequestException("Invalid username or password"));

        // Password first, so account state is only revealed to someone who knows the password
        if ((user.getRole() != Role.ADMIN_L1 && user.getRole() != Role.ADMIN_L2) || user.getAuthClient() != AuthClient.WEB) {
            throw new BadRequestException("Invalid username or password");
        }
        validatePassword(request.getPassword(), user.getPassword());
        validateAdmin(user);

        return user;
    }

    private void validateAdmin(User user) {
        if ((user.getRole() != Role.ADMIN_L1 && user.getRole() != Role.ADMIN_L2) || user.getAuthClient() != AuthClient.WEB) {
            throw new BadRequestException("Invalid username or password");
        }

        if (!user.isEnabled()) {
            throw new BadRequestException(disabledReason(user));
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

    public LoginResponse login(AdminLoginRequest request) {
        return authenticationService.authenticateAdmin(request);
    }

    private String disabledReason(User user) {
        return employeeRepository.findByUserId(user.getId())
                .map(employee -> switch (employee.getStatus()) {
                    case APPROVAL_PENDING -> "Your account is waiting for Admin L2 approval. You can sign in once it is approved.";
                    case IN_ACTIVE -> employee.getApprovalRejectionReason() != null
                            ? "Your account request was not approved. Please contact your administrator."
                            : "User account is disabled";
                    default -> "User account is disabled";
                })
                .orElse("User account is disabled");
    }
}
