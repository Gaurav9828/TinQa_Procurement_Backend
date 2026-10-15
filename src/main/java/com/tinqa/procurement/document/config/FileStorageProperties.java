package com.tinqa.procurement.document.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param uploadDir directory uploaded documents are stored under
 */
@ConfigurationProperties(prefix = "file")
public record FileStorageProperties(@DefaultValue("./uploads") String uploadDir) {
}
