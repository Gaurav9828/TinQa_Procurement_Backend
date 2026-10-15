package com.tinqa.procurement.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param key shared secret other TinQa services send in the internal API key header; empty disables the internal API
 */
@ConfigurationProperties(prefix = "tinqa.internal-api")
public record InternalApiProperties(@DefaultValue("") String key) {
}
