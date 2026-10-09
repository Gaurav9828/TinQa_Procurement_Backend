package com.tinqa.procurement.common.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class RequestInputSafetyFilterTest {

    private final RequestInputSafetyFilter filter = new RequestInputSafetyFilter(new ObjectMapper().findAndRegisterModules());

    @Test
    void scriptInQueryParameterIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/employees");
        request.addParameter("search", "<script>alert(1)</script>");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("UNSAFE_INPUT"));
        assertNull(chain.getRequest());
    }

    @Test
    void encodedPathTraversalIsRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/documents/%2e%2e/secret"), response, new MockFilterChain());
        assertEquals(400, response.getStatus());
    }

    @Test
    void ordinaryRequestPassesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/employees");
        request.addParameter("search", "Rohan Mehta");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
    }
}
