package com.tinqa.procurement.common.validation;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InputSafetyJacksonConfig {

    // Spring Boot registers Module beans on the application ObjectMapper
    @Bean
    public Module inputSafetyModule() {
        SimpleModule module = new SimpleModule("InputSafety");
        module.addDeserializer(String.class, new SafeStringDeserializer());
        return module;
    }
}
