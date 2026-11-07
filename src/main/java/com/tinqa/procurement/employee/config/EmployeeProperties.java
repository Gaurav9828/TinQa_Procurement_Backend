package com.tinqa.procurement.employee.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param defaultPassword temporary password of a new supplier login, changed at first login. Employees never get one: they
 *                        choose their password through an emailed link (PasswordLinkService). Required: set
 *                        TINQA_EMPLOYEE_DEFAULT_PASSWORD outside the dev/test profiles
 */
@ConfigurationProperties(prefix = "tinqa.employee")
public record EmployeeProperties(String defaultPassword) {

    public EmployeeProperties {
        if (defaultPassword == null || defaultPassword.isBlank()) {
            throw new IllegalStateException("tinqa.employee.default-password is not set: set TINQA_EMPLOYEE_DEFAULT_PASSWORD");
        }
    }
}
