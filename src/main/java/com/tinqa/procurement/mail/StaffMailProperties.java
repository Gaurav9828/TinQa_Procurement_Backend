package com.tinqa.procurement.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param baseUrl        the e-commerce service, including its /api path; it sends staff emails through its mail server
 * @param connectTimeout how long to wait for a connection to it
 * @param readTimeout    how long to wait for its answer (it talks to the mail server before answering)
 */
@ConfigurationProperties(prefix = "tinqa.ecommerce")
public record StaffMailProperties(@DefaultValue("http://localhost:8080/api") String baseUrl,
                                  @DefaultValue("3s") Duration connectTimeout,
                                  @DefaultValue("30s") Duration readTimeout) {
}
