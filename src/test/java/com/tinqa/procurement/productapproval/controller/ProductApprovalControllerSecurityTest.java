package com.tinqa.procurement.productapproval.controller;

import com.tinqa.procurement.productapproval.dto.ProductApprovalDTOs;
import com.tinqa.procurement.productapproval.enums.ProductApprovalStatus;
import com.tinqa.procurement.productapproval.service.ProductApprovalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(ProductApprovalControllerSecurityTest.Config.class)
class ProductApprovalControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        ProductApprovalService productApprovalService() {
            ProductApprovalService service = mock(ProductApprovalService.class);
            ProductApprovalDTOs.Response decided = ProductApprovalDTOs.Response.builder().status(ProductApprovalStatus.APPROVED).build();
            when(service.decide(any(), any())).thenReturn(decided);
            when(service.getByStatus(any())).thenReturn(List.of());
            when(service.getLatestForProducts(any())).thenReturn(List.of());
            return service;
        }

        @Bean
        ProductApprovalController productApprovalController(ProductApprovalService service) {
            return new ProductApprovalController(service);
        }
    }

    @Autowired private ProductApprovalController controller;

    private final ProductApprovalDTOs.DecisionRequest approve =
            ProductApprovalDTOs.DecisionRequest.builder().decision(ProductApprovalStatus.APPROVED).build();

    @Test
    @WithMockUser(roles = "ADMIN_L1")
    void adminL1CannotDecideOrSeeTheQueue() {
        assertThrows(AccessDeniedException.class, () -> controller.decide(20L, approve, new MockHttpServletRequest()));
        assertThrows(AccessDeniedException.class,
                () -> controller.getByStatus(ProductApprovalStatus.PENDING, new MockHttpServletRequest()));
    }

    @Test
    @WithMockUser(roles = "ADMIN_L1")
    void adminL1CanReadLatestStatus() {
        assertDoesNotThrow(() -> controller.getLatestForProducts("1,2", new MockHttpServletRequest()));
    }

    @Test
    @WithMockUser(roles = "ADMIN_L2")
    void adminL2CanDecideAndSeeTheQueue() {
        assertDoesNotThrow(() -> controller.decide(20L, approve, new MockHttpServletRequest()));
        assertDoesNotThrow(() -> controller.getByStatus(ProductApprovalStatus.PENDING, new MockHttpServletRequest()));
    }

    @Test
    @WithMockUser(roles = "DEALER")
    void otherRolesCannotSubmit() {
        assertThrows(AccessDeniedException.class,
                () -> controller.submit(ProductApprovalDTOs.SubmitRequest.builder().build(), new MockHttpServletRequest()));
    }
}
