package com.tinqa.procurement;

import com.tinqa.procurement.security.access.Designation;
import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Designation -> permission groups -> permissions, enforced over HTTP.
 */
@DisplayName("Access control")
class AccessControlIntegrationTest extends IntegrationTestSupport {

    // An enabled admin with the given level and designation, and a token for them
    private Actor admin(String role, String designation) throws Exception {
        String username = uniq("acl");
        long userId = insertUser(username, role, "WEB", false, true);
        long employeeId = designation == null ? 0 : insertEmployee(userId, designation);
        return new Actor(userId, employeeId, username, username + "@tinqa.test", login(username, SEED_PASSWORD));
    }

    private static List<String> texts(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }

    @Test
    void loginAndTokenCarryTheAccountsPermissions() throws Exception {
        JsonNode l1Login = post("/auth/admin/login", null, Map.of("username", l1.username(), "password", SEED_PASSWORD), 200);
        assertThat(l1Login.get("designation").asText()).isEqualTo("SUPER_ADMIN");
        assertThat(l1Login.get("designationLabel").asText()).isEqualTo("Super Admin");
        // Admin L1 holds the designation's groups minus the approval-level ones
        assertThat(texts(l1Login.get("permissions"))).contains("EMPLOYEE_CREATE", "SUPPLIER_CREATE").doesNotContain("EMPLOYEE_APPROVE", "SUPPLIER_DELETE");
        assertThat(texts(l1Login.get("permissionGroups"))).contains("EMPLOYEE_MANAGEMENT").doesNotContain("EMPLOYEE_APPROVAL");

        JsonNode l2Login = post("/auth/admin/login", null, Map.of("username", l2a.username(), "password", SEED_PASSWORD), 200);
        assertThat(texts(l2Login.get("permissions"))).contains("EMPLOYEE_APPROVE", "REFUND_APPROVE", "COMPANY_PROFILE_APPROVE");

        // Other TinQa services read the permissions from the token's perms claim
        String payload = new String(Base64.getUrlDecoder().decode(l2Login.get("accessToken").asText().split("\\.")[1]), StandardCharsets.UTF_8);
        JsonNode claims = objectMapper.readTree(payload);
        assertThat(claims.get("designation").asText()).isEqualTo("SUPER_ADMIN");
        assertThat(texts(claims.get("perms"))).contains("REFUND_APPROVE", "CUSTOMER_ORDER_VIEW");
    }

    @Test
    void designationDecidesWhichAreasAnAdminCanUse() throws Exception {
        Actor catalog = admin("ADMIN_L2", "CATALOG_SPECIALIST");

        get("/v1/admin/items?size=5", catalog.token(), 200);
        get("/v1/admin/products?size=5", catalog.token(), 200);
        get("/v1/admin/suppliers?size=5", catalog.token(), 403);
        get("/v1/admin/employees?size=5", catalog.token(), 403);
        get("/v1/orders", catalog.token(), 403);
        // Catalog specialists prepare listings but don't approve them, even at Level 2
        get("/v1/product-approvals", catalog.token(), 403);

        JsonNode access = data(get("/v1/access/me", catalog.token(), 200));
        assertThat(access.get("designation").asText()).isEqualTo("CATALOG_SPECIALIST");
        assertThat(texts(access.get("permissionGroups"))).contains("CATALOG_READ", "CATALOG_MANAGEMENT").doesNotContain("SUPPLIER_READ");
    }

    @Test
    void levelOneNeverHoldsApprovalGroups() throws Exception {
        Actor managerL1 = admin("ADMIN_L1", "STORE_MANAGER");
        Actor managerL2 = admin("ADMIN_L2", "STORE_MANAGER");

        get("/v1/product-approvals", managerL1.token(), 403);
        get("/v1/product-approvals", managerL2.token(), 200);
        get("/v1/stocks", managerL1.token(), 200);
    }

    @Test
    void designationChangesApplyToTheNextRequest() throws Exception {
        Actor rider = admin("ADMIN_L2", "DELIVERY_RIDER");
        get("/v1/admin/suppliers?size=5", rider.token(), 403);

        jdbc.update("UPDATE employee SET designation = 'SUPPLIER_MANAGER' WHERE id = ?", rider.employeeId());
        get("/v1/admin/suppliers?size=5", rider.token(), 200);
    }

    @Test
    void adminsWithoutADesignationCanDoNothing() throws Exception {
        Actor nobody = admin("ADMIN_L2", null);
        get("/v1/admin/items?size=5", nobody.token(), 403);
        assertThat(data(get("/v1/access/me", nobody.token(), 200)).get("permissions")).isEmpty();
    }

    @Test
    void nobodyChangesTheirOwnDesignation() throws Exception {
        JsonNode own = put("/v1/admin/employees/" + l2a.employeeId(), l2a.token(), body("designation", "STORE_MANAGER"), 400);
        assertThat(own.get("errors").get(0).get("field").asText()).isEqualTo("designation");

        JsonNode profile = put("/v1/admin/profile", l2b.token(), body("designation", "STORE_MANAGER"), 400);
        assertThat(profile.get("errors").get(0).get("field").asText()).isEqualTo("designation");
        // Sending the current designation back, as an edit form does, is fine
        put("/v1/admin/profile", l2b.token(), body("designation", "SUPER_ADMIN"), 200);
    }

    @Test
    void superAdminDesignationNeedsAnEmployeeApprover() throws Exception {
        Map<String, Object> superAdmin = withPanAndAddress(body("username", uniq("sa"), "email", uniq("sa") + "@tinqa.test",
                "firstName", letters(), "lastName", letters(), "dateOfBirth", "1990-01-15", "gender", "MALE",
                "designation", "SUPER_ADMIN", "department", "Administration", "employmentType", "FULL_TIME",
                "joiningDate", "2015-06-01", "phone", nextLocalMobile(), "role", "ADMIN_L1"));
        // Even an approver can't create a Super Admin alone
        JsonNode created = data(post("/v1/admin/employees", l2a.token(), superAdmin, 201));
        assertThat(created.get("status").asText()).isEqualTo("APPROVAL_PENDING");

        Actor target = admin("ADMIN_L1", "CATALOG_SPECIALIST");
        JsonNode promoted = put("/v1/admin/employees/" + target.employeeId(), l1.token(), body("designation", "SUPER_ADMIN"), 400);
        assertThat(promoted.get("errors").get(0).get("field").asText()).isEqualTo("designation");
        put("/v1/admin/employees/" + target.employeeId(), l2a.token(), body("designation", "SUPER_ADMIN"), 200);

        put("/v1/admin/employees/" + target.employeeId(), l2a.token(), body("designation", "Chief Wizard"), 400);
    }

    @Test
    void onlyEmployeeApproversChangeAnotherAdminsLevel() throws Exception {
        Actor target = admin("ADMIN_L1", "STORE_MANAGER");
        get("/v1/product-approvals", target.token(), 403);

        JsonNode byMaker = put("/v1/admin/employees/" + target.employeeId(), l1.token(), body("role", "ADMIN_L2"), 400);
        assertThat(byMaker.get("errors").get(0).get("field").asText()).isEqualTo("role");
        JsonNode own = put("/v1/admin/employees/" + l2a.employeeId(), l2a.token(), body("role", "ADMIN_L1"), 400);
        assertThat(own.get("errors").get(0).get("field").asText()).isEqualTo("role");
        put("/v1/admin/employees/" + target.employeeId(), l2a.token(), body("role", "INSPECTOR"), 400);

        JsonNode promoted = data(put("/v1/admin/employees/" + target.employeeId(), l2a.token(), body("role", "admin_l2"), 200));
        assertThat(promoted.get("role").asText()).isEqualTo("ADMIN_L2");
        // The store manager now holds their approval groups too
        get("/v1/product-approvals", target.token(), 200);
    }

    @Test
    void departmentFollowsTheDesignation() throws Exception {
        Map<String, Object> rider = withPanAndAddress(body("username", uniq("dr"), "email", uniq("dr") + "@tinqa.test",
                "firstName", letters(), "lastName", letters(), "dateOfBirth", "1990-01-15", "gender", "MALE",
                "designation", "DELIVERY_RIDER", "employmentType", "FULL_TIME",
                "joiningDate", "2015-06-01", "phone", nextLocalMobile(), "role", "ADMIN_L1"));
        JsonNode created = data(post("/v1/admin/employees", l2a.token(), rider, 201));
        assertThat(created.get("department").asText()).isEqualTo("Logistics & Delivery");
        long id = created.get("id").asLong();

        // A department that doesn't match the designation is refused
        // (its own PAN and verified personal email, so only the department is wrong)
        Map<String, Object> mismatched = withPanAndAddress(new java.util.LinkedHashMap<>(rider));
        mismatched.putAll(body("username", uniq("dr"), "email", uniq("dr") + "@tinqa.test", "phone", nextLocalMobile(),
                "department", "Finance & Accounts"));
        JsonNode refused = post("/v1/admin/employees", l2a.token(), mismatched, 400);
        assertThat(refused.get("errors").get(0).get("field").asText()).isEqualTo("department");

        // Changing the designation moves the department with it
        JsonNode moved = data(put("/v1/admin/employees/" + id, l2a.token(), body("designation", "FINANCIAL_AUDITOR"), 200));
        assertThat(moved.get("department").asText()).isEqualTo("Finance & Accounts");
        put("/v1/admin/employees/" + id, l2a.token(), body("department", "Procurement"), 400);

        // Nobody changes their own department through their profile
        JsonNode own = put("/v1/admin/profile", l2b.token(), body("department", "Store Operations"), 400);
        assertThat(own.get("errors").get(0).get("field").asText()).isEqualTo("department");

        JsonNode catalog = data(get("/v1/access/catalog", l1.token(), 200));
        assertThat(catalog.get("designations").get(0).get("department").asText()).isEqualTo("Administration");
    }

    @Test
    void levelTwoOnlyExistsWhereADesignationCanApprove() throws Exception {
        // A rider has no approval powers, so asking for Level 2 gives Level 1 (and no second approver is needed)
        Map<String, Object> rider = withPanAndAddress(body("username", uniq("lv"), "email", uniq("lv") + "@tinqa.test",
                "firstName", letters(), "lastName", letters(), "dateOfBirth", "1990-01-15", "gender", "MALE",
                "designation", "DELIVERY_RIDER", "employmentType", "FULL_TIME",
                "joiningDate", "2015-06-01", "phone", nextLocalMobile(), "role", "ADMIN_L2"));
        JsonNode created = data(post("/v1/admin/employees", l2a.token(), rider, 201));
        assertThat(created.get("role").asText()).isEqualTo("ADMIN_L1");
        assertThat(created.get("status").asText()).isEqualTo("FIRST_LOGIN");

        // Moving a Level 2 store manager to a designation without approval powers makes them Level 1
        Actor manager = admin("ADMIN_L2", "STORE_MANAGER");
        JsonNode moved = data(put("/v1/admin/employees/" + manager.employeeId(), l2a.token(), body("designation", "CATALOG_SPECIALIST"), 200));
        assertThat(moved.get("role").asText()).isEqualTo("ADMIN_L1");
        JsonNode promoted = data(put("/v1/admin/employees/" + manager.employeeId(), l2a.token(), body("role", "ADMIN_L2"), 200));
        assertThat(promoted.get("role").asText()).isEqualTo("ADMIN_L1");
    }

    @Test
    void catalogDescribesEveryDesignationGroupAndPermission() throws Exception {
        JsonNode catalog = data(get("/v1/access/catalog", l1.token(), 200));
        // Built-in designations first; active custom roles (made by other tests) follow them
        assertThat(java.util.stream.StreamSupport.stream(catalog.get("designations").spliterator(), false)
                .filter(entry -> !entry.get("custom").asBoolean()).count()).isEqualTo(Designation.values().length);
        assertThat(catalog.get("designations").get(0).get("code").asText()).isEqualTo("SUPER_ADMIN");
        assertThat(catalog.get("groups").findValues("code").stream().map(JsonNode::asText)).contains("REFUND_APPROVAL");
        assertThat(catalog.get("permissions").findValues("code").stream().map(JsonNode::asText)).contains("REFUND_APPROVE");

        get("/v1/access/catalog", inspectorToken(), 403);
    }
}
