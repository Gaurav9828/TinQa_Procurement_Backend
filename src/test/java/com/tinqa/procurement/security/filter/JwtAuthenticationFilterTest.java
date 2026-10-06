package com.tinqa.procurement.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.impl.UserDetailsServiceImpl;
import com.tinqa.procurement.security.service.jwt.JwtService;
import io.jsonwebtoken.MalformedJwtException;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    @Test
    void invalidBearerTokenReturns401WithStandardEnvelope() throws ServletException, IOException {
        JwtService jwtService = mock(JwtService.class);
        when(jwtService.extractUsername("invalid-token"))
                .thenThrow(new MalformedJwtException("invalid token"));
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtService,
                mock(UserDetailsServiceImpl.class),
                mock(UserRepository.class),
                new ObjectMapper().findAndRegisterModules()
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
        request.addHeader("Authorization", "Bearer invalid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        Map<?, ?> body = new ObjectMapper().findAndRegisterModules()
                .readValue(response.getContentAsString(), Map.class);
        assertEquals(false, body.get("success"));
        assertEquals("UNAUTHORIZED", body.get("errorCode"));
        assertEquals(request.getRequestURI(), body.get("path"));
        assertFalse(((Map<?, ?>) body).containsKey("exception"));
    }
}
