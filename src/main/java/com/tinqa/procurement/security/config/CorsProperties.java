package com.tinqa.procurement.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * @param allowedOrigins browser origins allowed to call the API (the admin console)
 */
@ConfigurationProperties(prefix = "tinqa.cors")
public record CorsProperties(@DefaultValue("http://localhost:5173") List<String> allowedOrigins) {
}
