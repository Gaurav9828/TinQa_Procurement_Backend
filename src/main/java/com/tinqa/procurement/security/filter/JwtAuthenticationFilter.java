package com.tinqa.procurement.security.filter;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.web.JsonErrorResponseWriter;
import com.tinqa.procurement.security.access.AccessResolver;
import com.tinqa.procurement.security.config.SecurityPaths;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.impl.UserDetailsServiceImpl;
import com.tinqa.procurement.security.service.jwt.JwtService;
import com.tinqa.procurement.security.service.jwt.TokenClaims;
import com.tinqa.procurement.security.service.token.TokenRevocationService;
import com.tinqa.procurement.security.web.BearerTokenResolver;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * Authenticates requests that carry a bearer token. The account is re-read from the database on every request,
 * so revocation, password changes and the first-login state take effect immediately.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String INVALID_TOKEN_MESSAGE = "The access token is invalid or has expired.";
    private static final String PASSWORD_CHANGE_REQUIRED_MESSAGE = "Set a new password before continuing.";

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final TokenRevocationService tokenRevocationService;
    private final JsonErrorResponseWriter errorWriter;
    private final AccessResolver accessResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Optional<String> token = BearerTokenResolver.resolve(request);
        if (token.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        TokenClaims claims;
        try {
            claims = jwtService.parse(token.get());
        } catch (JwtException | IllegalArgumentException exception) {
            writeUnauthorized(request, response);
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            User user = userRepository.findByUsername(claims.username()).orElse(null);
            // A disabled account (e.g. a deleted employee) loses its open sessions at once
            if (user == null || !user.isEnabled()) {
                writeUnauthorized(request, response);
                return;
            }

            String applicationPath = BearerTokenResolver.applicationPath(request);
            if (isRevoked(claims, user)) {
                // Logout stays idempotent: a token that is already dead just goes through unauthenticated
                if (SecurityPaths.LOGOUT.equals(applicationPath)) {
                    filterChain.doFilter(request, response);
                    return;
                }
                writeUnauthorized(request, response);
                return;
            }

            if (user.isFirstLogin() && !SecurityPaths.FIRST_LOGIN_ALLOWED.contains(applicationPath)) {
                errorWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN, ErrorCodes.PASSWORD_CHANGE_REQUIRED,
                        PASSWORD_CHANGE_REQUIRED_MESSAGE);
                return;
            }

            // Permissions come from the current designation and level, not from the token, so changes apply at once
            UserDetails userDetails = UserDetailsServiceImpl.toUserDetails(user, accessResolver.resolve(user));
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        errorWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, INVALID_TOKEN_MESSAGE);
    }

    // Logged out, or issued before the last password change. Compared in milliseconds (the precision tokens carry):
    // a session opened earlier in the same second as the change is signed out, one opened right after it is not.
    private boolean isRevoked(TokenClaims claims, User user) {
        if (tokenRevocationService.isRevoked(claims.jti())) {
            return true;
        }
        Instant passwordChangedAt = user.getPasswordChangedAt();
        if (passwordChangedAt == null) {
            return false;
        }
        return claims.issuedAt() == null || claims.issuedAt().toEpochMilli() < passwordChangedAt.toEpochMilli();
    }
}
