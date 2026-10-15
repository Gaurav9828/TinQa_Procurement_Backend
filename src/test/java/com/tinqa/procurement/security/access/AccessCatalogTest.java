package com.tinqa.procurement.security.access;

import com.tinqa.procurement.common.validation.ValidationPatterns;
import com.tinqa.procurement.security.controller.AdminAuthController;
import com.tinqa.procurement.security.controller.AuthController;
import com.tinqa.procurement.security.controller.MobileAuthController;
import com.tinqa.procurement.security.model.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rules the access catalog must keep as it grows. Plain unit tests: no Spring context, no database.
 */
class AccessCatalogTest {

    // Sign-in, sign-out and password endpoints are reachable before or without permissions (see SecurityConfig)
    private static final Set<Class<?>> UNGUARDED_CONTROLLERS = Set.of(AdminAuthController.class, MobileAuthController.class,
            AuthController.class);

    @Test
    void superAdminHoldsEveryInternalPermission() {
        Set<Permission> expected = EnumSet.allOf(Permission.class);
        expected.removeIf(permission -> permission.group() == PermissionGroup.DEALER_PORTAL);

        assertThat(UserAccess.of(Role.ADMIN_L2, Designation.SUPER_ADMIN).permissions()).isEqualTo(expected);
    }

    @Test
    void levelOneNeverHoldsAnApprovalGroup() {
        for (Designation designation : Designation.values()) {
            assertThat(DesignationAccess.effectiveGroups(Role.ADMIN_L1, designation))
                    .as(designation.name())
                    .noneMatch(PermissionGroup::approvalLevel);
        }
    }

    @Test
    void everyGroupIsGrantedToSomeone() {
        Set<PermissionGroup> granted = EnumSet.noneOf(PermissionGroup.class);
        Arrays.stream(Designation.values()).forEach(designation -> granted.addAll(DesignationAccess.groupsOf(designation)));
        granted.addAll(DesignationAccess.effectiveGroups(Role.DEALER, null));

        assertThat(granted).containsExactlyInAnyOrder(PermissionGroup.values());
    }

    @Test
    void everyGroupHasPermissions() {
        for (PermissionGroup group : PermissionGroup.values()) {
            assertThat(Arrays.stream(Permission.values()).anyMatch(permission -> permission.group() == group))
                    .as(group.name())
                    .isTrue();
        }
    }

    @Test
    void approvalGroupsFollowTheNamingScheme() {
        for (PermissionGroup group : PermissionGroup.values()) {
            if (group != PermissionGroup.SYSTEM_ADMINISTRATION) {
                assertThat(group.approvalLevel()).as(group.name()).isEqualTo(group.name().endsWith("_APPROVAL"));
            }
        }
    }

    @Test
    void nonEmployeesNeverGetEmployeePermissions() {
        assertThat(DesignationAccess.effectiveGroups(Role.INSPECTOR, Designation.SUPER_ADMIN)).isEmpty();
        assertThat(DesignationAccess.effectiveGroups(Role.DEALER, Designation.SUPER_ADMIN)).containsExactly(PermissionGroup.DEALER_PORTAL);
        assertThat(DesignationAccess.effectiveGroups(Role.ADMIN_L2, null)).isEmpty();
    }

    @Test
    void levelTwoIsOnlyKeptWhereItAddsApprovalPowers() {
        assertThat(DesignationAccess.hasApprovalLevel(Designation.STORE_MANAGER)).isTrue();
        assertThat(DesignationAccess.hasApprovalLevel(Designation.DELIVERY_RIDER)).isFalse();
        assertThat(DesignationAccess.effectiveLevel(Role.ADMIN_L2, Designation.DELIVERY_RIDER)).isEqualTo(Role.ADMIN_L1);
        assertThat(DesignationAccess.effectiveLevel(Role.ADMIN_L2, Designation.DEALER_MANAGER)).isEqualTo(Role.ADMIN_L2);
        assertThat(DesignationAccess.effectiveLevel(Role.INSPECTOR, Designation.DELIVERY_RIDER)).isEqualTo(Role.INSPECTOR);
    }

    // Riders record delivery outcomes only; changing any other status needs CUSTOMER_ORDER_TRACKING_UPDATE
    @Test
    void deliveryRidersOnlyRecordDeliveryOutcomes() {
        for (Role level : List.of(Role.ADMIN_L1, Role.ADMIN_L2)) {
            assertThat(UserAccess.of(level, Designation.DELIVERY_RIDER).permissions())
                    .containsExactlyInAnyOrder(Permission.CUSTOMER_ORDER_VIEW, Permission.DELIVERY_STATUS_UPDATE);
        }
        assertThat(UserAccess.of(Role.ADMIN_L1, Designation.FLEET_COORDINATOR).permissions())
                .contains(Permission.CUSTOMER_ORDER_TRACKING_UPDATE);
    }

    @Test
    void designationPatternAcceptsExactlyTheDesignationCodes() {
        for (Designation designation : Designation.values()) {
            assertThat(designation.name().matches(ValidationPatterns.DESIGNATION)).as(designation.name()).isTrue();
            assertThat(designation.name().toLowerCase().matches(ValidationPatterns.DESIGNATION)).isTrue();
        }
        assertThat("Engineer".matches(ValidationPatterns.DESIGNATION)).isFalse();
        assertThat("SUPER_ADMIN|X".matches(ValidationPatterns.DESIGNATION)).isFalse();
        assertThat(ValidationPatterns.DESIGNATION.split("\\|")).hasSize(Designation.values().length);
    }

    // A new endpoint must say who may call it, normally with @RequiresPermission, so nothing ships unguarded
    @Test
    void everyEndpointDeclaresAnAccessRule() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<String> unguarded = new ArrayList<>();
        int endpoints = 0;
        for (BeanDefinition definition : scanner.findCandidateComponents("com.tinqa.procurement")) {
            Class<?> controller = Class.forName(definition.getBeanClassName());
            if (UNGUARDED_CONTROLLERS.contains(controller)) {
                continue;
            }
            boolean classRule = AnnotatedElementUtils.hasAnnotation(controller, PreAuthorize.class);
            for (Method method : controller.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                    continue;
                }
                endpoints++;
                if (!classRule && !AnnotatedElementUtils.hasAnnotation(method, PreAuthorize.class)) {
                    unguarded.add(controller.getSimpleName() + "." + method.getName());
                }
            }
        }

        assertThat(endpoints).isGreaterThan(50);
        assertThat(unguarded).as("endpoints without @RequiresPermission or @PreAuthorize").isEmpty();
    }
}
