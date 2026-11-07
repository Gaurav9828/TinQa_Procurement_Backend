package com.tinqa.procurement.employee.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param defaultPassword temporary password of a new supplier login, changed at first login. Employees never get one: they
 *                        choose their password through an emailed link (PasswordLinkService)
 */
@ConfigurationProperties(prefix = "tinqa.employee")
public record EmployeeProperties(@DefaultValue("TinQa") String defaultPassword) {
}
