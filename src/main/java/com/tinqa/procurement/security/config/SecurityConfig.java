package com.tinqa.procurement.security.config;

import com.tinqa.procurement.security.filter.InternalApiKeyFilter;
import com.tinqa.procurement.security.filter.JwtAuthenticationFilter;
import com.tinqa.procurement.security.handler.AccessDeniedHandlerImpl;
import com.tinqa.procurement.security.handler.AuthenticationEntryPointHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.annotation.AnnotationTemplateExpressionDefaults;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT security for the admin and mobile APIs, plus shared-secret authentication for /v1/internal/**.
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AuthenticationEntryPointHandler authenticationEntryPointHandler;
    private final AccessDeniedHandlerImpl accessDeniedHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final InternalApiKeyFilter internalApiKeyFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Uses the CorsConfigurationSource bean from CorsConfig
                .cors(cors -> {
                })
                // Tokens travel in the Authorization header, never in cookies, so CSRF does not apply
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPointHandler)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(SecurityPaths.PUBLIC).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Service-to-service only; admin JWTs are not enough
                        .requestMatchers(SecurityPaths.INTERNAL_API_PREFIX + "**").hasRole(Authorities.INTERNAL_SERVICE_ROLE)
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Shared-secret authentication for the internal API runs before JWT
                .addFilterBefore(internalApiKeyFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    // Fills the {value} placeholder of meta-annotations such as @RequiresPermission
    @Bean
    static AnnotationTemplateExpressionDefaults annotationTemplateExpressionDefaults() {
        return new AnnotationTemplateExpressionDefaults();
    }
}
