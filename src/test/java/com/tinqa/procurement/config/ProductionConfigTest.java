package com.tinqa.procurement.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the production configuration that is easy to lose: the upload limit matches the 25 MB the validator allows
 * (Spring's own default is 1 MB), the pool is sized, Redis must be configured, and the API docs are off in production.
 */
class ProductionConfigTest {

    @Test
    void uploadsUpTo25MbAreAcceptedInEveryProfile() throws IOException {
        PropertySource<?> base = load("application.yml");
        assertThat(value(base, "spring.servlet.multipart.max-file-size")).isEqualTo("25MB");
        assertThat(value(base, "spring.servlet.multipart.max-request-size")).isEqualTo("30MB");
        for (String profile : List.of("application-dev.yml", "application-uat.yml", "application-prod.yml")) {
            assertThat(load(profile).getProperty("spring.servlet.multipart.max-file-size")).as(profile).isNull();
        }
    }

    @Test
    void theConnectionPoolIsSizedExplicitly() throws IOException {
        assertThat(value(load("application.yml"), "spring.datasource.hikari.maximum-pool-size")).isNotNull();
    }

    @Test
    void productionAndUatNeedARedisHost() throws IOException {
        assertThat(value(load("application-prod.yml"), "spring.data.redis.host")).isEqualTo("${REDIS_HOST}");
        assertThat(value(load("application-uat.yml"), "spring.data.redis.host")).isEqualTo("${REDIS_HOST}");
    }

    @Test
    void apiDocsAndSwaggerAreOffInProduction() throws IOException {
        PropertySource<?> prod = load("application-prod.yml");
        assertThat(value(prod, "springdoc.api-docs.enabled")).isEqualTo("${TINQA_API_DOCS_ENABLED:false}");
        assertThat(value(prod, "springdoc.swagger-ui.enabled")).isEqualTo("${TINQA_API_DOCS_ENABLED:false}");
    }

    private static String value(PropertySource<?> source, String key) {
        Object value = source.getProperty(key);
        return value == null ? null : value.toString();
    }

    private static PropertySource<?> load(String file) throws IOException {
        return new YamlPropertySourceLoader().load(file, new ClassPathResource(file)).getFirst();
    }
}
