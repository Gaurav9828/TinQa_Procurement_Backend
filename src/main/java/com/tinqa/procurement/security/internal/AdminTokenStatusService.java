package com.tinqa.procurement.security.internal;

import com.tinqa.procurement.security.access.AccessResolver;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.jwt.JwtService;
import com.tinqa.procurement.security.service.jwt.TokenClaims;
import com.tinqa.procurement.security.service.token.TokenValidity;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies this service's own token rules (the request filter's) to a token another TinQa service was handed. */
@Service
@RequiredArgsConstructor
public class AdminTokenStatusService {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final TokenValidity tokenValidity;
    private final AccessResolver accessResolver;

    @Transactional(readOnly = true)
    public AdminTokenStatusDTOs.Response status(String token) {
        TokenClaims claims;
        try {
            claims = jwtService.parse(token);
        } catch (JwtException | IllegalArgumentException invalid) {
            return AdminTokenStatusDTOs.Response.inactive();
        }
        User user = userRepository.findByUsername(claims.username()).orElse(null);
        if (user == null || !user.isEnabled() || user.isFirstLogin() || tokenValidity.isRevoked(claims, user)) {
            return AdminTokenStatusDTOs.Response.inactive();
        }
        return new AdminTokenStatusDTOs.Response(true, user.getRole().name(),
                accessResolver.resolve(user).permissions().stream().map(Enum::name).sorted().toList());
    }
}
