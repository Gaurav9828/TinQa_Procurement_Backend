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
                    .containsExactlyInAnyOrder(Permission.CUSTOMER_ORDER_VIEW, Permission.DELIVERY_STATUS_UPDATE,
                            Permission.PAYSLIP_VIEW_OWN, Permission.DEMO_VIEW_OWN, Permission.DEMO_EXPENSE_RECORD_OWN,
                            Permission.DEMO_UNITS_HANDLE_OWN);
        }
    }

    // The riders' senior records the same delivery outcomes, plus the rest of the delivery leg
    @Test
    void fleetCoordinatorsRecordDeliveryOutcomesToo() {
        assertThat(UserAccess.of(Role.ADMIN_L1, Designation.FLEET_COORDINATOR).permissions())
                .contains(Permission.DELIVERY_STATUS_UPDATE, Permission.CUSTOMER_ORDER_TRACKING_UPDATE);
    }

    // Expenses are raised by the teams that spend (maker) and approved only at Level 2 (checker)
    @Test
    void expensesAreRaisedByOperationsAndApprovedByLevelTwoFinance() {
        for (Designation designation : List.of(Designation.STORE_MANAGER, Designation.DEALER_MANAGER,
                Designation.FULFILLMENT_SPECIALIST, Designation.FLEET_COORDINATOR)) {
            assertThat(UserAccess.of(Role.ADMIN_L2, designation).permissions()).as(designation.name())
                    .contains(Permission.EXPENSE_VIEW, Permission.EXPENSE_SUBMIT)
                    .doesNotContain(Permission.EXPENSE_APPROVE);
        }
        assertThat(UserAccess.of(Role.ADMIN_L2, Designation.FINANCE_MANAGER).permissions())
                .contains(Permission.EXPENSE_APPROVE, Permission.FINANCE_REPORT_VIEW, Permission.FINANCE_REPORT_EXPORT);
        assertThat(UserAccess.of(Role.ADMIN_L1, Designation.FINANCE_MANAGER).permissions())
                .contains(Permission.EXPENSE_SUBMIT)
                .doesNotContain(Permission.EXPENSE_APPROVE);
        assertThat(UserAccess.of(Role.ADMIN_L2, Designation.FINANCIAL_AUDITOR).permissions())
                .contains(Permission.EXPENSE_VIEW)
                .doesNotContain(Permission.EXPENSE_SUBMIT, Permission.EXPENSE_APPROVE);
        assertThat(PermissionGroup.EXPENSE_APPROVAL.approvalLevel()).isTrue();
    }

    // Finance managers run client demos; only Level 2 approves them, and only a Super Admin corrects a completed one
    @Test
    void clientDemosAreRunByFinanceAndCorrectedOnlyBySuperAdmins() {
        assertThat(UserAccess.of(Role.ADMIN_L1, Designation.FINANCE_MANAGER).permissions())
                .contains(Permission.DEMO_VIEW, Permission.DEMO_MANAGE)
                .doesNotContain(Permission.DEMO_APPROVE, Permission.DEMO_COMPLETED_EDIT);
        assertThat(UserAccess.of(Role.ADMIN_L2, Designation.FINANCE_MANAGER).permissions())
                .contains(Permission.DEMO_APPROVE)
                .doesNotContain(Permission.DEMO_COMPLETED_EDIT);
        assertThat(UserAccess.of(Role.ADMIN_L2, Designation.SUPER_ADMIN).permissions())
                .contains(Permission.DEMO_COMPLETED_EDIT, Permission.DEMO_UNIT_WRITE_OFF);
        assertThat(UserAccess.of(Role.ADMIN_L1, Designation.SUPER_ADMIN).permissions())
                .doesNotContain(Permission.DEMO_COMPLETED_EDIT, Permission.DEMO_UNIT_WRITE_OFF);
        for (Designation designation : Designation.values()) {
            assertThat(UserAccess.of(Role.ADMIN_L1, designation).permissions()).as(designation.name())
                    .contains(Permission.DEMO_VIEW_OWN, Permission.DEMO_EXPENSE_RECORD_OWN, Permission.DEMO_UNITS_HANDLE_OWN);
        }
    }

    // Agents book store sales and see only their own; store and finance staff read every agent's sales
    @Test
    void salesAgentsSellAndSeeOnlyTheirOwnSales() {
        for (Role level : List.of(Role.ADMIN_L1, Role.ADMIN_L2)) {
            assertThat(UserAccess.of(level, Designation.SALES_AGENT).permissions()).as(level.name())
                    .containsExactlyInAnyOrder(Permission.STORE_CUSTOMER_REGISTER, Permission.STORE_ORDER_CREATE,
                            Permission.STORE_ORDER_VIEW, Permission.PAYSLIP_VIEW_OWN, Permission.DEMO_VIEW_OWN,
                            Permission.DEMO_EXPENSE_RECORD_OWN, Permission.DEMO_UNITS_HANDLE_OWN);
        }
        assertThat(DesignationAccess.effectiveLevel(Role.ADMIN_L2, Designation.SALES_AGENT)).isEqualTo(Role.ADMIN_L1);
        for (Designation designation : List.of(Designation.STORE_MANAGER, Designation.FINANCE_MANAGER,
                Designation.FINANCIAL_AUDITOR, Designation.SUPER_ADMIN)) {
            assertThat(UserAccess.of(Role.ADMIN_L1, designation).permissions()).as(designation.name())
                    .contains(Permission.STORE_SALES_VIEW_ALL);
        }
        // Mail server settings belong to a Level 2 Super Admin
        assertThat(UserAccess.of(Role.ADMIN_L2, Designation.SUPER_ADMIN).permissions()).contains(Permission.SYSTEM_SETTINGS_MANAGE);
        assertThat(UserAccess.of(Role.ADMIN_L1, Designation.SUPER_ADMIN).permissions()).doesNotContain(Permission.SYSTEM_SETTINGS_MANAGE);
    }

    // Dealer logins hold the dealer portal and nothing else, whatever designation is passed in
    @Test
    void dealersOnlyHoldTheDealerPortal() {
        assertThat(UserAccess.of(Role.DEALER, null).permissions()).containsExactlyInAnyOrder(
                Permission.DEALER_PORTAL_VIEW, Permission.DEALER_QUOTATION_SUBMIT,
                Permission.DEALER_QUOTATION_CHANGE_REQUEST, Permission.DEALER_ORDER_RESPOND,
                Permission.DEALER_ORDER_SHIP);
        assertThat(UserAccess.of(Role.DEALER, Designation.SUPER_ADMIN).permissions())
                .isEqualTo(UserAccess.of(Role.DEALER, null).permissions());
        assertThat(AccessDTOs.MyAccess.of(UserAccess.of(Role.DEALER, null)).designationLabel()).isEqualTo("Dealer");
    }

    // A Dealer Manager creates dealer logins and only Level 2 approves them; either level decides dealers' quotation changes
    @Test
    void dealerLoginsNeedALevelTwoDealerManagerButQuotationChangesAnyDealerManager() {
        assertThat(UserAccess.of(Role.ADMIN_L1, Designation.DEALER_MANAGER).permissions())
                .contains(Permission.DEALER_LOGIN_MANAGE, Permission.DEALER_QUOTATION_CHANGE_APPROVE)
                .doesNotContain(Permission.DEALER_LOGIN_APPROVE);
        assertThat(UserAccess.of(Role.ADMIN_L2, Designation.DEALER_MANAGER).permissions())
                .contains(Permission.DEALER_LOGIN_MANAGE, Permission.DEALER_LOGIN_APPROVE, Permission.DEALER_QUOTATION_CHANGE_APPROVE)
                .noneMatch(permission -> permission.group() == PermissionGroup.DEALER_PORTAL);
        assertThat(Permission.DEALER_LOGIN_APPROVE.group().approvalLevel()).isTrue();
        assertThat(Permission.DEALER_QUOTATION_CHANGE_APPROVE.group().approvalLevel()).isFalse();
    }

    // Super Admin and Store Manager (either level) open the user guide and share role PDFs; nobody else does
    @Test
    void onlySuperAdminsAndStoreManagersOpenTheUserGuide() {
        for (Designation designation : Designation.values()) {
            boolean expected = designation == Designation.SUPER_ADMIN || designation == Designation.STORE_MANAGER;
            for (Role level : List.of(Role.ADMIN_L1, Role.ADMIN_L2)) {
                assertThat(UserAccess.of(level, designation).permissions().contains(Permission.USER_GUIDE_VIEW))
                        .as(designation + " " + level).isEqualTo(expected);
            }
        }
        assertThat(UserAccess.of(Role.DEALER, null).permissions()).doesNotContain(Permission.USER_GUIDE_VIEW);
    }

    // Barcodes: store, warehouse and Super Admin staff ask for corrections, others who handle stock only trace units;
    // only a Level 2 Super Admin changes a locked barcode or decides those requests
    @Test
    void onlyALevelTwoSuperAdminChangesLockedBarcodes() {
        for (Designation designation : Designation.values()) {
            for (Role level : List.of(Role.ADMIN_L1, Role.ADMIN_L2)) {
                assertThat(UserAccess.of(level, designation).permissions().contains(Permission.BARCODE_APPROVE))
                        .as(designation + " " + level)
                        .isEqualTo(designation == Designation.SUPER_ADMIN && level == Role.ADMIN_L2);
            }
        }
        for (Designation designation : List.of(Designation.STORE_MANAGER,
                Designation.FULFILLMENT_SPECIALIST, Designation.SUPER_ADMIN)) {
            assertThat(UserAccess.of(Role.ADMIN_L1, designation).permissions()).as(designation.name())
                    .contains(Permission.BARCODE_VIEW, Permission.BARCODE_REQUEST);
        }
        for (Designation designation : List.of(Designation.DEALER_MANAGER, Designation.CATALOG_SPECIALIST,
                Designation.CUSTOMER_SUPPORT, Designation.FINANCIAL_AUDITOR)) {
            assertThat(UserAccess.of(Role.ADMIN_L2, designation).permissions()).as(designation.name())
                    .contains(Permission.BARCODE_VIEW).doesNotContain(Permission.BARCODE_REQUEST);
        }
        assertThat(UserAccess.of(Role.ADMIN_L2, Designation.DELIVERY_RIDER).permissions()).doesNotContain(Permission.BARCODE_VIEW);
        assertThat(UserAccess.of(Role.DEALER, null).permissions()).doesNotContain(Permission.BARCODE_VIEW);
    }

    // Customer care and store managers look customers up and ask for corrections; only the Super Admin edits them
    @Test
    void customerCareAsksForCustomerChangesAndOnlyTheSuperAdminMakesThem() {
        for (Designation designation : List.of(Designation.CUSTOMER_SUPPORT, Designation.STORE_MANAGER)) {
            assertThat(UserAccess.of(Role.ADMIN_L2, designation).permissions()).as(designation.name())
                    .contains(Permission.CUSTOMER_VIEW, Permission.CUSTOMER_CHANGE_REQUEST)
                    .doesNotContain(Permission.CUSTOMER_UPDATE);
        }
        for (Designation designation : Designation.values()) {
            assertThat(UserAccess.of(Role.ADMIN_L2, designation).permissions().contains(Permission.CUSTOMER_UPDATE))
                    .as(designation.name()).isEqualTo(designation == Designation.SUPER_ADMIN);
        }
        assertThat(UserAccess.of(Role.ADMIN_L1, Designation.SUPER_ADMIN).permissions())
                .contains(Permission.CUSTOMER_VIEW).doesNotContain(Permission.CUSTOMER_UPDATE);
        assertThat(UserAccess.of(Role.ADMIN_L2, Designation.SALES_AGENT).permissions()).doesNotContain(Permission.CUSTOMER_VIEW);
    }

    // Every employee reads their own payslips; salaries are recorded by finance and confirmed only at Level 2
    @Test
    void everyEmployeeReadsTheirPayslipsAndOnlyLevelTwoFinanceConfirmsSalaries() {
        for (Designation designation : Designation.values()) {
            for (Role level : List.of(Role.ADMIN_L1, Role.ADMIN_L2)) {
                assertThat(UserAccess.of(level, designation).permissions()).as(designation + " " + level)
                        .contains(Permission.PAYSLIP_VIEW_OWN);
            }
        }
        assertThat(UserAccess.of(Role.DEALER, null).permissions()).doesNotContain(Permission.PAYSLIP_VIEW_OWN);
        assertThat(UserAccess.of(Role.ADMIN_L1, Designation.FINANCE_MANAGER).permissions())
                .contains(Permission.PAYMENT_VIEW, Permission.PAYMENT_RECORD)
                .doesNotContain(Permission.PAYMENT_APPROVE);
        assertThat(UserAccess.of(Role.ADMIN_L2, Designation.FINANCE_MANAGER).permissions()).contains(Permission.PAYMENT_APPROVE);
        assertThat(UserAccess.of(Role.ADMIN_L2, Designation.DELIVERY_RIDER).permissions()).doesNotContain(Permission.PAYMENT_VIEW);
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
