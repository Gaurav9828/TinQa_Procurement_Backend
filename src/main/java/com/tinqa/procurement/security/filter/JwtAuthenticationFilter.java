package com.tinqa.procurement.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.impl.UserDetailsServiceImpl;
import com.tinqa.procurement.security.service.jwt.JwtService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsServiceImpl userDetailsService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

        String authorizationHeader = request.getHeader("Authorization");

        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authorizationHeader.substring(7);
        String username;

        try {
            username = jwtService.extractUsername(token);
        } catch (JwtException | IllegalArgumentException exception) {
            writeUnauthorized(request, response);
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            UserDetails userDetails;
            try {
                userDetails = userDetailsService.loadUserByUsername(username);
            } catch (UsernameNotFoundException exception) {
                writeUnauthorized(request, response);
                return;
            }

            boolean valid;
            try {
                valid = jwtService.isTokenValid(token, userDetails);
            } catch (JwtException | IllegalArgumentException exception) {
                writeUnauthorized(request, response);
                return;
            }
            if (!valid) {
                writeUnauthorized(request, response);
                return;
            }

            User user = userRepository.findByUsername(username).orElse(null);
            String path = request.getRequestURI();
            // Request URI includes the servlet context path (/api); the allow-list is relative to it
            String applicationPath = path.substring(request.getContextPath().length());
            if (user != null && user.isFirstLogin() && !isAllowedFirstLoginPath(applicationPath)) {
                ApiResponse<Void> body = ApiResponse.<Void>builder()
                        .success(false)
                        .message("Password change required before continuing.")
                        .errorCode("PASSWORD_CHANGE_REQUIRED")
                        .timestamp(Instant.now())
                        .path(path)
                        .errors(java.util.List.of())
                        .build();
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(body));
                return;
            }

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    userDetails,
                    null,
                    userDetails.getAuthorities()
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .success(false)
                .message("The access token is invalid or has expired.")
                .errorCode("UNAUTHORIZED")
                .timestamp(Instant.now())
                .path(request.getRequestURI())
                .errors(java.util.List.of())
                .build();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private boolean isAllowedFirstLoginPath(String path) {
        return path.equals("/auth/change-password")
                || path.equals("/auth/logout")
                || path.startsWith("/v1/admin/profile")
                || path.equals("/v1/admin/profile");
    }
}