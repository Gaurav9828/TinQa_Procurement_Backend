package com.tinqa.procurement.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs (e.g. emailing confirmed payslips). Each job can be switched off by its own property. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
