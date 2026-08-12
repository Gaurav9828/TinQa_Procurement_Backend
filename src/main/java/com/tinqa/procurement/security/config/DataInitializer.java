package com.tinqa.procurement.security.config;

import com.tinqa.procurement.security.model.AuthClient;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${tinqa.initial-admin.username}")
    private String username;

    @Value("${tinqa.initial-admin.email}")
    private String email;

    @Value("${tinqa.initial-admin.password}")
    private String password;

    @Override
    public void run(String... args) {
        if (userRepository.existsByRole(Role.ADMIN_L1) || userRepository.existsByRole(Role.ADMIN_L2)) {
            return;
        }
    }
}