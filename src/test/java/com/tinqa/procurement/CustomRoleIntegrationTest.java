package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Custom roles: built from the groups the creator holds, used exactly like a built-in designation (access, label,
 * department, Level 2 only with approval groups), deactivated rather than deleted while in use.
 */
class CustomRoleIntegrationTest extends IntegrationTestSupport {

    private static final String ROLES = "/v1/access/roles";

    private static List<String> texts(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }

    private static JsonNode find(JsonNode array, String code) {
        return StreamSupport.stream(array.spliterator(), false).filter(entry -> entry.get("code").asText().equals(code))
                .findFirst().orElse(null);
    }

    private Map<String, Object> roleBody(String label, List<String> groups) {
        return body("label", label, "department", "Marketing", "description", "Calls hotels and offices", "groups", groups);
    }

    private Map<String, Object> employeeBody(String designation, String role) {
        return withPanAndAddress(body("username", uniq("cr"), "email", uniq("cr") + "@tinqa.test",
                "firstName", letters(), "lastName", letters(), "dateOfBirth", "1990-01-15", "gender", "FEMALE",
                "designation", designation, "employmentType", "FULL_TIME",
                "joiningDate", "2015-06-01", "phone", nextLocalMobile(), "role", role));
    }

    @Test
    void aCustomRoleWorksLikeADesignation() throws Exception {
        // Only groups the creator holds, never the supplier portal or system administration
        JsonNode grantable = data(get(ROLES + "/grantable-groups", l1.token(), 200));
        List<String> codes = grantable.findValues("code").stream().map(JsonNode::asText).toList();
        assertThat(codes).contains("CLIENT_OUTREACH_READ", "MEETING_MANAGEMENT")
                .doesNotContain("SUPPLIER_PORTAL", "SYSTEM_ADMINISTRATION", "SELF_SERVICE", "CLIENT_DEMO_APPROVAL");

        String label = "Marketing Executive " + letters();
        List<String> groups = List.of("CLIENT_OUTREACH_READ", "CLIENT_OUTREACH_MANAGEMENT", "MEETING_READ", "MEETING_MANAGEMENT");
        // A Level 1 admin can't hand out approval powers they don't have
        JsonNode refused = post(ROLES, l1.token(), roleBody(label, List.of("CLIENT_OUTREACH_READ", "CLIENT_DEMO_APPROVAL")), 403);
        assertThat(refused.get("errorCode").asText()).isEqualTo("ROLE_GROUP_NOT_GRANTABLE");
        post(ROLES, l2a.token(), roleBody(label, List.of("SYSTEM_ADMINISTRATION")), 400);
        post(ROLES, l2a.token(), roleBody(label, List.of("SELF_SERVICE")), 400);
        assertThat(post(ROLES, l2a.token(), roleBody("Store Manager", groups), 409).get("errorCode").asText()).isEqualTo("ROLE_EXISTS");

        JsonNode role = data(post(ROLES, l2a.token(), roleBody(label, groups), 201));
        String code = role.get("code").asText();
        assertThat(code).startsWith("MARKETING_EXECUTIVE_");
        assertThat(role.get("custom").asBoolean()).isTrue();
        assertThat(texts(role.get("groups"))).contains("SELF_SERVICE", "CLIENT_OUTREACH_MANAGEMENT");
        assertThat(post(ROLES, l2a.token(), roleBody(label, groups), 409).get("errorCode").asText()).isEqualTo("ROLE_EXISTS");
        long roleId = role.get("id").asLong();

        // The catalog offers it to the employee forms
        JsonNode catalogEntry = find(data(get("/v1/access/catalog", l1.token(), 200)).get("designations"), code);
        assertThat(catalogEntry.get("custom").asBoolean()).isTrue();
        assertThat(catalogEntry.get("id").asLong()).isEqualTo(roleId);
        assertThat(catalogEntry.get("department").asText()).isEqualTo("Marketing");

        // An employee with the role: its department and label, and Level 2 only because nothing in it is approval-level
        JsonNode employee = data(post("/v1/admin/employees", l2a.token(), employeeBody(code.toLowerCase(), "ADMIN_L2"), 201));
        assertThat(employee.get("designation").asText()).isEqualTo(code);
        assertThat(employee.get("designationLabel").asText()).isEqualTo(label);
        assertThat(employee.get("department").asText()).isEqualTo("Marketing");
        assertThat(employee.get("role").asText()).isEqualTo("ADMIN_L1");
        post("/v1/admin/employees", l2a.token(), employeeBody("NO_SUCH_ROLE", "ADMIN_L1"), 400);

        // Their access comes from the role's groups, plus self service
        String username = employee.get("username").asText();
        long userId = employee.get("userId").asLong();
        jdbc.update("UPDATE users SET is_first_login = FALSE, password = ? WHERE id = ?", passwordEncoder.encode(SEED_PASSWORD), userId);
        String token = login(username, SEED_PASSWORD);
        JsonNode me = data(get("/v1/access/me", token, 200));
        assertThat(me.get("designation").asText()).isEqualTo(code);
        assertThat(me.get("designationLabel").asText()).isEqualTo(label);
        assertThat(texts(me.get("permissions"))).contains("OUTREACH_LOG", "MEETING_SCHEDULE", "MEETING_RESPOND_OWN", "PAYSLIP_VIEW_OWN")
                .doesNotContain("EMPLOYEE_VIEW", "DEMO_VIEW");
        get("/v1/outreach/businesses", token, 200);
        get("/v1/admin/employees", token, 403);

        // Editing the groups applies at once; renaming the department moves the employees with it
        JsonNode edited = data(put(ROLES + "/" + roleId, l2a.token(), body("label", label, "department", "Sales & Marketing",
                "groups", List.of("CLIENT_OUTREACH_READ"), "active", true), 200));
        assertThat(edited.get("employeeCount").asLong()).isEqualTo(1);
        get("/v1/meetings", token, 403);
        JsonNode moved = data(get("/v1/admin/employees/" + employee.get("id").asLong(), l2a.token(), 200));
        assertThat(moved.get("department").asText()).isEqualTo("Sales & Marketing");

        // In use: deactivate, not delete. Inactive roles aren't given to new employees, but existing holders keep it
        assertThat(delete(ROLES + "/" + roleId, l2a.token(), 409).get("errorCode").asText()).isEqualTo("ROLE_IN_USE");
        put(ROLES + "/" + roleId, l2a.token(), body("label", label, "department", "Sales & Marketing",
                "groups", List.of("CLIENT_OUTREACH_READ"), "active", false), 200);
        assertThat(find(data(get("/v1/access/catalog", l1.token(), 200)).get("designations"), code)).isNull();
        post("/v1/admin/employees", l2a.token(), employeeBody(code, "ADMIN_L1"), 400);
        put("/v1/admin/employees/" + employee.get("id").asLong(), l2a.token(), body("designation", code), 200);
        get("/v1/outreach/businesses", token, 200);

        // Back to a built-in designation; then the role can go
        put("/v1/admin/employees/" + employee.get("id").asLong(), l2a.token(), body("designation", "DELIVERY_RIDER"), 200);
        JsonNode listed = find(data(get(ROLES, l2a.token(), 200)), code);
        assertThat(listed.get("active").asBoolean()).isFalse();
        delete(ROLES + "/" + roleId, l2a.token(), 200);
        assertThat(find(data(get(ROLES, l2a.token(), 200)), code)).isNull();
    }

    @Test
    void builtInRolesAreListedReadOnlyAndOnlyRoleManagersChangeRoles() throws Exception {
        JsonNode roles = data(get(ROLES, l2a.token(), 200));
        JsonNode superAdmin = find(roles, "SUPER_ADMIN");
        assertThat(superAdmin.get("custom").asBoolean()).isFalse();
        assertThat(superAdmin.get("id").isNull()).isTrue();
        assertThat(superAdmin.get("employeeCount").asLong()).isPositive();

        String username = uniq("rider");
        long userId = insertUser(username, "ADMIN_L1", "WEB", false, true);
        insertEmployee(userId, "DELIVERY_RIDER");
        String rider = login(username, SEED_PASSWORD);
        get(ROLES, rider, 403);
        post(ROLES, rider, roleBody("Rider Plus", List.of("CUSTOMER_ORDER_READ")), 403);
    }
}
