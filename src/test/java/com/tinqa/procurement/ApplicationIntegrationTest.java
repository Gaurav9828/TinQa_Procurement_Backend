package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end suite for the whole application: it starts the real Spring context (security, Flyway, Hibernate
 * validation, Redis) and exercises every module through its HTTP API, one nested group per module.
 */
@DisplayName("TinQa Procurement application")
class ApplicationIntegrationTest extends IntegrationTestSupport {

    @Nested
    @DisplayName("Startup and infrastructure")
    class Startup {

        @Test
        void appliesTheBaselineMigrationAndReportsHealthy() throws Exception {
            Integer applied = jdbc.queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL", Integer.class);
            // Every versioned migration on the classpath, so adding one doesn't need this test changed
            int migrations = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*__*.sql").length;
            assertThat(migrations).isPositive();
            assertThat(applied).isEqualTo(migrations);

            JsonNode health = get("/actuator/health", null, 200);
            assertThat(health.get("status").asText()).isEqualTo("UP");
        }

        @Test
        void configuredSuperAdminCanSignInStraightAway() throws Exception {
            JsonNode login = post("/auth/admin/login", null,
                    body("username", "test-superadmin", "password", "Test@SuperAdmin2026"), 200);
            assertThat(login.get("role").asText()).isEqualTo("ADMIN_L2");
            assertThat(login.get("firstLogin").asBoolean()).isFalse();

            JsonNode profile = data(get("/v1/admin/profile", login.get("accessToken").asText(), 200));
            assertThat(profile.get("username").asText()).isEqualTo("test-superadmin");
            assertThat(profile.get("displayName").asText()).isEqualTo("Super Admin");
            assertThat(profile.get("status").asText()).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = 'test-superadmin'", Integer.class)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Authentication and security")
    class Security {

        @Test
        void adminLoginIssuesBearerToken() throws Exception {
            JsonNode login = post("/auth/admin/login", null, body("username", l1.username(), "password", SEED_PASSWORD), 200);
            assertThat(login.get("tokenType").asText()).isEqualTo("Bearer");
            assertThat(login.get("role").asText()).isEqualTo("ADMIN_L1");
            assertThat(login.get("username").asText()).isEqualTo(l1.username());
            assertThat(login.get("expiresIn").asLong()).isEqualTo(3600000L);
            assertThat(login.get("accessToken").asText()).isNotBlank();
        }

        @Test
        void rejectsWrongPasswordAndUnknownUserAlike() throws Exception {
            JsonNode wrong = post("/auth/admin/login", null, body("username", l1.username(), "password", "nope"), 400);
            JsonNode unknown = post("/auth/admin/login", null, body("username", "ghost-user", "password", "nope"), 400);
            assertThat(wrong.get("message").asText()).isEqualTo("Invalid username or password");
            assertThat(unknown.get("message").asText()).isEqualTo("Invalid username or password");
            assertThat(wrong.get("errorCode").asText()).isEqualTo("BAD_REQUEST");
        }

        @Test
        void rejectsMissingAndInvalidTokens() throws Exception {
            JsonNode missing = get("/v1/orders", null, 401);
            assertThat(missing.get("errorCode").asText()).isEqualTo("UNAUTHORIZED");
            assertThat(missing.get("message").asText()).isEqualTo("Authentication is required.");

            JsonNode invalid = get("/v1/orders", "not-a-jwt", 401);
            assertThat(invalid.get("message").asText()).isEqualTo("The access token is invalid or has expired.");
        }

        @Test
        void logoutRevokesTheTokenAndIsIdempotent() throws Exception {
            String token = login(l1.username(), SEED_PASSWORD);
            get("/v1/notifications", token, 200);

            JsonNode logout = post("/auth/logout", token, null, 200);
            assertThat(logout.get("success").asBoolean()).isTrue();
            get("/v1/notifications", token, 401);

            post("/auth/logout", token, null, 200);
            post("/auth/logout", null, null, 200);
        }

        @Test
        void mobileLoginIsPublicAndMobileAccountsCannotUseAdminApis() throws Exception {
            JsonNode unknown = post("/auth/mobile/login", null, body("mobileNumber", "9000000001", "password", "x"), 400);
            assertThat(unknown.get("message").asText()).isEqualTo("Invalid username or password");

            String token = inspectorToken();
            get("/v1/notifications", token, 200);
            assertThat(get("/v1/admin/dealers", token, 403).get("errorCode").asText()).isEqualTo("FORBIDDEN");
            get("/v1/admin/items", token, 403);
            get("/v1/admin/products", token, 403);
            get("/v1/orders", token, 403);
        }

        @Test
        void webAccountCannotUseMobileLoginAndViceVersa() throws Exception {
            post("/auth/admin/login", null, body("username", inspector.username(), "password", SEED_PASSWORD), 400);
        }

        @Test
        void adminL1CannotReachAdminL2OnlyEndpoints() throws Exception {
            JsonNode denied = get("/v1/admin/employees/pending-approvals", l1.token(), 403);
            assertThat(denied.get("message").asText()).isEqualTo("You do not have permission to access this resource.");
            get("/v1/product-approvals", l1.token(), 403);
            get("/admin/profile-approvals", l1.token(), 403);
        }

        @Test
        void internalApiNeedsTheSharedKeyNotAnAdminToken() throws Exception {
            String path = "/v1/internal/item-stock/movements/" + randomUuid();
            assertThat(get(path, null, 401).get("errorCode").asText()).isEqualTo("INTERNAL_AUTH_FAILED");
            get(path, l2a.token(), 401);
            perform(MockMvcRequestBuilders.get(path).header(INTERNAL_KEY_HEADER, "wrong-key"), 401);
            perform(MockMvcRequestBuilders.get(path).header(INTERNAL_KEY_HEADER, INTERNAL_API_KEY), 404);
        }

        @Test
        void firstLoginUserMustSetAPasswordBeforeAnythingElse() throws Exception {
            String username = uniq("first");
            long userId = insertUser(username, "ADMIN_L1", "WEB", true, true);
            insertEmployee(userId);
            jdbc.update("UPDATE employee SET status = 'FIRST_LOGIN' WHERE user_id = ?", userId);

            JsonNode login = post("/auth/admin/login", null, body("username", username, "password", SEED_PASSWORD), 200);
            assertThat(login.get("firstLogin").asBoolean()).isTrue();
            String token = login.get("accessToken").asText();

            JsonNode blocked = get("/v1/notifications", token, 403);
            assertThat(blocked.get("errorCode").asText()).isEqualTo("PASSWORD_CHANGE_REQUIRED");

            JsonNode weak = post("/auth/first-login/password", token, body("newPassword", "short", "confirmNewPassword", "short"), 400);
            assertThat(weak.get("errors").get(0).get("field").asText()).isEqualTo("newPassword");
            JsonNode mismatch = post("/auth/first-login/password", token,
                    body("newPassword", "New!Passw0rd#2026", "confirmNewPassword", "Other!Passw0rd#2026"), 400);
            assertThat(mismatch.get("errors").get(0).get("field").asText()).isEqualTo("confirmNewPassword");
            JsonNode reused = post("/auth/first-login/password", token,
                    body("newPassword", SEED_PASSWORD, "confirmNewPassword", SEED_PASSWORD), 400);
            assertThat(reused.get("errorCode").asText()).isEqualTo("PASSWORD_REUSED");

            JsonNode set = post("/auth/first-login/password", token,
                    body("newPassword", "New!Passw0rd#2026", "confirmNewPassword", "New!Passw0rd#2026"), 200);
            assertThat(set.get("requiresLogin").asBoolean()).isTrue();

            get("/v1/notifications", token, 401);
            JsonNode relogin = post("/auth/admin/login", null, body("username", username, "password", "New!Passw0rd#2026"), 200);
            assertThat(relogin.get("firstLogin").asBoolean()).isFalse();
            assertThat(jdbc.queryForObject("SELECT status FROM employee WHERE user_id = ?", String.class, userId)).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_audit_events WHERE user_id = ? AND event_type = 'FIRST_LOGIN_PASSWORD_SET'",
                    Integer.class, userId)).isEqualTo(1);

            String fresh = relogin.get("accessToken").asText();
            JsonNode again = post("/auth/first-login/password", fresh,
                    body("newPassword", "Another!Passw0rd#1", "confirmNewPassword", "Another!Passw0rd#1"), 409);
            assertThat(again.get("errorCode").asText()).isEqualTo("FIRST_LOGIN_NOT_REQUIRED");
        }

        @Test
        void changePasswordChecksTheCurrentPasswordAndSignsOutOtherSessions() throws Exception {
            String username = uniq("changer");
            long userId = insertUser(username, "ADMIN_L1", "WEB", false, true);
            String token = login(username, SEED_PASSWORD);

            JsonNode wrongCurrent = post("/auth/change-password", token, body("currentPassword", "wrong",
                    "newPassword", "Brand!New#Pass1", "confirmNewPassword", "Brand!New#Pass1"), 400);
            assertThat(wrongCurrent.get("message").asText()).isEqualTo("Current password is incorrect");

            JsonNode changed = post("/auth/change-password", token, body("currentPassword", SEED_PASSWORD,
                    "newPassword", "Brand!New#Pass1", "confirmNewPassword", "Brand!New#Pass1"), 200);
            assertThat(changed.get("code").asText()).isEqualTo("PASSWORD_CHANGED");
            get("/v1/notifications", token, 401);
            login(username, "Brand!New#Pass1");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_audit_events WHERE user_id = ? AND event_type = 'PASSWORD_CHANGED'",
                    Integer.class, userId)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Request handling and errors")
    class Errors {

        @Test
        void malformedJsonIsRejected() throws Exception {
            JsonNode response = post("/v1/orders", l1.token(), "{not json", 400);
            assertThat(response.get("errorCode").asText()).isEqualTo("MALFORMED_REQUEST");
            assertThat(response.get("success").asBoolean()).isFalse();
            assertThat(response.get("path").asText()).isEqualTo("/v1/orders");
        }

        @Test
        void bodyValidationReportsEveryField() throws Exception {
            JsonNode response = post("/v1/orders", l1.token(), body(), 400);
            assertThat(response.get("errorCode").asText()).isEqualTo("VALIDATION_FAILED");
            assertThat(response.get("errors").findValuesAsText("field"))
                    .contains("dealerId", "itemId", "orderQuantity", "unitType", "unitPrice", "shipmentPrice", "orderDate");
        }

        @Test
        void unsafeInputIsRejectedInBodiesAndQueryParameters() throws Exception {
            JsonNode inBody = post("/v1/admin/items/categories", l1.token(), body("name", "<b>bold</b>", "code", uniq("C")), 400);
            assertThat(inBody.get("errorCode").asText()).isEqualTo("UNSAFE_INPUT");
            JsonNode inQuery = get("/v1/admin/employees?search=%3Cscript%3E", l1.token(), 400);
            assertThat(inQuery.get("errorCode").asText()).isEqualTo("UNSAFE_INPUT");
        }

        @Test
        void unknownRoutesAndMethodsAreReported() throws Exception {
            assertThat(get("/v1/does-not-exist", l1.token(), 404).get("errorCode").asText()).isEqualTo("RESOURCE_NOT_FOUND");
            assertThat(delete("/v1/notifications", l1.token(), 405).get("errorCode").asText()).isEqualTo("METHOD_NOT_ALLOWED");
        }

        @Test
        void successResponsesCarryMessagePathAndTimestamp() throws Exception {
            JsonNode response = get("/v1/notifications", l1.token(), 200);
            assertThat(response.get("success").asBoolean()).isTrue();
            assertThat(response.get("message").asText()).isEqualTo("Notifications Retrieved Successfully");
            assertThat(response.get("path").asText()).isEqualTo("/v1/notifications");
            assertThat(response.get("timestamp").asText()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("Employees")
    class Employees {

        private Map<String, Object> employeeBody(String role) {
            return withPanAndAddress(body("username", uniq("emp"), "email", uniq("Emp") + "@Company.com",
                    "firstName", letters(), "lastName", letters(), "dateOfBirth", "1990-01-15", "gender", "MALE",
                    "designation", "DEALER_MANAGER", "employmentType", "FULL_TIME",
                    "joiningDate", "2015-06-01", "phone", nextLocalMobile(), "role", role));
        }

        @Test
        void adminL2CreatesActiveAccountsThatStartWithFirstLogin() throws Exception {
            Map<String, Object> request = employeeBody("ADMIN_L1");
            JsonNode created = data(post("/v1/admin/employees", l2a.token(), request, 201));

            assertThat(created.get("status").asText()).isEqualTo("FIRST_LOGIN");
            assertThat(created.get("employeeCode").asText()).matches("^EMP-\\d{4}$");
            assertThat(created.get("phone").asText()).isEqualTo("+91" + request.get("phone"));
            assertThat(created.get("displayName").asText()).isEqualTo(request.get("firstName") + " " + request.get("lastName"));
            assertThat(created.get("workEmail").asText()).isEqualTo(((String) request.get("email")).toLowerCase());
            assertThat(created.get("salaryCurrency").asText()).isEqualTo("INR");
            assertThat(created.get("salaryAmount").decimalValue()).isEqualByComparingTo("0");
            assertThat(created.get("createdByUsername").asText()).isEqualTo(l2a.username());

            // No shared default password: the employee is emailed a link to choose theirs, at their personal email
            post("/auth/admin/login", null, body("username", request.get("username"), "password", DEFAULT_EMPLOYEE_PASSWORD), 400);
            String token = lastPasswordLink((String) request.get("personalEmail"), true);
            setPasswordWithLink(token, (String) request.get("username"), "Employee!Passw0rd#1");
            assertThat(data(get("/v1/admin/employees/" + created.get("id").asLong(), l2a.token(), 200)).get("status").asText())
                    .isEqualTo("ACTIVE");
        }

        @Test
        void duplicateDetailsAreReportedPerField() throws Exception {
            Map<String, Object> request = employeeBody("ADMIN_L1");
            post("/v1/admin/employees", l2a.token(), request, 201);

            Map<String, Object> duplicate = employeeBody("ADMIN_L1");
            duplicate.put("username", request.get("username"));
            duplicate.put("phone", "0" + request.get("phone"));
            JsonNode conflict = post("/v1/admin/employees", l2a.token(), duplicate, 409);
            assertThat(conflict.get("errorCode").asText()).isEqualTo("DUPLICATE_VALUE");
            assertThat(conflict.get("errors").findValuesAsText("field")).containsExactlyInAnyOrder("username", "phone");
        }

        @Test
        void joiningDateMustFollowTheEighteenthBirthday() throws Exception {
            Map<String, Object> request = employeeBody("ADMIN_L1");
            request.put("dateOfBirth", "2000-01-01");
            request.put("joiningDate", "2010-01-01");
            JsonNode invalid = post("/v1/admin/employees", l2a.token(), request, 400);
            assertThat(invalid.get("errors").get(0).get("field").asText()).isEqualTo("joiningDate");
        }

        @Test
        void accountsCreatedByAdminL1WaitForAdminL2Approval() throws Exception {
            Map<String, Object> request = employeeBody("ADMIN_L1");
            JsonNode created = data(post("/v1/admin/employees", l1.token(), request, 201));
            long id = created.get("id").asLong();
            assertThat(created.get("status").asText()).isEqualTo("APPROVAL_PENDING");
            assertThat(notificationTitles(l2a)).contains("Employee awaiting approval");

            // Nothing is emailed until the account is approved
            org.mockito.Mockito.verify(staffMailer, org.mockito.Mockito.never()).sendPasswordLink(
                    org.mockito.ArgumentMatchers.eq((String) request.get("personalEmail")), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyLong());

            JsonNode pending = data(get("/v1/admin/employees/pending-approvals", l2a.token(), 200));
            assertThat(pending.findValues("id").stream().map(JsonNode::asLong)).contains(id);

            JsonNode noReason = patch("/v1/admin/employees/" + id + "/approval", l2a.token(), body("decision", "REJECTED"), 400);
            assertThat(noReason.get("errors").get(0).get("field").asText()).isEqualTo("reason");

            JsonNode approved = patch("/v1/admin/employees/" + id + "/approval", l2a.token(), body("decision", "APPROVED"), 200);
            assertThat(approved.get("message").asText()).isEqualTo("Employee approved successfully");
            assertThat(data(approved).get("status").asText()).isEqualTo("FIRST_LOGIN");
            assertThat(data(approved).get("approvalDecidedByUsername").asText()).isEqualTo(l2a.username());
            assertThat(notificationTitles(l1)).contains("Employee approved");

            JsonNode twice = patch("/v1/admin/employees/" + id + "/approval", l2a.token(), body("decision", "APPROVED"), 409);
            assertThat(twice.get("errorCode").asText()).isEqualTo("EMPLOYEE_NOT_PENDING_APPROVAL");
            setPasswordWithLink(lastPasswordLink((String) request.get("personalEmail"), true), (String) request.get("username"),
                    "Employee!Passw0rd#2");
        }

        @Test
        void newAdminL2AccountsNeedASecondAdminL2() throws Exception {
            long id = data(post("/v1/admin/employees", l2a.token(), employeeBody("ADMIN_L2"), 201)).get("id").asLong();

            JsonNode self = patch("/v1/admin/employees/" + id + "/approval", l2a.token(), body("decision", "APPROVED"), 403);
            assertThat(self.get("errorCode").asText()).isEqualTo("EMPLOYEE_APPROVAL_SELF_DECISION");

            JsonNode rejected = data(patch("/v1/admin/employees/" + id + "/approval", l2b.token(),
                    body("decision", "REJECTED", "reason", "Not needed"), 200));
            assertThat(rejected.get("status").asText()).isEqualTo("IN_ACTIVE");
            assertThat(rejected.get("approvalRejectionReason").asText()).isEqualTo("Not needed");
        }

        @Test
        void updatesValidateDatesAndProtectWorkflowStatuses() throws Exception {
            long id = data(post("/v1/admin/employees", l2a.token(), employeeBody("ADMIN_L1"), 201)).get("id").asLong();

            JsonNode updated = data(put("/v1/admin/employees/" + id, l1.token(), body("designation", "store_manager", "middleName", "Kumar"), 200));
            assertThat(updated.get("designation").asText()).isEqualTo("STORE_MANAGER");
            assertThat(updated.get("designationLabel").asText()).isEqualTo("Store Manager");
            assertThat(updated.get("middleName").asText()).isEqualTo("Kumar");
            JsonNode cleared = data(put("/v1/admin/employees/" + id, l1.token(), body("middleName", " "), 200));
            assertThat(cleared.get("middleName").isNull()).isTrue();

            put("/v1/admin/employees/" + id, l1.token(), body("status", "APPROVAL_PENDING"), 400);
            JsonNode leaving = put("/v1/admin/employees/" + id, l1.token(), body("leavingDate", "2014-01-01"), 400);
            assertThat(leaving.get("errors").get(0).get("field").asText()).isEqualTo("leavingDate");
            get("/v1/admin/employees/999999", l1.token(), 404);
        }

        @Test
        void listSearchesAndPaginates() throws Exception {
            Map<String, Object> request = employeeBody("ADMIN_L1");
            post("/v1/admin/employees", l2a.token(), request, 201);

            JsonNode page = data(get("/v1/admin/employees?search=" + ((String) request.get("lastName")).toUpperCase() + "&size=5",
                    l1.token(), 200));
            assertThat(page.get("content")).hasSize(1);
            assertThat(page.get("content").get(0).get("username").asText()).isEqualTo(request.get("username"));

            JsonNode byStatus = data(get("/v1/admin/employees?status=FIRST_LOGIN&search=" + request.get("lastName"), l1.token(), 200));
            assertThat(byStatus.get("content")).hasSize(1);
        }

        @Test
        void deletionIsRequestedFirstAndFinalisedByAdminL2Only() throws Exception {
            long id = data(post("/v1/admin/employees", l2a.token(), employeeBody("ADMIN_L1"), 201)).get("id").asLong();

            JsonNode requested = data(patch("/v1/admin/employees/" + id + "/request-deletion", l1.token(), null, 200));
            assertThat(requested.get("status").asText()).isEqualTo("WAITING_FOR_DELETION");
            JsonNode twice = patch("/v1/admin/employees/" + id + "/request-deletion", l1.token(), null, 400);
            assertThat(twice.get("message").asText()).isEqualTo("Employee is already waiting for deletion");

            delete("/v1/admin/employees/" + id, l1.token(), 403);
            JsonNode deleted = data(delete("/v1/admin/employees/" + id, l2a.token(), 200));
            assertThat(deleted.get("status").asText()).isEqualTo("DELETED");
            assertThat(deleted.get("deletedByUsername").asText()).isEqualTo(l2a.username());
        }

        @Test
        void deletedEmployeesAreKeptAndListedOnlyUnderTheirStatus() throws Exception {
            Map<String, Object> request = employeeBody("ADMIN_L1");
            long id = data(post("/v1/admin/employees", l2a.token(), request, 201)).get("id").asLong();
            patch("/v1/admin/employees/" + id + "/request-deletion", l1.token(), null, 200);
            delete("/v1/admin/employees/" + id, l2a.token(), 200);

            // The row and its login stay, but the account can no longer sign in
            assertThat(jdbc.queryForObject("SELECT status FROM employee WHERE id = ?", String.class, id)).isEqualTo("DELETED");
            post("/auth/admin/login", null, body("username", request.get("username"), "password", DEFAULT_EMPLOYEE_PASSWORD), 400);

            String lastName = (String) request.get("lastName");
            assertThat(data(get("/v1/admin/employees?search=" + lastName, l2a.token(), 200)).get("content")).isEmpty();
            JsonNode listed = data(get("/v1/admin/employees?status=DELETED&search=" + lastName, l2a.token(), 200));
            assertThat(listed.get("content")).hasSize(1);
            assertThat(data(get("/v1/admin/employees/" + id, l2a.token(), 200)).get("status").asText()).isEqualTo("DELETED");

            // Only someone who can delete employees sees deleted records
            get("/v1/admin/employees?status=DELETED", l1.token(), 403);
            get("/v1/admin/employees/" + id, l1.token(), 404);

            // A deleted record is read-only
            JsonNode locked = put("/v1/admin/employees/" + id, l2a.token(), body("firstName", letters()), 409);
            assertThat(locked.get("errorCode").asText()).isEqualTo("EMPLOYEE_DELETED");
            delete("/v1/admin/employees/" + id, l2a.token(), 400);
        }

        @Test
        void newEmployeesNeedAUniquePersonalPanAndAnAddress() throws Exception {
            Map<String, Object> missing = employeeBody("ADMIN_L1");
            missing.remove("panNumber");
            missing.remove("pincode");
            JsonNode invalid = post("/v1/admin/employees", l2a.token(), missing, 400);
            assertThat(invalid.get("errors").findValuesAsText("field")).contains("panNumber", "pincode");

            Map<String, Object> business = employeeBody("ADMIN_L1");
            business.put("panNumber", "ABCCE1234F");
            post("/v1/admin/employees", l2a.token(), business, 400);

            Map<String, Object> request = employeeBody("ADMIN_L1");
            request.put("googleMapsUrl", "https://maps.app.goo.gl/abc123");
            JsonNode created = data(post("/v1/admin/employees", l2a.token(), request, 201));
            assertThat(created.get("panNumber").asText()).isEqualTo(request.get("panNumber"));
            assertThat(created.get("pincode").asText()).isEqualTo("560001");
            assertThat(created.get("googleMapsUrl").asText()).isEqualTo("https://maps.app.goo.gl/abc123");

            Map<String, Object> samePan = employeeBody("ADMIN_L1");
            samePan.put("panNumber", request.get("panNumber"));
            JsonNode conflict = post("/v1/admin/employees", l2a.token(), samePan, 409);
            assertThat(conflict.get("errors").findValuesAsText("field")).containsExactly("panNumber");
        }
    }

    @Nested
    @DisplayName("Admin profile and profile approvals")
    class AdminProfile {

        @Test
        void profileBelongsToTheSignedInAdmin() throws Exception {
            JsonNode profile = data(get("/v1/admin/profile", l1.token(), 200));
            assertThat(profile.get("employeeId").asLong()).isEqualTo(l1.employeeId());
            assertThat(profile.get("username").asText()).isEqualTo(l1.username());
            assertThat(profile.get("workEmail").asText()).isEqualTo(l1.email());
            assertThat(profile.get("approvalPending").asBoolean()).isFalse();

            get("/v1/admin/profile", inspectorToken(), 403);
        }

        @Test
        void adminL2ChangesApprovalControlledFieldsDirectly() throws Exception {
            String lastName = letters();
            JsonNode response = put("/v1/admin/profile", l2b.token(), body("lastName", lastName, "displayName", "Seed Lead"), 200);
            assertThat(response.get("message").asText()).isEqualTo("Profile Changes Saved Successfully");
            assertThat(data(response).get("lastName").asText()).isEqualTo(lastName);
            assertThat(data(response).get("displayName").asText()).isEqualTo("Seed Lead");
            assertThat(data(response).get("approvalPending").asBoolean()).isFalse();
        }

        @Test
        void adminL1ChangesGoThroughAdminL2Approval() throws Exception {
            String originalFirstName = data(get("/v1/admin/profile", l1.token(), 200)).get("firstName").asText();
            String phone = nextLocalMobile();

            JsonNode submitted = put("/v1/admin/profile", l1.token(), body("firstName", "Renamed", "primaryPhone", phone), 200);
            assertThat(submitted.get("message").asText()).isEqualTo("Profile Changes Submitted For Approval");
            JsonNode profile = data(submitted);
            assertThat(profile.get("firstName").asText()).isEqualTo(originalFirstName);
            assertThat(profile.get("primaryPhone").asText()).isEqualTo("+91" + phone);
            assertThat(profile.get("approvalPending").asBoolean()).isTrue();
            assertThat(StreamSupport.stream(profile.get("pendingApprovalFields").spliterator(), false).map(JsonNode::asText))
                    .containsExactly("firstName");

            JsonNode second = put("/v1/admin/profile", l1.token(), body("lastName", "Other"), 409);
            assertThat(second.get("errorCode").asText()).isEqualTo("CONFLICT");

            long requestId = pendingRequestFor(l1.employeeId());
            JsonNode single = data(get("/admin/profile-approvals/" + requestId, l2a.token(), 200));
            assertThat(single.get("firstName").asText()).isEqualTo("Renamed");
            assertThat(single.get("requestedByUsername").asText()).isEqualTo(l1.username());

            put("/admin/profile-approvals/" + requestId, l2a.token(), body("decision", "PENDING"), 400);
            JsonNode noReason = put("/admin/profile-approvals/" + requestId, l2a.token(), body("decision", "REJECTED"), 400);
            assertThat(noReason.get("message").asText()).isEqualTo("Rejection reason is required");

            JsonNode rejected = put("/admin/profile-approvals/" + requestId, l2a.token(),
                    body("decision", "REJECTED", "rejectionReason", "Use your legal name"), 200);
            assertThat(rejected.get("message").asText()).isEqualTo("Profile Changes Rejected Successfully");
            assertThat(data(rejected).get("status").asText()).isEqualTo("REJECTED");
            assertThat(notificationTitles(l1)).contains("Profile Change Request Rejected");
            assertThat(data(get("/v1/admin/profile", l1.token(), 200)).get("approvalPending").asBoolean()).isFalse();

            put("/v1/admin/profile", l1.token(), body("firstName", "Approved"), 200);
            long secondRequest = pendingRequestFor(l1.employeeId());
            JsonNode approved = put("/admin/profile-approvals/" + secondRequest, l2a.token(), body("decision", "APPROVED"), 200);
            assertThat(approved.get("message").asText()).isEqualTo("Profile Changes Approved Successfully");
            assertThat(data(get("/v1/admin/profile", l1.token(), 200)).get("firstName").asText()).isEqualTo("Approved");
            assertThat(notificationTitles(l1)).contains("Profile Change Request Approved");

            JsonNode processed = put("/admin/profile-approvals/" + secondRequest, l2a.token(), body("decision", "APPROVED"), 409);
            assertThat(processed.get("message").asText()).isEqualTo("Profile change request has already been processed");
        }

        private long pendingRequestFor(long employeeId) throws Exception {
            JsonNode pending = data(get("/admin/profile-approvals", l2a.token(), 200));
            return StreamSupport.stream(pending.spliterator(), false)
                    .filter(request -> request.get("employeeId").asLong() == employeeId)
                    .findFirst().orElseThrow().get("requestId").asLong();
        }
    }

    @Nested
    @DisplayName("Dealers and categories")
    class Dealers {

        @Test
        void dealersAreAssignedItemCategories() throws Exception {
            String code = uniq("SHARED");
            JsonNode created = data(post("/v1/admin/items/categories", l1.token(), body("name", "Item " + code, "code", code), 201));
            assertThat(created.get("code").asText()).isEqualTo(code);
            assertThat(created.get("isActive").asBoolean()).isTrue();

            JsonNode duplicate = post("/v1/admin/items/categories", l1.token(), body("name", "Other " + code, "code", code), 400);
            assertThat(duplicate.get("message").asText()).isEqualTo("Category code already exists: " + code);

            // The dealer screens offer the item categories
            JsonNode list = data(get("/v1/admin/dealers/categories", l1.token(), 200));
            assertThat(list.findValuesAsText("code")).contains(code);

            long categoryId = created.get("id").asLong();
            JsonNode dealer = data(post("/v1/admin/dealers", l1.token(), dealerBody(List.of(categoryId)), 201));
            assertThat(dealer.get("categories").findValues("id").stream().map(JsonNode::asLong)).containsExactly(categoryId);

            JsonNode unknown = post("/v1/admin/dealers", l1.token(), dealerBody(List.of(999999L)), 404);
            assertThat(unknown.get("errorCode").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        }

        @Test
        void dealerLifecycle() throws Exception {
            long first = createCategory(l1);
            long second = createCategory(l1);
            Map<String, Object> request = dealerBody(List.of(first));
            JsonNode dealer = data(post("/v1/admin/dealers", l1.token(), request, 201));
            long id = dealer.get("id").asLong();
            assertThat(dealer.get("offersShipping").asBoolean()).isFalse();
            assertThat(dealer.get("doesBulkDealing").asBoolean()).isTrue();
            assertThat(dealer.get("createdBy").asLong()).isEqualTo(l1.userId());

            assertThat(data(get("/v1/admin/dealers/" + id, l1.token(), 200)).get("name").asText()).isEqualTo(request.get("name"));

            request.put("name", "Renamed Dealer");
            request.put("district", "Bengaluru Urban");
            request.put("isGstVerified", true);
            JsonNode updated = data(put("/v1/admin/dealers/" + id, l2a.token(), request, 200));
            assertThat(updated.get("name").asText()).isEqualTo("Renamed Dealer");
            assertThat(updated.get("district").asText()).isEqualTo("Bengaluru Urban");
            assertThat(updated.get("updatedBy").asLong()).isEqualTo(l2a.userId());
            // Flags left out of the update keep their current values
            assertThat(updated.get("offersShipping").asBoolean()).isFalse();
            assertThat(updated.get("doesWholesaleDealing").asBoolean()).isTrue();

            assertThat(data(patch("/v1/admin/dealers/" + id + "/toggle-status", l1.token(), null, 200)).get("isActive").asBoolean()).isFalse();
            assertThat(data(patch("/v1/admin/dealers/" + id + "/toggle-status", l1.token(), null, 200)).get("isActive").asBoolean()).isTrue();

            assertThat(data(post("/v1/admin/dealers/" + id + "/categories", l1.token(), List.of(second), 200)).get("categories")).hasSize(2);
            assertThat(data(put("/v1/admin/dealers/" + id + "/categories", l1.token(), List.of(second), 200)).get("categories")).hasSize(1);
            delete("/v1/admin/dealers/" + id + "/categories/" + first, l1.token(), 404);
            assertThat(data(delete("/v1/admin/dealers/" + id + "/categories/" + second, l1.token(), 200)).get("categories")).isEmpty();

            JsonNode page = data(get("/v1/admin/dealers?size=1000", l1.token(), 200));
            assertThat(page.get("content").findValues("id").stream().map(JsonNode::asLong)).contains(id);

            // Removing a dealer is an approval-level action
            delete("/v1/admin/dealers/" + id, l1.token(), 403);
            delete("/v1/admin/dealers/" + id, l2a.token(), 200);
            assertThat(data(get("/v1/admin/dealers/" + id, l1.token(), 200)).get("isActive").asBoolean()).isFalse();
            get("/v1/admin/dealers/999999", l1.token(), 404);
        }

        @Test
        void dealerListIsFilteredOnTheServer() throws Exception {
            long category = createCategory(l1);
            long other = createCategory(l1);
            String city = "Town" + letters();
            Map<String, Object> firstBody = dealerBody(List.of(category));
            firstBody.put("city", city);
            long first = data(post("/v1/admin/dealers", l1.token(), firstBody, 201)).get("id").asLong();
            long second = data(post("/v1/admin/dealers", l1.token(), dealerBody(List.of(category, other)), 201)).get("id").asLong();
            long third = data(post("/v1/admin/dealers", l1.token(), dealerBody(List.of(other)), 201)).get("id").asLong();

            assertThat(dealerIds("categoryId=" + category)).containsExactlyInAnyOrder(first, second);
            // A dealer in both categories is listed once
            assertThat(dealerIds("categoryId=" + category + "&categoryId=" + other)).containsExactlyInAnyOrder(first, second, third);
            assertThat(dealerIds("city=" + city.toLowerCase())).containsExactly(first);
            assertThat(dealerIds("search=" + firstBody.get("email").toString().toUpperCase())).containsExactly(first);
            assertThat(dealerIds("search=" + city.substring(4) + "&categoryId=" + category)).containsExactly(first);
            assertThat(data(get("/v1/admin/dealers/cities", l1.token(), 200))).extracting(JsonNode::asText).contains(city);

            patch("/v1/admin/dealers/" + second + "/toggle-status", l1.token(), null, 200);
            assertThat(dealerIds("categoryId=" + category + "&isActive=false")).containsExactly(second);
            assertThat(dealerIds("categoryId=" + category + "&isActive=true")).containsExactly(first);
        }

        private List<Long> dealerIds(String query) throws Exception {
            return topLevelIds(data(get("/v1/admin/dealers?size=1000&" + query, l1.token(), 200)));
        }

        @Test
        void dealerTaxAndAddressFieldsAreValidated() throws Exception {
            Map<String, Object> request = dealerBody(List.of(createCategory(l1)));
            request.put("gstin", "BADGSTIN");
            request.put("pincode", "012345");
            JsonNode invalid = post("/v1/admin/dealers", l1.token(), request, 400);
            assertThat(invalid.get("errors").findValuesAsText("field")).contains("gstin", "pincode");
        }
    }

    @Nested
    @DisplayName("Items and warranties")
    class Items {

        @Test
        void itemLifecycleWithWarranties() throws Exception {
            long category = createCategory(l1);
            Map<String, Object> request = itemBody(category);
            JsonNode item = data(post("/v1/admin/items", l1.token(), request, 201));
            long id = item.get("id").asLong();
            long warrantyId = item.get("warranties").get(0).get("id").asLong();
            assertThat(item.get("categories").findValues("id").stream().map(JsonNode::asLong)).containsExactly(category);
            assertThat(item.get("warranties").get(0).get("title").asText()).isEqualTo("One Year");

            // The SKU is always generated (TQ-yyyyMMdd-NNNN, numbered per day); a client-sent one is ignored
            String sku = item.get("sku").asText();
            String todayPrefix = "TQ-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
            assertThat(sku).startsWith(todayPrefix).matches("TQ-\\d{8}-\\d{4,}");
            request.put("sku", "MY-OWN-SKU");
            String nextSku = data(post("/v1/admin/items", l1.token(), request, 201)).get("sku").asText();
            assertThat(Integer.parseInt(nextSku.substring(todayPrefix.length())))
                    .isEqualTo(Integer.parseInt(sku.substring(todayPrefix.length())) + 1);

            Map<String, Object> update = itemBody(category);
            update.put("name", "Updated Item");
            update.put("sku", "CHANGED-SKU");
            update.put("warranties", List.of(
                    body("id", warrantyId, "warrantyType", "MANUFACTURER", "title", "Two Years", "durationValue", 2, "durationUnit", "YEARS"),
                    body("warrantyType", "SELLER", "title", "Seller Cover", "durationValue", 30, "durationUnit", "DAYS")));
            JsonNode updated = data(put("/v1/admin/items/" + id, l2a.token(), update, 200));
            assertThat(updated.get("name").asText()).isEqualTo("Updated Item");
            assertThat(updated.get("sku").asText()).isEqualTo(sku);
            assertThat(updated.get("warranties").findValuesAsText("title")).containsExactlyInAnyOrder("Two Years", "Seller Cover");

            update.put("warranties", List.of(body("id", 999999, "warrantyType", "SELLER", "title", "Foreign",
                    "durationValue", 1, "durationUnit", "DAYS")));
            JsonNode foreign = put("/v1/admin/items/" + id, l2a.token(), update, 400);
            assertThat(foreign.get("message").asText()).isEqualTo("Warranty 999999 does not belong to item " + id);

            assertThat(data(get("/v1/admin/items/" + id + "/warranties", l1.token(), 200))).hasSize(2);
            assertThat(data(get("/v1/admin/items?size=1000", l1.token(), 200)).get("content").findValues("id").stream()
                    .map(JsonNode::asLong)).contains(id);

            delete("/v1/admin/items/" + id, l1.token(), 200);
            assertThat(data(get("/v1/admin/items/" + id, l1.token(), 200)).get("isActive").asBoolean()).isFalse();
            get("/v1/admin/items/999999", l1.token(), 404);
        }

        @Test
        void itemListIsFilteredOnTheServer() throws Exception {
            long category = createCategory(l1);
            long other = createCategory(l1);
            Map<String, Object> kgBody = itemBody(category);
            kgBody.put("unitOfMeasure", "KG");
            kgBody.put("name", "Bulk" + letters());
            JsonNode kg = data(post("/v1/admin/items", l1.token(), kgBody, 201));
            long kgId = kg.get("id").asLong();
            long pcsId = data(post("/v1/admin/items", l1.token(), itemBody(category), 201)).get("id").asLong();
            long otherId = data(post("/v1/admin/items", l1.token(), itemBody(other), 201)).get("id").asLong();
            JsonNode both = data(post("/v1/admin/items", l1.token(), itemBody(category, other), 201));
            long bothId = both.get("id").asLong();
            assertThat(both.get("categories")).hasSize(2);

            assertThat(itemIds("categoryId=" + category)).containsExactlyInAnyOrder(kgId, pcsId, bothId);
            // An item in both categories is listed once
            assertThat(itemIds("categoryId=" + category + "&categoryId=" + other)).containsExactlyInAnyOrder(kgId, pcsId, otherId, bothId);
            assertThat(itemIds("categoryId=" + category + "&unitOfMeasure=kg")).containsExactly(kgId);
            assertThat(itemIds("search=" + kg.get("sku").asText().toLowerCase())).containsExactly(kgId);
            assertThat(itemIds("search=" + kgBody.get("name").toString().toUpperCase())).containsExactly(kgId);

            delete("/v1/admin/items/" + pcsId, l1.token(), 200);
            assertThat(itemIds("categoryId=" + category + "&isActive=false")).containsExactly(pcsId);
            assertThat(itemIds("categoryId=" + category + "&isActive=true")).containsExactlyInAnyOrder(kgId, bothId);
        }

        private List<Long> itemIds(String query) throws Exception {
            return topLevelIds(data(get("/v1/admin/items?size=1000&" + query, l1.token(), 200)));
        }

        @Test
        void itemsNeedAtLeastOneKnownCategory() throws Exception {
            JsonNode none = post("/v1/admin/items", l1.token(), itemBody(), 400);
            assertThat(none.get("errors").findValuesAsText("field")).contains("categoryIds");
            post("/v1/admin/items", l1.token(), itemBody(createCategory(l1), 999999L), 404);
        }
    }

    @Nested
    @DisplayName("Orders")
    class Orders {

        @Test
        void createComputesTotalsAndStartsAsQuotation() throws Exception {
            long dealer = createDealer();
            long item = createItem();
            JsonNode order = createOrder(dealer, item, "10");
            assertThat(order.get("orderStatus").asText()).isEqualTo("QUOTATION");
            assertThat(order.get("orderNumber").asText()).matches("^ORD-\\d+-[0-9A-F]{4}$");
            assertThat(order.get("totalPrice").decimalValue()).isEqualByComparingTo("1050.00");
            assertThat(order.get("createdBy").asLong()).isEqualTo(l1.userId());
            assertThat(historyCount(order.get("id").asLong())).isEqualTo(1);

            Map<String, Object> invalid = orderBody(dealer, item, "1");
            invalid.put("expectedDelivery", LocalDate.now().minusDays(30).toString());
            JsonNode early = post("/v1/orders", l1.token(), invalid, 400);
            assertThat(early.get("errors").get(0).get("field").asText()).isEqualTo("expectedDelivery");

            post("/v1/orders", l1.token(), orderBody(999999, item, "1"), 404);
        }

        @Test
        void fullForwardWorkflowWithRoleRules() throws Exception {
            long dealer = createDealer();
            long item = createItem();
            long id = createOrder(dealer, item, "10").get("id").asLong();

            JsonNode edited = data(put("/v1/orders/" + id, l1.token(), body("orderQuantity", 20), 200));
            assertThat(edited.get("totalPrice").decimalValue()).isEqualByComparingTo("2050.00");

            moveOrder(id, l1, "PENDING");
            JsonNode l1Edit = put("/v1/orders/" + id, l1.token(), body("orderQuantity", 5), 403);
            assertThat(l1Edit.get("message").asText()).isEqualTo("You don't have permission to edit an order that is waiting for approval.");
            put("/v1/orders/" + id, l2a.token(), body("unitPrice", 10), 200);

            JsonNode viaStatus = patch("/v1/orders/" + id + "/status", l2a.token(), body("status", "CONFIRMED"), 409);
            assertThat(viaStatus.get("errorCode").asText()).isEqualTo("INVALID_STATUS_TRANSITION");
            patch("/v1/orders/" + id + "/approval", l1.token(), body("decision", "DEALER_LEVEL_PENDING"), 403);
            patch("/v1/orders/" + id + "/approval", l2a.token(), body("decision", "CONFIRMED"), 409);
            JsonNode approved = data(patch("/v1/orders/" + id + "/approval", l2a.token(), body("decision", "DEALER_LEVEL_PENDING"), 200));
            assertThat(approved.get("orderStatus").asText()).isEqualTo("DEALER_LEVEL_PENDING");

            patch("/v1/orders/" + id + "/status", l1.token(), body("status", "CONFIRMED"), 403);
            moveOrder(id, l2b, "CONFIRMED");
            assertThat(notificationTitles(l2a)).contains("Order Confirmed");
            moveOrder(id, l1, "SHIPPED");

            JsonNode noDate = patch("/v1/orders/" + id + "/status", l1.token(), body("status", "DELIVERED"), 400);
            assertThat(noDate.get("errors").get(0).get("field").asText()).isEqualTo("actualDelivery");
            patch("/v1/orders/" + id + "/status", l1.token(),
                    body("status", "DELIVERED", "actualDelivery", LocalDate.now().plusDays(1).toString()), 400);
            JsonNode delivered = moveOrder(id, l1, "DELIVERED");
            assertThat(delivered.get("actualDelivery").asText()).isEqualTo(LocalDate.now().toString());

            JsonNode locked = put("/v1/orders/" + id, l2a.token(), body("orderQuantity", 5), 409);
            assertThat(locked.get("errorCode").asText()).isEqualTo("ORDER_NOT_EDITABLE");
            assertThat(historyCount(id)).isEqualTo(6);

            assertThat(data(get("/v1/orders/status/DELIVERED", l1.token(), 200)).findValues("id").stream().map(JsonNode::asLong)).contains(id);
            assertThat(data(get("/v1/orders/dealer/" + dealer, l1.token(), 200))).hasSize(1);
            assertThat(data(get("/v1/orders", l1.token(), 200)).findValues("id").stream().map(JsonNode::asLong)).contains(id);
            // Several statuses in one call (the Approvals hub's queues), with nothing from other statuses
            long quotation = createOrder(dealer, item, "1").get("id").asLong();
            JsonNode waiting = data(get("/v1/orders?status=QUOTATION&status=DELIVERED", l1.token(), 200));
            assertThat(waiting.findValues("id").stream().map(JsonNode::asLong)).contains(id, quotation);
            assertThat(waiting.findValuesAsText("orderStatus")).containsOnly("QUOTATION", "DELIVERED");
            assertThat(data(get("/v1/orders?status=PENDING", l1.token(), 200)).findValues("id").stream().map(JsonNode::asLong))
                    .doesNotContain(id, quotation);
            get("/v1/orders?status=NOT_A_STATUS", l1.token(), 400);
            get("/v1/orders/dealer/999999", l1.token(), 404);
            get("/v1/orders/999999", l1.token(), 404);
        }

        @Test
        void cancellationsAndRejectionsNeedAReason() throws Exception {
            long dealer = createDealer();
            long item = createItem();
            long quotation = createOrder(dealer, item, "1").get("id").asLong();
            JsonNode noReason = patch("/v1/orders/" + quotation + "/status", l1.token(), body("status", "CANCELLED"), 400);
            assertThat(noReason.get("errors").get(0).get("field").asText()).isEqualTo("reason");
            JsonNode cancelled = data(patch("/v1/orders/" + quotation + "/status", l1.token(),
                    body("status", "CANCELLED", "reason", "Dealer withdrew"), 200));
            assertThat(cancelled.get("orderStatus").asText()).isEqualTo("CANCELLED");
            patch("/v1/orders/" + quotation + "/status", l1.token(), body("status", "PENDING"), 409);

            long pending = createOrder(dealer, item, "1").get("id").asLong();
            moveOrder(pending, l1, "PENDING");
            JsonNode rejectNoReason = patch("/v1/orders/" + pending + "/approval", l2a.token(), body("decision", "CANCELLED"), 400);
            assertThat(rejectNoReason.get("errors").get(0).get("field").asText()).isEqualTo("rejectionReason");
            JsonNode rejected = data(patch("/v1/orders/" + pending + "/approval", l2a.token(),
                    body("decision", "CANCELLED", "rejectionReason", "Too expensive"), 200));
            assertThat(rejected.get("orderStatus").asText()).isEqualTo("CANCELLED");
            assertThat(notificationTitles(l1)).contains("Order Cancelled");
        }

        private int historyCount(long orderId) {
            return jdbc.queryForObject("SELECT count(*) FROM order_status_history WHERE order_id = ?", Integer.class, orderId);
        }
    }

    @Nested
    @DisplayName("Stock")
    class Stocks {

        @Test
        void stockIsReceivedOnceFromADeliveredOrderAndApprovedByAdminL2() throws Exception {
            long dealer = createDealer();
            long item = createItem();
            JsonNode quotation = createOrder(dealer, item, "10");
            JsonNode notDelivered = post("/v1/stocks", l1.token(), body("orderNumber", quotation.get("orderNumber").asText(),
                    "unitsPassedTest", 1, "defectedUnits", 0, "hasTested", true, "dateOfArrival", LocalDate.now().toString()), 409);
            assertThat(notDelivered.get("errorCode").asText()).isEqualTo("ORDER_NOT_DELIVERED");

            JsonNode order = deliveredOrder(dealer, item, "10");
            String orderNumber = order.get("orderNumber").asText();
            JsonNode stock = createStock(orderNumber, "8", "1");
            long id = stock.get("id").asLong();
            assertThat(stock.get("approvalStatus").asText()).isEqualTo("PENDING");
            assertThat(stock.get("availableUnits").decimalValue()).isEqualByComparingTo("8");
            assertThat(stock.get("stockIdentityNumber").asText()).matches("^STK-\\d+-[0-9A-F]{5}$");
            assertThat(stock.get("batchNumber").asText()).matches("^BAT-\\d{8}-" + order.get("id").asLong() + "-[0-9A-F]{5}$");
            assertThat(stock.get("totalOrderQuantity").decimalValue()).isEqualByComparingTo("10");
            assertThat(notificationTitles(l2a)).contains("Stock Approval Required");

            JsonNode again = post("/v1/stocks", l1.token(), body("orderNumber", orderNumber, "unitsPassedTest", 1,
                    "defectedUnits", 0, "hasTested", true, "dateOfArrival", LocalDate.now().toString()), 409);
            assertThat(again.get("errorCode").asText()).isEqualTo("ORDER_ALREADY_HAS_STOCK");

            JsonNode notApproved = post("/v1/stocks/" + id + "/add-quantity", l1.token(), body("quantity", 1, "reason", "Recount"), 400);
            assertThat(notApproved.get("message").asText()).isEqualTo("Stock operations are restricted to approved stock entries.");

            post("/v1/stocks/" + id + "/approval", l1.token(), body("decision", "APPROVED"), 403);
            post("/v1/stocks/" + id + "/approval", l2a.token(), body("decision", "PENDING"), 400);
            JsonNode approved = data(post("/v1/stocks/" + id + "/approval", l2a.token(), body("decision", "APPROVED"), 200));
            assertThat(approved.get("approvalStatus").asText()).isEqualTo("APPROVED");
            assertThat(approved.get("approvedBy").asLong()).isEqualTo(l2a.userId());
            assertThat(notificationTitles(l1)).contains("Stock Approved");
            JsonNode twice = post("/v1/stocks/" + id + "/approval", l2a.token(), body("decision", "APPROVED"), 400);
            assertThat(twice.get("message").asText()).isEqualTo("Stock request processed already with status: APPROVED");

            JsonNode returned = patch("/v1/orders/" + order.get("id").asLong() + "/status", l1.token(),
                    body("status", "RETURN_REQUESTED", "reason", "Damaged"), 409);
            assertThat(returned.get("errorCode").asText()).isEqualTo("ORDER_STOCKED");
        }

        @Test
        void quantitiesStayWithinTheOrderAndAvailability() throws Exception {
            long dealer = createDealer();
            long item = createItem();
            JsonNode small = deliveredOrder(dealer, item, "5");
            JsonNode tooMuch = post("/v1/stocks", l1.token(), body("orderNumber", small.get("orderNumber").asText(),
                    "unitsPassedTest", 5, "defectedUnits", 1, "hasTested", true, "dateOfArrival", LocalDate.now().toString()), 400);
            assertThat(tooMuch.get("errors").get(0).get("field").asText()).isEqualTo("unitsPassedTest");

            JsonNode order = deliveredOrder(dealer, item, "10");
            long id = createStock(order.get("orderNumber").asText(), "8", "1").get("id").asLong();
            post("/v1/stocks/" + id + "/approval", l2a.token(), body("decision", "APPROVED"), 200);

            // Added units are recorded but held back from use until Admin L2 approves the change
            JsonNode added = data(post("/v1/stocks/" + id + "/add-quantity", l1.token(),
                    body("quantity", 1, "reason", "Recount", "barcodes", barcodes(1)), 200));
            assertThat(added.get("unitsPassedTest").decimalValue()).isEqualByComparingTo("9");
            assertThat(added.get("availableUnits").decimalValue()).isEqualByComparingTo("8");
            assertThat(added.get("heldUnits").decimalValue()).isEqualByComparingTo("1");
            assertThat(added.get("changeAwaitingApproval").asBoolean()).isTrue();
            assertThat(notificationTitles(l2a)).contains("Stock Change Approval Required");
            assertThat(data(get("/v1/stocks?status=PENDING", l2a.token(), 200)).findValues("id").stream().map(JsonNode::asLong)).contains(id);
            post("/v1/stocks/" + id + "/add-quantity", l1.token(), body("quantity", 1, "reason", "Recount", "barcodes", barcodes(1)), 400);
            JsonNode approvedChange = data(post("/v1/stocks/" + id + "/approval", l2a.token(), body("decision", "APPROVED"), 200));
            assertThat(approvedChange.get("availableUnits").decimalValue()).isEqualByComparingTo("9");
            assertThat(approvedChange.get("changeAwaitingApproval").asBoolean()).isFalse();
            assertThat(data(get("/v1/stocks?status=PENDING", l2a.token(), 200)).findValues("id").stream().map(JsonNode::asLong)).doesNotContain(id);

            JsonNode insufficient = post("/v1/stocks/" + id + "/reduce-quantity", l1.token(), body("quantity", 100, "reason", "Loss"), 400);
            assertThat(insufficient.get("message").asText()).startsWith("Insufficient stock available");
            JsonNode reduced = data(post("/v1/stocks/" + id + "/reduce-quantity", l1.token(),
                    body("quantity", 2, "reason", "Loss", "barcodes", stockBarcodes(id, "IN_STOCK").subList(0, 2)), 200));
            assertThat(reduced.get("availableUnits").decimalValue()).isEqualByComparingTo("7");
            assertThat(availableUnits(item)).isEqualByComparingTo("7");
        }

        @Test
        void updatesKeepTheOrdersDealerAndItemAndRequireAnAdmin() throws Exception {
            long dealer = createDealer();
            long item = createItem();
            JsonNode order = deliveredOrder(dealer, item, "10");
            long id = createStock(order.get("orderNumber").asText(), "9", "1").get("id").asLong();
            post("/v1/stocks/" + id + "/approval", l2a.token(), body("decision", "APPROVED"), 200);
            post("/v1/stocks/" + id + "/reduce-quantity", l1.token(),
                    body("quantity", 2, "reason", "Used", "barcodes", stockBarcodes(id, "IN_STOCK").subList(0, 2)), 200);

            Map<String, Object> update = body("batchNumber", "BATCH-X1", "dealerId", dealer, "itemId", item,
                    "unitsPassedTest", 9, "defectedUnits", 1, "hasTested", true, "dateOfArrival", LocalDate.now().toString());
            put("/v1/stocks/" + id, inspectorToken(), update, 403);

            update.put("dealerId", createDealer());
            JsonNode wrongDealer = put("/v1/stocks/" + id, l1.token(), update, 400);
            assertThat(wrongDealer.get("errors").get(0).get("field").asText()).isEqualTo("dealerId");

            update.put("dealerId", dealer);
            update.put("unitsPassedTest", 8);
            update.put("removedBarcodes", stockBarcodes(id, "IN_STOCK").subList(0, 1));
            JsonNode response = put("/v1/stocks/" + id, l1.token(), update, 200);
            assertThat(response.get("message").asText()).isEqualTo("Stock updated successfully");
            assertThat(data(response).get("batchNumber").asText()).isEqualTo("BATCH-X1");
            assertThat(data(response).get("availableUnits").decimalValue()).isEqualByComparingTo("6");

            update.put("unitsPassedTest", 1);
            put("/v1/stocks/" + id, l1.token(), update, 400);
        }

        @Test
        void rejectedStockHoldsNothingAndListsFilterByStatus() throws Exception {
            JsonNode order = deliveredOrder(createDealer(), createItem(), "4");
            long id = createStock(order.get("orderNumber").asText(), "4", "0").get("id").asLong();
            JsonNode rejected = data(post("/v1/stocks/" + id + "/approval", l2a.token(),
                    body("decision", "REJECTED", "rejectionReason", "Failed QA"), 200));
            assertThat(rejected.get("approvalStatus").asText()).isEqualTo("REJECTED");
            assertThat(rejected.get("availableUnits").decimalValue()).isEqualByComparingTo("0");
            assertThat(rejected.get("rejectionReason").asText()).isEqualTo("Failed QA");
            assertThat(notificationTitles(l1)).contains("Stock Cancelled");

            assertThat(data(get("/v1/stocks?status=REJECTED", l1.token(), 200)).findValues("id").stream().map(JsonNode::asLong)).contains(id);
            assertThat(data(get("/v1/stocks?status=APPROVED", l1.token(), 200)).findValues("id").stream().map(JsonNode::asLong)).doesNotContain(id);
            assertThat(data(get("/v1/stocks", l1.token(), 200)).findValues("id").stream().map(JsonNode::asLong)).contains(id);
            assertThat(data(get("/v1/stocks/" + id, l1.token(), 200)).get("id").asLong()).isEqualTo(id);
            get("/v1/stocks/999999", l1.token(), 404);
        }

        @Test
        void availabilityCountsOnlyApprovedStock() throws Exception {
            long item = itemWithApprovedStock("6");
            long emptyItem = createItem();

            JsonNode single = data(get("/v1/stocks/items/" + item + "/availability", l1.token(), 200));
            assertThat(single.get("totalAvailableUnits").decimalValue()).isEqualByComparingTo("6");
            assertThat(single.get("stocks")).hasSize(1);

            JsonNode bulk = data(get("/v1/stocks/items/availability?itemIds=" + item + "," + emptyItem, l1.token(), 200));
            assertThat(bulk).hasSize(2);
            JsonNode inStock = data(get("/v1/stocks/items/availability?inStockOnly=true&itemIds=" + item + "," + emptyItem, l1.token(), 200));
            assertThat(inStock).hasSize(1);
            assertThat(inStock.get(0).get("itemId").asLong()).isEqualTo(item);
        }

        @Test
        void purchasesAreBookedOnlyWhenAdminL2Approves() throws Exception {
            // 10 PCS at 100.00 plus 50.00 shipment
            JsonNode order = deliveredOrder(createDealer(), createItem(), "10");
            String orderNumber = order.get("orderNumber").asText();
            assertThat(bookedAmounts(orderNumber)).isEmpty();

            // Recorded, not approved: nothing is bought yet
            long id = createStock(orderNumber, "8", "1").get("id").asLong();
            assertThat(bookedAmounts(orderNumber)).isEmpty();

            JsonNode approved = data(post("/v1/stocks/" + id + "/approval", l2a.token(), body("decision", "APPROVED"), 200));
            assertThat(bookedAmounts(orderNumber)).containsExactly(new BigDecimal("850.00"));
            assertThat(approved.get("bookedPurchaseValue").decimalValue()).isEqualByComparingTo("850.00");
            assertThat(approved.get("approvedUnitsPassed").decimalValue()).isEqualByComparingTo("8");

            // A change waits for L2: nothing moves until it is approved
            post("/v1/stocks/" + id + "/add-quantity", l1.token(), body("quantity", 1, "reason", "Re-tested", "barcodes", barcodes(1)), 200);
            assertThat(bookedAmounts(orderNumber)).containsExactly(new BigDecimal("850.00"));
            post("/v1/stocks/" + id + "/approval", l2a.token(), body("decision", "APPROVED"), 200);
            assertThat(bookedAmounts(orderNumber)).containsExactly(new BigDecimal("850.00"), new BigDecimal("100.00"));

            // Consuming stock is not a purchase change
            post("/v1/stocks/" + id + "/reduce-quantity", l1.token(),
                    body("quantity", 2, "reason", "Used", "barcodes", stockBarcodes(id, "IN_STOCK").subList(0, 2)), 200);
            Map<String, Object> correction = body("batchNumber", "BATCH-F1", "dealerId", order.get("dealerId").asLong(),
                    "itemId", order.get("itemId").asLong(), "unitsPassedTest", 7, "defectedUnits", 3, "hasTested", true,
                    "dateOfArrival", LocalDate.now().toString(), "removedBarcodes", stockBarcodes(id, "IN_STOCK").subList(0, 2));
            JsonNode corrected = data(put("/v1/stocks/" + id, l1.token(), correction, 200));
            assertThat(corrected.get("availableUnits").decimalValue()).isEqualByComparingTo("5");
            assertThat(bookedAmounts(orderNumber)).hasSize(2);
            post("/v1/stocks/" + id + "/approval", l2a.token(), body("decision", "APPROVED"), 200);
            assertThat(bookedAmounts(orderNumber)).containsExactly(
                    new BigDecimal("850.00"), new BigDecimal("100.00"), new BigDecimal("-200.00"));

            // A rejected change puts the approved counts back (units taken off come back into use) and books nothing
            correction.put("unitsPassedTest", 6);
            correction.put("removedBarcodes", stockBarcodes(id, "IN_STOCK").subList(0, 1));
            assertThat(data(put("/v1/stocks/" + id, l1.token(), correction, 200)).get("availableUnits").decimalValue())
                    .isEqualByComparingTo("4");
            JsonNode restored = data(post("/v1/stocks/" + id + "/approval", l2a.token(),
                    body("decision", "REJECTED", "rejectionReason", "Not re-tested"), 200));
            assertThat(restored.get("approvalStatus").asText()).isEqualTo("APPROVED");
            assertThat(restored.get("unitsPassedTest").decimalValue()).isEqualByComparingTo("7");
            assertThat(restored.get("availableUnits").decimalValue()).isEqualByComparingTo("5");
            assertThat(restored.get("changeAwaitingApproval").asBoolean()).isFalse();
            assertThat(bookedAmounts(orderNumber)).hasSize(3);

            List<JsonNode> events = purchaseEvents(orderNumber);
            assertThat(events).extracting(event -> event.get("reason").asText())
                    .containsExactly("STOCK_APPROVED", "STOCK_CHANGE_APPROVED", "STOCK_CHANGE_APPROVED");
            assertThat(events.getFirst().get("postingKey").asText()).startsWith("PROCUREMENT_PURCHASE:");
            assertThat(events.getFirst().get("quantity").decimalValue()).isEqualByComparingTo("8");
            // No GST breakup on the quotation: no input tax
            assertThat(events.getFirst().get("cgst").decimalValue()).isEqualByComparingTo("0");
            assertThat(events.getFirst().get("supplyType").isNull()).isTrue();

            // A rejected entry buys nothing, and never did
            JsonNode other = deliveredOrder(createDealer(), createItem(), "4");
            long rejected = createStock(other.get("orderNumber").asText(), "4", "0").get("id").asLong();
            post("/v1/stocks/" + rejected + "/approval", l2a.token(), body("decision", "REJECTED", "rejectionReason", "Failed QA"), 200);
            assertThat(bookedAmounts(other.get("orderNumber").asText())).isEmpty();
        }

        @Test
        void purchasesCarryTheQuotedGstOnTheApprovedValue() throws Exception {
            // 10 PCS at 100.00 + 50.00 shipment = 1,050.00 taxable; CGST + SGST at 18 %: 94.50 each
            long dealer = createDealer();
            long item = createItem();
            Map<String, Object> request = orderBody(dealer, item, "10");
            request.put("taxBreakup", Map.of("supplyType", "INTRA_STATE", "gstRate", 18, "hsnSac", "8541"));
            long orderId = data(post("/v1/orders", l1.token(), request, 201)).get("id").asLong();
            moveOrder(orderId, l1, "PENDING");
            patch("/v1/orders/" + orderId + "/approval", l2a.token(), body("decision", "DEALER_LEVEL_PENDING"), 200);
            moveOrder(orderId, l2a, "CONFIRMED");
            moveOrder(orderId, l1, "SHIPPED");
            String orderNumber = moveOrder(orderId, l1, "DELIVERED").get("orderNumber").asText();

            long id = createStock(orderNumber, "10", "0").get("id").asLong();
            post("/v1/stocks/" + id + "/approval", l2a.token(), body("decision", "APPROVED"), 200);
            JsonNode full = purchaseEvents(orderNumber).getFirst();
            assertThat(full.get("amount").decimalValue()).isEqualByComparingTo("1050.00");
            assertThat(full.get("cgst").decimalValue()).isEqualByComparingTo("94.50");
            assertThat(full.get("sgst").decimalValue()).isEqualByComparingTo("94.50");
            assertThat(full.get("igst").decimalValue()).isEqualByComparingTo("0");
            assertThat(full.get("gstRate").decimalValue()).isEqualByComparingTo("18");
            assertThat(full.get("supplyType").asText()).isEqualTo("INTRA_STATE");
            assertThat(full.get("hsnSac").asText()).isEqualTo("8541");
            assertThat(full.get("dealerState").asText()).isEqualTo("Karnataka");
            // The dealer's invoice uploaded for the delivery: the document the input tax is claimed on
            assertThat(full.get("dealerInvoiceUploadedAt").isNull()).isFalse();

            // Two units taken off and approved: 850.00 taxable, 76.50 each, so 18.00 each comes back
            put("/v1/stocks/" + id, l1.token(), body("batchNumber", "BATCH-G1", "dealerId", dealer,
                    "itemId", item, "unitsPassedTest", 8, "defectedUnits", 2, "hasTested", true,
                    "dateOfArrival", LocalDate.now().toString(), "removedBarcodes", stockBarcodes(id, "IN_STOCK").subList(0, 2)), 200);
            post("/v1/stocks/" + id + "/approval", l2a.token(), body("decision", "APPROVED"), 200);
            JsonNode less = purchaseEvents(orderNumber).getLast();
            assertThat(less.get("amount").decimalValue()).isEqualByComparingTo("-200.00");
            assertThat(less.get("cgst").decimalValue()).isEqualByComparingTo("-18.00");
            assertThat(less.get("sgst").decimalValue()).isEqualByComparingTo("-18.00");
        }

        private List<JsonNode> purchaseEvents(String orderNumber) throws Exception {
            JsonNode feed = data(perform(MockMvcRequestBuilders.get("/v1/internal/finance/purchase-events")
                    .param("limit", "500").header(INTERNAL_KEY_HEADER, INTERNAL_API_KEY), 200));
            return StreamSupport.stream(feed.get("events").spliterator(), false)
                    .filter(event -> orderNumber.equals(event.get("orderNumber").asText())).toList();
        }

        private List<BigDecimal> bookedAmounts(String orderNumber) {
            return jdbc.queryForList("SELECT p.amount FROM purchase_postings p JOIN orders o ON o.id = p.order_id "
                    + "WHERE o.order_number = ? ORDER BY p.id", BigDecimal.class, orderNumber);
        }
    }

    @Nested
    @DisplayName("Products")
    class Products {

        @Test
        void productsConsumeStockAndReturnItWhenDeleted() throws Exception {
            long item = itemWithStockWithoutBarcodes("10");

            JsonNode product = data(post("/v1/admin/products", l1.token(), body("itemId", item, "quantity", 3), 201));
            long id = product.get("id").asLong();
            assertThat(product.get("productId").asText()).matches("^PRD-\\d+-[0-9A-F]{4}$");
            assertThat(product.get("stockAllocations").get(0).get("quantity").decimalValue()).isEqualByComparingTo("3");
            assertThat(product.get("itemWarranties")).hasSize(1);
            assertThat(availableUnits(item)).isEqualByComparingTo("7");

            String custom = uniq("CUSTOM-");
            post("/v1/admin/products", l1.token(), body("productId", custom, "itemId", item, "quantity", 1), 201);
            JsonNode duplicate = post("/v1/admin/products", l1.token(), body("productId", custom, "itemId", item, "quantity", 1), 409);
            assertThat(duplicate.get("message").asText()).isEqualTo("Product ID already exists: " + custom);

            JsonNode insufficient = post("/v1/admin/products", l1.token(), body("itemId", item, "quantity", 100), 409);
            assertThat(insufficient.get("errorCode").asText()).isEqualTo("INSUFFICIENT_STOCK");
            assertThat(insufficient.get("message").asText()).contains("does not have enough stock");

            assertThat(data(get("/v1/admin/products/" + id, l1.token(), 200)).get("id").asLong()).isEqualTo(id);
            assertThat(data(get("/v1/admin/products/product-id/" + custom, l1.token(), 200)).get("productId").asText()).isEqualTo(custom);
            assertThat(data(get("/v1/admin/products/item/" + item, l1.token(), 200))).hasSize(2);
            assertThat(data(put("/v1/admin/products/" + id, l1.token(), body("isActive", false), 200)).get("isActive").asBoolean()).isFalse();
            assertThat(data(get("/v1/admin/products?isActive=false&size=1000", l1.token(), 200)).get("content")
                    .findValues("id").stream().map(JsonNode::asLong)).contains(id);

            JsonNode deleted = delete("/v1/admin/products/" + id, l1.token(), 200);
            assertThat(deleted.get("message").asText()).isEqualTo("Product deleted and its stock returned successfully");
            assertThat(availableUnits(item)).isEqualByComparingTo("9");
            get("/v1/admin/products/" + id, l1.token(), 404);
        }

        @Test
        void inactiveItemsCannotBeUsed() throws Exception {
            long item = createItem();
            delete("/v1/admin/items/" + item, l1.token(), 200);
            JsonNode response = post("/v1/admin/products", l1.token(), body("itemId", item, "quantity", 1), 400);
            assertThat(response.get("message").asText()).isEqualTo("Cannot link a product to an inactive item: " + item);
        }
    }

    @Nested
    @DisplayName("Internal item stock movements")
    class Movements {

        private JsonNode internal(HttpMethod method, String path, Object body, int status) throws Exception {
            var builder = MockMvcRequestBuilders.request(method, path)
                    .header(INTERNAL_KEY_HEADER, INTERNAL_API_KEY);
            if (body != null) {
                builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
            }
            return perform(builder, status);
        }

        private Map<String, Object> movement(String movementId, String reference, long itemId, String quantity) {
            return body("movementId", movementId, "source", "ECOMMERCE_PRODUCT", "reference", reference,
                    "lines", List.of(body("itemId", itemId, "quantity", new BigDecimal(quantity))), "performedBy", "ecommerce");
        }

        @Test
        void movementsAreIdempotentReversibleAndCappedByWhatAReferenceHolds() throws Exception {
            long item = itemWithStockWithoutBarcodes("10");
            String reference = "product:" + next();
            String consume = randomUuid();

            JsonNode applied = data(internal(HttpMethod.POST, "/v1/internal/item-stock/movements", movement(consume, reference, item, "4"), 200));
            assertThat(applied.get("status").asText()).isEqualTo("APPLIED");
            assertThat(applied.get("lines").get(0).get("quantity").decimalValue()).isEqualByComparingTo("4");
            assertThat(availableUnits(item)).isEqualByComparingTo("6");

            internal(HttpMethod.POST, "/v1/internal/item-stock/movements", movement(consume, reference, item, "4"), 200);
            assertThat(availableUnits(item)).isEqualByComparingTo("6");
            JsonNode reused = internal(HttpMethod.POST, "/v1/internal/item-stock/movements", movement(consume, "product:other", item, "4"), 409);
            assertThat(reused.get("errorCode").asText()).isEqualTo("MOVEMENT_ID_CONFLICT");

            String giveBack = randomUuid();
            JsonNode returned = data(internal(HttpMethod.POST, "/v1/internal/item-stock/movements", movement(giveBack, reference, item, "-1"), 200));
            assertThat(returned.get("lines").get(0).get("quantity").decimalValue()).isEqualByComparingTo("-1");
            assertThat(availableUnits(item)).isEqualByComparingTo("7");

            JsonNode blocked = internal(HttpMethod.POST, "/v1/internal/item-stock/movements/" + consume + "/reverse", null, 409);
            assertThat(blocked.get("errorCode").asText()).isEqualTo("REVERSAL_NOT_POSSIBLE");
            internal(HttpMethod.POST, "/v1/internal/item-stock/movements/" + giveBack + "/reverse", null, 200);
            assertThat(availableUnits(item)).isEqualByComparingTo("6");
            JsonNode reversed = data(internal(HttpMethod.POST, "/v1/internal/item-stock/movements/" + consume + "/reverse", null, 200));
            assertThat(reversed.get("status").asText()).isEqualTo("REVERSED");
            assertThat(availableUnits(item)).isEqualByComparingTo("10");
            internal(HttpMethod.POST, "/v1/internal/item-stock/movements/" + consume + "/reverse", null, 200);
            assertThat(availableUnits(item)).isEqualByComparingTo("10");

            assertThat(data(internal(HttpMethod.GET, "/v1/internal/item-stock/movements/" + consume, null, 200))
                    .get("reference").asText()).isEqualTo(reference);
        }

        @Test
        void invalidMovementsChangeNothing() throws Exception {
            long item = itemWithApprovedStock("2");
            String reference = "product:" + next();
            JsonNode shortage = internal(HttpMethod.POST, "/v1/internal/item-stock/movements", movement(randomUuid(), reference, item, "1000"), 409);
            assertThat(shortage.get("errorCode").asText()).isEqualTo("INSUFFICIENT_ITEM_STOCK");
            JsonNode zero = internal(HttpMethod.POST, "/v1/internal/item-stock/movements", movement(randomUuid(), reference, item, "0"), 400);
            assertThat(zero.get("errorCode").asText()).isEqualTo("INVALID_MOVEMENT_LINES");
            JsonNode unknown = internal(HttpMethod.POST, "/v1/internal/item-stock/movements", movement(randomUuid(), reference, 999999, "1"), 400);
            assertThat(unknown.get("errorCode").asText()).isEqualTo("ITEM_NOT_FOUND");
            assertThat(availableUnits(item)).isEqualByComparingTo("2");
        }
    }

    @Nested
    @DisplayName("Product approvals")
    class ProductApprovals {

        private Map<String, Object> submission(long productId, int stockQuantity, List<Map<String, Object>> components) {
            return body("ecommerceProductId", productId, "productTitle", "Laptop " + productId,
                    "snapshot", body("title", "Laptop " + productId, "price", new BigDecimal("999.00"),
                            "stockQuantity", stockQuantity, "components", components));
        }

        @Test
        void submitRejectResubmitAndApprove() throws Exception {
            JsonNode stock = approvedStock("10");
            long item = stock.get("itemId").asLong();
            List<String> pieces = stockBarcodes(stock.get("id").asLong(), "IN_STOCK");
            long productId = next();

            JsonNode created = data(post("/v1/product-approvals", l1.token(), submission(productId, 0, List.of()), 201));
            assertThat(created.get("requestType").asText()).isEqualTo("CREATE");
            assertThat(created.get("status").asText()).isEqualTo("PENDING");
            assertThat(created.get("requestedByUsername").asText()).isEqualTo(l1.username());
            assertThat(notificationTitles(l2a)).contains("Product awaiting approval");
            JsonNode duplicate = post("/v1/product-approvals", l1.token(), submission(productId, 0, List.of()), 409);
            assertThat(duplicate.get("errorCode").asText()).isEqualTo("PRODUCT_APPROVAL_PENDING");

            assertThat(data(get("/v1/product-approvals", l2a.token(), 200)).findValues("id").stream().map(JsonNode::asLong))
                    .contains(created.get("id").asLong());
            patch("/v1/product-approvals/" + created.get("id").asLong() + "/decision", l2a.token(), body("decision", "REJECTED"), 400);
            JsonNode rejected = data(patch("/v1/product-approvals/" + created.get("id").asLong() + "/decision", l2a.token(),
                    body("decision", "REJECTED", "reason", "Blurry photos"), 200));
            assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
            assertThat(notificationTitles(l1)).contains("Product rejected");

            List<Map<String, Object>> components = List.of(body("itemId", item, "itemName", "Board", "quantity", 1));
            // A resubmission rebuilds the units in stock: each needs its barcode and its pieces' barcodes
            JsonNode unscanned = post("/v1/product-approvals", l1.token(), submission(productId, 2, components), 400);
            assertThat(unscanned.get("errors").findValuesAsText("field")).contains("units");
            List<String> units = barcodes(2);
            Map<String, Object> resubmission = submission(productId, 2, components);
            resubmission.put("units", List.of(
                    body("barcode", units.get(0), "parts", List.of(body("itemId", item, "barcodes", List.of(pieces.get(0))))),
                    body("barcode", units.get(1), "parts", List.of(body("itemId", item, "barcodes", List.of(pieces.get(1)))))));
            JsonNode resubmitted = data(post("/v1/product-approvals", l1.token(), resubmission, 201));
            assertThat(resubmitted.get("requestType").asText()).isEqualTo("RESUBMISSION");
            assertThat(availableUnits(item)).isEqualByComparingTo("8");
            assertThat(barcodeStatus(units.get(0))).isEqualTo("PENDING");
            assertThat(barcodeStatus(pieces.get(0))).isEqualTo("FITTED");

            JsonNode cancelled = data(patch("/v1/product-approvals/" + resubmitted.get("id").asLong() + "/decision", l2a.token(),
                    body("decision", "CANCELLED", "reason", "Discontinued"), 200));
            assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
            assertThat(availableUnits(item)).isEqualByComparingTo("10");
            // The units' registrations are withdrawn and their pieces are back in stock
            assertThat(barcodeStatus(units.get(0))).isEqualTo("VOID");
            assertThat(barcodeStatus(pieces.get(0))).isEqualTo("IN_STOCK");
            JsonNode afterCancel = post("/v1/product-approvals", l1.token(), submission(productId, 0, List.of()), 409);
            assertThat(afterCancel.get("errorCode").asText()).isEqualTo("PRODUCT_APPROVAL_CANCELLED");

            JsonNode history = data(get("/v1/product-approvals/product/" + productId, l1.token(), 200));
            assertThat(history).hasSize(2);
        }

        @Test
        void makerCheckerAndUpdateRequests() throws Exception {
            long productId = next();
            long requestId = data(post("/v1/product-approvals", l2a.token(), submission(productId, 0, List.of()), 201)).get("id").asLong();

            JsonNode self = patch("/v1/product-approvals/" + requestId + "/decision", l2a.token(), body("decision", "APPROVED"), 403);
            assertThat(self.get("errorCode").asText()).isEqualTo("PRODUCT_APPROVAL_SELF_DECISION");
            JsonNode approved = data(patch("/v1/product-approvals/" + requestId + "/decision", l2b.token(), body("decision", "APPROVED"), 200));
            assertThat(approved.get("decidedByUsername").asText()).isEqualTo(l2b.username());

            JsonNode recreate = post("/v1/product-approvals", l1.token(), submission(productId, 0, List.of()), 409);
            assertThat(recreate.get("errorCode").asText()).isEqualTo("PRODUCT_ALREADY_APPROVED");

            Map<String, Object> update = submission(productId, 0, List.of());
            update.put("requestType", "UPDATE");
            JsonNode missing = post("/v1/product-approvals", l1.token(), update, 400);
            assertThat(missing.get("errors").findValuesAsText("field")).contains("proposedChanges", "previousSnapshot", "changeSummary");

            update.put("proposedChanges", body("price", 899));
            update.put("previousSnapshot", ((Map<?, ?>) update.get("snapshot")));
            update.put("changeSummary", List.of(body("label", "Price", "oldValue", "999", "newValue", "899")));
            long updateId = data(post("/v1/product-approvals", l1.token(), update, 201)).get("id").asLong();
            patch("/v1/product-approvals/" + updateId + "/applied", l1.token(), null, 409);
            patch("/v1/product-approvals/" + updateId + "/decision", l2a.token(), body("decision", "CANCELLED", "reason", "x"), 400);
            patch("/v1/product-approvals/" + updateId + "/decision", l2a.token(), body("decision", "APPROVED"), 200);
            assertThat(notificationTitles(l1)).contains("Product changes approved");

            JsonNode applied = data(patch("/v1/product-approvals/" + updateId + "/applied", l1.token(), null, 200));
            assertThat(applied.get("appliedBy").asLong()).isEqualTo(l1.userId());
            String appliedAt = applied.get("appliedAt").asText();
            assertThat(data(patch("/v1/product-approvals/" + updateId + "/applied", l2a.token(), null, 200)).get("appliedAt").asText())
                    .isEqualTo(appliedAt);

            JsonNode latest = data(get("/v1/product-approvals/latest?productIds=" + productId, l1.token(), 200));
            assertThat(latest).hasSize(1);
            assertThat(latest.get(0).get("id").asLong()).isEqualTo(updateId);
            get("/v1/product-approvals/latest?productIds=abc", l1.token(), 400);
            patch("/v1/product-approvals/999999/decision", l2a.token(), body("decision", "APPROVED"), 404);
        }
    }

    @Nested
    @DisplayName("Documents")
    class Documents {

        private static final byte[] PDF = "%PDF-1.4 test document".getBytes(StandardCharsets.US_ASCII);

        private JsonNode upload(String fileName, byte[] content, int status) throws Exception {
            String meta = objectMapper.writeValueAsString(body("uploaderType", "ADMIN_L1", "ownerType", "EMPLOYEE",
                    "ownerId", l1.userId(), "referenceType", "ORDER", "referenceId", 1, "category", "INVOICE",
                    "purpose", "INVOICE", "type", "PDF", "stage", "GENERAL"));
            return perform(MockMvcRequestBuilders.multipart("/v1/documents/upload")
                    .file(new MockMultipartFile("file", fileName, "application/pdf", content))
                    .file(new MockMultipartFile("meta", "", MediaType.APPLICATION_JSON_VALUE, meta.getBytes(StandardCharsets.UTF_8)))
                    .header("Authorization", "Bearer " + l1.token()), status);
        }

        @Test
        void uploadApproveDownloadAndDelete() throws Exception {
            JsonNode uploaded = data(upload("invoice_2026.pdf", PDF, 200));
            long id = uploaded.get("id").asLong();
            assertThat(uploaded.get("status").asText()).isEqualTo("WAITING_FOR_APPROVAL");
            assertThat(uploaded.get("contentType").asText()).isEqualTo("application/pdf");
            assertThat(uploaded.get("downloadUrl").asText()).isEqualTo("/api/v1/documents/" + id + "/download");

            JsonNode invalid = upload("invoice.pdf", "plain text".getBytes(StandardCharsets.UTF_8), 400);
            assertThat(invalid.get("errorCode").asText()).isEqualTo("INVALID_FILE");

            assertThat(data(get("/v1/documents/" + id, l1.token(), 200)).get("originalFileName").asText()).isEqualTo("invoice_2026.pdf");
            MvcResult download = mockMvc.perform(MockMvcRequestBuilders.get("/v1/documents/" + id + "/download").header("Authorization", "Bearer " + l1.token()))
                    .andReturn();
            assertThat(download.getResponse().getStatus()).isEqualTo(200);
            assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(PDF);
            assertThat(download.getResponse().getContentType()).isEqualTo("application/pdf");
            assertThat(download.getResponse().getHeader("Content-Disposition")).contains("attachment");

            assertThat(data(get("/v1/documents?referenceType=ORDER&status=WAITING_FOR_APPROVAL", l1.token(), 200))
                    .findValues("id").stream().map(JsonNode::asLong)).contains(id);
            assertThat(data(get("/v1/documents/user/" + l1.userId(), l1.token(), 200)).findValues("id").stream().map(JsonNode::asLong)).contains(id);

            patch("/v1/documents/" + id + "/approval", l1.token(), body("decision", "ACTIVE"), 403);
            patch("/v1/documents/" + id + "/approval", l2a.token(), body("decision", "REJECTED"), 400);
            patch("/v1/documents/" + id + "/approval", l2a.token(), body("decision", "ARCHIVED"), 400);
            JsonNode approved = data(patch("/v1/documents/" + id + "/approval", l2a.token(), body("decision", "ACTIVE"), 200));
            assertThat(approved.get("status").asText()).isEqualTo("ACTIVE");
            assertThat(approved.get("uploadedByUserId").asLong()).isEqualTo(l1.userId());
            assertThat(notificationTitles(l1)).contains("Document Approved");

            delete("/v1/documents/" + id, l1.token(), 200);
            delete("/v1/documents/" + id, l1.token(), 404);
            get("/v1/documents/999999", l1.token(), 404);
        }
    }

    @Nested
    @DisplayName("Notifications")
    class Notifications {

        @Test
        void usersReadOnlyTheirOwnNotifications() throws Exception {
            String username = uniq("reader");
            long userId = insertUser(username, "ADMIN_L1", "WEB", false, true);
            String token = login(username, SEED_PASSWORD);
            jdbc.update("""
                    WITH n AS (INSERT INTO notification (title, message, broadcast, created_at)
                               VALUES ('Hello', 'First message', FALSE, now()), ('Again', 'Second message', FALSE, now())
                               RETURNING id)
                    INSERT INTO notification_recipient (notification_id, user_id, read, created_at)
                    SELECT id, ?, FALSE, now() FROM n
                    """, userId);

            JsonNode mine = data(get("/v1/notifications", token, 200));
            assertThat(mine).hasSize(2);
            assertThat(data(get("/v1/notifications/unread-count", token, 200)).get("unreadCount").asLong()).isEqualTo(2);

            long first = mine.get(0).get("id").asLong();
            patch("/v1/notifications/" + first + "/read", l1.token(), null, 404);
            assertThat(patch("/v1/notifications/" + first + "/read", token, null, 200).get("message").asText())
                    .isEqualTo("Notification Marked As Read");
            assertThat(data(get("/v1/notifications/unread-count", token, 200)).get("unreadCount").asLong()).isEqualTo(1);

            patch("/v1/notifications/read-all", token, null, 200);
            assertThat(data(get("/v1/notifications/unread-count", token, 200)).get("unreadCount").asLong()).isZero();
        }
    }

    // Ids of the page's rows only (rows also carry nested categories and warranties with ids of their own)
    private static List<Long> topLevelIds(JsonNode page) {
        List<Long> ids = new java.util.ArrayList<>();
        page.get("content").forEach(row -> ids.add(row.get("id").asLong()));
        return ids;
    }
}
