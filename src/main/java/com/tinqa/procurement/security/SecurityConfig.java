package com.tinqa.procurement.security;

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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AuthenticationEntryPointHandler authenticationEntryPointHandler;
    private final AccessDeniedHandlerImpl accessDeniedHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http) throws Exception {

        http

                /*
                 * CORS
                 *
                 * Uses the CorsConfigurationSource bean defined
                 * in CorsConfig.java.
                 */
                .cors(cors -> {})

                /*
                 * REST API with stateless JWT authentication.
                 * CSRF is not required because authentication is
                 * performed using the Authorization header.
                 */
                .csrf(AbstractHttpConfigurer::disable)

                /*
                 * No server-side HTTP sessions.
                 */
                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                /*
                 * Authentication / authorization exception handling.
                 */
                .exceptionHandling(exception ->
                        exception
                                .authenticationEntryPoint(
                                        authenticationEntryPointHandler
                                )
                                .accessDeniedHandler(
                                        accessDeniedHandler
                                )
                )

                /*
                 * Authorization rules.
                 */
                .authorizeHttpRequests(auth -> auth

                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/actuator/health",
                                "/auth/admin/login"
                        ).permitAll()

                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        .anyRequest().authenticated()
                )

                /*
                 * JWT authentication filter.
                 */
                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }
}