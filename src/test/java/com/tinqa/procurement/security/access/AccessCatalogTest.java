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
        expected.removeIf(permission -> permission.group() == PermissionGroup.SUPPLIER_PORTAL);

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
        granted.addAll(DesignationAccess.effectiveGroups(Role.SUPPLIER, null));

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
        assertThat(DesignationAccess.effectiveGroups(Role.SUPPLIER, Designation.SUPER_ADMIN)).containsExactly(PermissionGroup.SUPPLIER_PORTAL);
        assertThat(DesignationAccess.effectiveGroups(Role.ADMIN_L2, null)).isEmpty();
    }

    @Test
    void levelTwoIsOnlyKeptWhereItAddsApprovalPowers() {
        assertThat(DesignationAccess.hasApprovalLevel(Designation.STORE_MANAGER)).isTrue();
        assertThat(DesignationAccess.hasApprovalLevel(Designation.DELIVERY_RIDER)).isFalse();
        assertThat(DesignationAccess.effectiveLevel(Role.ADMIN_L2, Designation.DELIVERY_RIDER)).isEqualTo(Role.ADMIN_L1);
        assertThat(DesignationAccess.effectiveLevel(Role.ADMIN_L2, Designation.SUPPLIER_MANAGER)).isEqualTo(Role.ADMIN_L2);
        assertThat(DesignationAccess.effectiveLevel(Role.INSPECTOR, Designation.DELIVERY_RIDER)).isEqualTo(Role.INSPECTOR);
    }

    // Customer Support only asks for a return; Store Managers enable it and set its charge, at Level 1 as well
    @Test
    void returnsAreRequestedBySupportAndEnabledByStoreManagers() {
        for (Role level : List.of(Role.ADMIN_L1, Role.ADMIN_L2)) {
            assertThat(UserAccess.of(level, Designation.CUSTOMER_SUPPORT).permissions()).as(level.name())
                    .contains(Permission.REFUND_REQUEST)
                    .doesNotContain(Permission.RETURN_ENABLE);
            assertThat(UserAccess.of(level, Designation.STORE_MANAGER).permissions()).as(level.name())
                    .contains(Permission.REFUND_REQUEST, Permission.RETURN_ENABLE);
        }
        assertThat(PermissionGroup.RETURN_ENABLEMENT.approvalLevel()).isFalse();
    }

    // Customer care reviews warranty claims; only a Level 2 Store Manager or Super Admin decides them
    @Test
    void customerCareReviewsWarrantyClaimsAndALevel2Decides() {
        for (Role level : List.of(Role.ADMIN_L1, Role.ADMIN_L2)) {
            assertThat(UserAccess.of(level, Designation.CUSTOMER_SUPPORT).permissions()).as(level.name())
                    .contains(Permission.WARRANTY_CLAIM_VIEW, Permission.WARRANTY_CLAIM_UPDATE)
                    .doesNotContain(Permission.WARRANTY_CLAIM_DECIDE);
        }
        for (Designation designation : Designation.values()) {
            assertThat(UserAccess.of(Role.ADMIN_L1, designation).permissions()).as(designation.name())
                    .doesNotContain(Permission.WARRANTY_CLAIM_DECIDE);
            assertThat(UserAccess.of(Role.ADMIN_L2, designation).permissions().contains(Permission.WARRANTY_CLAIM_DECIDE))
                    .as(designation.name())
                    .isEqualTo(designation == Designation.SUPER_ADMIN || designation == Designation.STORE_MANAGER);
        }
        assertThat(PermissionGroup.WARRANTY_CLAIM_APPROVAL.approvalLevel()).isTrue();
    }

    // Riders record delivery outcomes only; changing any other status needs CUSTOMER_ORDER_TRACKING_UPDATE
    @Test
    void deliveryRidersOnlyRecordDeliveryOutcomes() {
        for (Role level : List.of(Role.ADMIN_L1, Role.ADMIN_L2)) {
            assertThat(UserAccess.of(level, Designation.DELIVERY_RIDER).permissions())
                    .containsExactlyInAnyOrder(Permission.CUSTOMER_ORDER_VIEW, Permission.DELIVERY_STATUS_UPDATE,
                            Permission.PAYSLIP_VIEW_OWN, Permission.MEETING_VIEW_OWN, Permission.MEETING_RESPOND_OWN);
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
        for (Designation designation : List.of(Designation.STORE_MANAGER, Designation.SUPPLIER_MANAGER,
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
    }

    // Going on demos is field work for the roles that do it, not self service: a rider, warehouse or support employee
    // has no "My demos" and can't be tagged on one
    @Test
    void onlyRolesThatGoOnDemosAttendThem() {
        Set<Designation> attending = EnumSet.of(Designation.SUPER_ADMIN, Designation.STORE_MANAGER,
                Designation.FINANCE_MANAGER, Designation.SALES_AGENT);
        for (Designation designation : Designation.values()) {
            for (Role level : List.of(Role.ADMIN_L1, Role.ADMIN_L2)) {
                Set<Permission> permissions = UserAccess.of(level, designation).permissions();
                if (attending.contains(designation)) {
                    assertThat(permissions).as(designation + " " + level)
                            .contains(Permission.DEMO_VIEW_OWN, Permission.DEMO_EXPENSE_RECORD_OWN, Permission.DEMO_UNITS_HANDLE_OWN);
                } else {
                    assertThat(permissions).as(designation + " " + level)
                            .doesNotContain(Permission.DEMO_VIEW_OWN, Permission.DEMO_EXPENSE_RECORD_OWN, Permission.DEMO_UNITS_HANDLE_OWN);
                }
            }
        }
        assertThat(PermissionGroup.CLIENT_DEMO_ATTENDANCE.approvalLevel()).isFalse();
        assertThat(Permission.DEMO_VIEW_OWN.group()).isEqualTo(PermissionGroup.CLIENT_DEMO_ATTENDANCE);
    }

    // Store managers and catalog specialists post openings and screen applicants; only Level 2 confirms the decisions
    @Test
    void careersAreScreenedByStoreAndCatalogStaffAndDecidedAtLevelTwo() {
        for (Designation designation : List.of(Designation.STORE_MANAGER, Designation.CATALOG_SPECIALIST, Designation.SUPER_ADMIN)) {
            assertThat(UserAccess.of(Role.ADMIN_L1, designation).permissions()).as(designation.name())
                    .contains(Permission.CAREER_OPENING_VIEW, Permission.CAREER_APPLICATION_VIEW,
                            Permission.CAREER_OPENING_MANAGE, Permission.CAREER_APPLICATION_SCREEN)
                    .doesNotContain(Permission.CAREER_DECISION_APPROVE);
        }
        assertThat(UserAccess.of(Role.ADMIN_L2, Designation.STORE_MANAGER).permissions()).contains(Permission.CAREER_DECISION_APPROVE);
        assertThat(UserAccess.of(Role.ADMIN_L2, Designation.SUPER_ADMIN).permissions()).contains(Permission.CAREER_DECISION_APPROVE);
        // Only a Super Admin at Level 2 hides or shows the Careers tab itself
        assertThat(UserAccess.of(Role.ADMIN_L2, Designation.SUPER_ADMIN).permissions()).contains(Permission.STOREFRONT_FEATURE_TOGGLE);
        assertThat(UserAccess.of(Role.ADMIN_L1, Designation.SUPER_ADMIN).permissions()).doesNotContain(Permission.STOREFRONT_FEATURE_TOGGLE);
        assertThat(UserAccess.of(Role.ADMIN_L2, Designation.STORE_MANAGER).permissions()).doesNotContain(Permission.STOREFRONT_FEATURE_TOGGLE);
        // Catalog specialists have no approval groups, so they are never Level 2
        assertThat(DesignationAccess.effectiveLevel(Role.ADMIN_L2, Designation.CATALOG_SPECIALIST)).isEqualTo(Role.ADMIN_L1);
        for (Designation designation : List.of(Designation.DELIVERY_RIDER, Designation.SALES_AGENT, Designation.CUSTOMER_SUPPORT)) {
            assertThat(UserAccess.of(Role.ADMIN_L2, designation).permissions()).as(designation.name())
                    .doesNotContain(Permission.CAREER_APPLICATION_VIEW);
        }
    }

    // Senior staff see promo codes to tell customers about them; only a Super Admin at Level 2 makes or changes them
    @Test
    void onlyTheSuperAdminManagesPromoCodesWhileSeniorStaffSeeThem() {
        Set<Designation> viewers = EnumSet.of(Designation.SUPER_ADMIN, Designation.STORE_MANAGER, Designation.SALES_AGENT,
                Designation.CUSTOMER_SUPPORT, Designation.FINANCE_MANAGER, Designation.FINANCIAL_AUDITOR);
        for (Designation designation : Designation.values()) {
            for (Role level : List.of(Role.ADMIN_L1, Role.ADMIN_L2)) {
                Set<Permission> permissions = UserAccess.of(level, designation).permissions();
                assertThat(permissions.contains(Permission.PROMO_CODE_VIEW)).as(designation + " " + level + " views")
                        .isEqualTo(viewers.contains(designation));
                assertThat(permissions.contains(Permission.PROMO_CODE_MANAGE)).as(designation + " " + level + " manages")
                        .isEqualTo(designation == Designation.SUPER_ADMIN && level == Role.ADMIN_L2);
            }
        }
        // Never part of a custom role, whoever builds it
        assertThat(DesignationAccess.NEVER_IN_CUSTOM_ROLES).contains(Permission.PROMO_CODE_MANAGE.group());
    }

    // Finance Managers request and pay suppliers; only a Super Admin (Level 2) approves the requests, and no custom role can
    @Test
    void onlyASuperAdminApprovesSupplierPayments() {
        for (Designation designation : Designation.values()) {
            for (Role level : List.of(Role.ADMIN_L1, Role.ADMIN_L2)) {
                Set<Permission> permissions = UserAccess.of(level, designation).permissions();
                assertThat(permissions.contains(Permission.SUPPLIER_PAYMENT_APPROVE)).as(designation + " " + level + " approves")
                        .isEqualTo(designation == Designation.SUPER_ADMIN && level == Role.ADMIN_L2);
                assertThat(permissions.contains(Permission.SUPPLIER_PAYMENT_RECORD)).as(designation + " " + level + " requests")
                        .isEqualTo(designation == Designation.SUPER_ADMIN || designation == Designation.FINANCE_MANAGER);
            }
        }
        assertThat(DesignationAccess.NEVER_IN_CUSTOM_ROLES).contains(PermissionGroup.SUPPLIER_PAYMENT_APPROVAL);
    }

    // Supplier Managers (either level) raise returns of failed pieces; only a Super Admin (Level 2) approves them
    @Test
    void supplierManagersRaiseReturnsOfFailedPiecesAndOnlyASuperAdminApprovesThem() {
        for (Designation designation : Designation.values()) {
            for (Role level : List.of(Role.ADMIN_L1, Role.ADMIN_L2)) {
                Set<Permission> permissions = UserAccess.of(level, designation).permissions();
                assertThat(permissions.contains(Permission.SUPPLIER_RETURN_APPROVE)).as(designation + " " + level + " approves returns")
                        .isEqualTo(designation == Designation.SUPER_ADMIN && level == Role.ADMIN_L2);
                assertThat(permissions.contains(Permission.SUPPLIER_RETURN_REQUEST)).as(designation + " " + level + " raises returns")
                        .isEqualTo(designation == Designation.SUPER_ADMIN || designation == Designation.SUPPLIER_MANAGER);
            }
        }
        assertThat(DesignationAccess.NEVER_IN_CUSTOM_ROLES).contains(Permission.SUPPLIER_RETURN_APPROVE.group());
    }

    // Staff who hand codes to customers share them (email, storefront) without seeing chosen customers' codes;
    // finance staff only look
    @Test
    void storeAndCustomerCareStaffSharePromoCodes() {
        Set<Designation> sharers = EnumSet.of(Designation.SUPER_ADMIN, Designation.STORE_MANAGER, Designation.SALES_AGENT,
                Designation.CUSTOMER_SUPPORT);
        for (Designation designation : Designation.values()) {
            for (Role level : List.of(Role.ADMIN_L1, Role.ADMIN_L2)) {
                assertThat(UserAccess.of(level, designation).permissions().contains(Permission.PROMO_CODE_SHARE))
                        .as(designation + " " + level + " shares").isEqualTo(sharers.contains(designation));
            }
        }
        assertThat(Permission.PROMO_CODE_SHARE.group().approvalLevel()).isFalse();
    }

    // Agents book store sales and see only their own; store and finance staff read every agent's sales
    @Test
    void salesAgentsSellAndSeeOnlyTheirOwnSales() {
        for (Role level : List.of(Role.ADMIN_L1, Role.ADMIN_L2)) {
            assertThat(UserAccess.of(level, Designation.SALES_AGENT).permissions()).as(level.name())
                    .containsExactlyInAnyOrder(Permission.STORE_CUSTOMER_REGISTER, Permission.STORE_ORDER_CREATE,
                            Permission.STORE_ORDER_VIEW, Permission.PAYSLIP_VIEW_OWN, Permission.DEMO_VIEW_OWN,
                            Permission.DEMO_EXPENSE_RECORD_OWN, Permission.DEMO_UNITS_HANDLE_OWN, Permission.MEETING_VIEW_OWN,
                            Permission.MEETING_RESPOND_OWN, Permission.PROMO_CODE_VIEW, Permission.PROMO_CODE_SHARE);
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

    // Supplier logins hold the supplier portal and nothing else, whatever designation is passed in
    @Test
    void suppliersOnlyHoldTheSupplierPortal() {
        assertThat(UserAccess.of(Role.SUPPLIER, null).permissions()).containsExactlyInAnyOrder(
                Permission.SUPPLIER_PORTAL_VIEW, Permission.SUPPLIER_QUOTATION_SUBMIT,
                Permission.SUPPLIER_QUOTATION_CHANGE_REQUEST, Permission.SUPPLIER_ORDER_RESPOND,
                Permission.SUPPLIER_ORDER_SHIP, Permission.SUPPLIER_PAYMENT_CONFIRM, Permission.SUPPLIER_RETURN_RESPOND,
                Permission.SUPPLIER_REPAYMENT_SUBMIT);
        assertThat(UserAccess.of(Role.SUPPLIER, Designation.SUPER_ADMIN).permissions())
                .isEqualTo(UserAccess.of(Role.SUPPLIER, null).permissions());
        assertThat(AccessDTOs.MyAccess.of(UserAccess.of(Role.SUPPLIER, null)).designationLabel()).isEqualTo("Supplier");
    }

    // A Supplier Manager creates supplier logins and only Level 2 approves them; either level decides suppliers' quotation changes
    @Test
    void supplierLoginsNeedALevelTwoSupplierManagerButQuotationChangesAnySupplierManager() {
        assertThat(UserAccess.of(Role.ADMIN_L1, Designation.SUPPLIER_MANAGER).permissions())
                .contains(Permission.SUPPLIER_LOGIN_MANAGE, Permission.SUPPLIER_QUOTATION_CHANGE_APPROVE)
                .doesNotContain(Permission.SUPPLIER_LOGIN_APPROVE);
        assertThat(UserAccess.of(Role.ADMIN_L2, Designation.SUPPLIER_MANAGER).permissions())
                .contains(Permission.SUPPLIER_LOGIN_MANAGE, Permission.SUPPLIER_LOGIN_APPROVE, Permission.SUPPLIER_QUOTATION_CHANGE_APPROVE)
                .noneMatch(permission -> permission.group() == PermissionGroup.SUPPLIER_PORTAL);
        assertThat(Permission.SUPPLIER_LOGIN_APPROVE.group().approvalLevel()).isTrue();
        assertThat(Permission.SUPPLIER_QUOTATION_CHANGE_APPROVE.group().approvalLevel()).isFalse();
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
        assertThat(UserAccess.of(Role.SUPPLIER, null).permissions()).doesNotContain(Permission.USER_GUIDE_VIEW);
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
        for (Designation designation : List.of(Designation.SUPPLIER_MANAGER, Designation.CATALOG_SPECIALIST,
                Designation.CUSTOMER_SUPPORT, Designation.FINANCIAL_AUDITOR)) {
            assertThat(UserAccess.of(Role.ADMIN_L2, designation).permissions()).as(designation.name())
                    .contains(Permission.BARCODE_VIEW).doesNotContain(Permission.BARCODE_REQUEST);
        }
        assertThat(UserAccess.of(Role.ADMIN_L2, Designation.DELIVERY_RIDER).permissions()).doesNotContain(Permission.BARCODE_VIEW);
        assertThat(UserAccess.of(Role.SUPPLIER, null).permissions()).doesNotContain(Permission.BARCODE_VIEW);
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
        assertThat(UserAccess.of(Role.SUPPLIER, null).permissions()).doesNotContain(Permission.PAYSLIP_VIEW_OWN);
        assertThat(UserAccess.of(Role.ADMIN_L1, Designation.FINANCE_MANAGER).permissions())
                .contains(Permission.PAYMENT_VIEW, Permission.PAYMENT_RECORD)
                .doesNotContain(Permission.PAYMENT_APPROVE);
        assertThat(UserAccess.of(Role.ADMIN_L2, Designation.FINANCE_MANAGER).permissions()).contains(Permission.PAYMENT_APPROVE);
        assertThat(UserAccess.of(Role.ADMIN_L2, Designation.DELIVERY_RIDER).permissions()).doesNotContain(Permission.PAYMENT_VIEW);
    }

    // Custom roles are designations too, so the pattern takes any designation-shaped code; the service checks it exists
    @Test
    void designationPatternAcceptsDesignationCodes() {
        for (Designation designation : Designation.values()) {
            assertThat(designation.name().matches(ValidationPatterns.DESIGNATION)).as(designation.name()).isTrue();
            assertThat(designation.name().toLowerCase().matches(ValidationPatterns.DESIGNATION)).isTrue();
        }
        assertThat("MARKETING_EXECUTIVE".matches(ValidationPatterns.DESIGNATION)).isTrue();
        assertThat("Sales Head".matches(ValidationPatterns.DESIGNATION)).isFalse();
        assertThat("SUPER_ADMIN|X".matches(ValidationPatterns.DESIGNATION)).isFalse();
        assertThat("1ST_ROLE".matches(ValidationPatterns.DESIGNATION)).isFalse();
    }

    // A custom role works like a designation: SELF_SERVICE added, approval groups only at Level 2, Level 2 only when
    // it adds something, and never the supplier portal or system administration
    @Test
    void customRolesGrantTheirGroupsLikeADesignation() {
        CustomRoleGrant marketing = new CustomRoleGrant(1L, "MARKETING_EXECUTIVE", "Marketing Executive", "Marketing",
                EnumSet.of(PermissionGroup.CLIENT_OUTREACH_READ, PermissionGroup.CLIENT_OUTREACH_MANAGEMENT,
                        PermissionGroup.MEETING_READ, PermissionGroup.MEETING_MANAGEMENT, PermissionGroup.CLIENT_DEMO_APPROVAL,
                        PermissionGroup.SYSTEM_ADMINISTRATION));
        UserAccess l1 = UserAccess.of(Role.ADMIN_L1, null, marketing);
        assertThat(l1.permissions()).contains(Permission.OUTREACH_LOG, Permission.MEETING_SCHEDULE, Permission.MEETING_RESPOND_OWN,
                Permission.PAYSLIP_VIEW_OWN).doesNotContain(Permission.DEMO_APPROVE, Permission.SYSTEM_SETTINGS_MANAGE);
        assertThat(l1.designationCode()).isEqualTo("MARKETING_EXECUTIVE");
        assertThat(l1.designationLabel()).isEqualTo("Marketing Executive");
        assertThat(UserAccess.of(Role.ADMIN_L2, null, marketing).permissions()).contains(Permission.DEMO_APPROVE)
                .doesNotContain(Permission.SYSTEM_SETTINGS_MANAGE);
        assertThat(DesignationAccess.effectiveLevelOfCustomRole(Role.ADMIN_L2, marketing)).isEqualTo(Role.ADMIN_L2);

        CustomRoleGrant caller = new CustomRoleGrant(2L, "CALLER", "Caller", "Marketing",
                EnumSet.of(PermissionGroup.CLIENT_OUTREACH_READ));
        assertThat(DesignationAccess.effectiveLevelOfCustomRole(Role.ADMIN_L2, caller)).isEqualTo(Role.ADMIN_L1);
        // A supplier never picks up an employee's custom role
        assertThat(UserAccess.of(Role.SUPPLIER, null, caller).permissions()).doesNotContain(Permission.OUTREACH_VIEW);
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
