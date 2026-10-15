package com.tinqa.procurement.employee.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param defaultPassword temporary password of every new employee account; it must be changed at first login
 */
@ConfigurationProperties(prefix = "tinqa.employee")
public record EmployeeProperties(@DefaultValue("TinQa") String defaultPassword) {
}
