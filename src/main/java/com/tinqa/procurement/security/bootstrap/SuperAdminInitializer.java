package com.tinqa.procurement.security.bootstrap;

import com.tinqa.procurement.common.constant.AppConstants;
import com.tinqa.procurement.common.validation.PhoneNumbers;
import com.tinqa.procurement.employee.enums.EmployeeStatus;
import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.security.access.Designation;
import com.tinqa.procurement.security.model.AuthClient;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Creates the configured super admin (an active Admin L2 with an employee profile) so an empty database can be
 * signed into. An existing account is never changed, so its password can be changed in the application.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuperAdminInitializer implements ApplicationRunner {

    private final SuperAdminProperties properties;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isConfigured()) {
            log.info("No super admin configured (tinqa.super-admin); skipping bootstrap account");
            return;
        }
        if (userRepository.existsByUsernameIgnoreCase(properties.username())) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        User admin = userRepository.saveAndFlush(User.builder()
                .username(properties.username().trim())
                .email(properties.email().trim().toLowerCase(Locale.ROOT))
                .password(passwordEncoder.encode(properties.password()))
                .role(Role.ADMIN_L2)
                .authClient(AuthClient.WEB)
                .enabled(true)
                .accountNonLocked(true)
                // Ready to use: the password comes from configuration, not a temporary one
                .isFirstLogin(false)
                .createdAt(now)
                .updatedAt(now)
                .build());

        employeeRepository.lockEmployeeCodeSequence();
        employeeRepository.save(Employee.builder()
                .userId(admin.getId())
                .employeeCode(String.format(AppConstants.EMPLOYEE_CODE_FORMAT, employeeRepository.findHighestEmployeeCodeNumber() + 1))
                .firstName(properties.firstName())
                .lastName(properties.lastName())
                .displayName(properties.firstName() + " " + properties.lastName())
                .designation(Designation.SUPER_ADMIN)
                .department(Designation.SUPER_ADMIN.department())
                .employmentType("FULL_TIME")
                .joiningDate(LocalDate.now(clock))
                .salaryCurrency(AppConstants.DEFAULT_CURRENCY)
                .phone(PhoneNumbers.normalize(properties.phone()))
                .status(EmployeeStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build());

        log.info("Created super admin account '{}'", admin.getUsername());
    }
}
