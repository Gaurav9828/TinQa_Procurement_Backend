package com.tinqa.procurement.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class InternalApiKeyFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validKeyAuthenticatesAsInternalService() throws ServletException, IOException {
        MockHttpServletResponse response = run(new InternalApiKeyFilter("secret", objectMapper), "secret");

        assertEquals(200, response.getStatus());
        assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(InternalApiKeyFilter.ROLE)));
    }

    @Test
    void wrongOrMissingKeyIsRejected() throws ServletException, IOException {
        assertEquals(401, run(new InternalApiKeyFilter("secret", objectMapper), "wrong").getStatus());
        assertEquals(401, run(new InternalApiKeyFilter("secret", objectMapper), null).getStatus());
    }

    @Test
    void unconfiguredKeyRejectsEveryCall() throws ServletException, IOException {
        assertEquals(401, run(new InternalApiKeyFilter("", objectMapper), "").getStatus());
    }

    @Test
    void nonInternalPathsAreNotFiltered() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/stocks");
        request.setContextPath("/api");
        assertTrue(new InternalApiKeyFilter("secret", objectMapper).shouldNotFilter(request));
    }

    private MockHttpServletResponse run(InternalApiKeyFilter filter, String key) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/internal/item-stock/movements");
        request.setContextPath("/api");
        if (key != null) {
            request.addHeader(InternalApiKeyFilter.HEADER, key);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
