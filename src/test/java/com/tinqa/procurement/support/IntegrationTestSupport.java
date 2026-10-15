package com.tinqa.procurement.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Boots the whole application (security chain, Flyway migrations, Hibernate schema validation, Redis) against real
 * PostgreSQL and Redis containers, and drives it over HTTP. The containers are started once per JVM and shared by
 * every test, so the Spring context is built once.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    protected static final String DEFAULT_EMPLOYEE_PASSWORD = "TinQa";
    protected static final String SEED_PASSWORD = "Seed!Passw0rd#2026";
    protected static final String INTERNAL_API_KEY = "test-internal-api-key";
    protected static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    private static final AtomicInteger SEQUENCE = new AtomicInteger(1000);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    /** A seeded account and a fresh token for it. */
    public record Actor(Long userId, Long employeeId, String username, String email, String token) {
    }

    // Shared accounts, seeded once for the whole run (two Admin L2s so maker-checker rules can be exercised)
    protected static Actor l2a;
    protected static Actor l2b;
    protected static Actor l1;
    protected static Actor inspector;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @org.junit.jupiter.api.BeforeEach
    void seedSharedActors() throws Exception {
        if (l2a != null) {
            return;
        }
        // Users first, employees afterwards in reverse order, so employee ids never equal user ids
        long l2aUser = insertUser(uniq("l2a"), "ADMIN_L2", "WEB", false, true);
        long l2bUser = insertUser(uniq("l2b"), "ADMIN_L2", "WEB", false, true);
        long l1User = insertUser(uniq("l1"), "ADMIN_L1", "WEB", false, true);
        long inspectorUser = insertUser(nextLocalMobile(), "INSPECTOR", "MOBILE", false, true);
        long l1Employee = insertEmployee(l1User);
        long l2bEmployee = insertEmployee(l2bUser);
        long l2aEmployee = insertEmployee(l2aUser);

        l2a = actor(l2aUser, l2aEmployee);
        l2b = actor(l2bUser, l2bEmployee);
        l1 = actor(l1User, l1Employee);
        String inspectorName = username(inspectorUser);
        inspector = new Actor(inspectorUser, null, inspectorName, inspectorName + "@tinqa.test", null);
    }

    // A mobile (INSPECTOR) account signs in through the mobile endpoint without any token
    protected String inspectorToken() throws Exception {
        return post("/auth/mobile/login", null, Map.of("mobileNumber", inspector.username(), "password", SEED_PASSWORD), 200)
                .get("accessToken").asText();
    }

    // ------------------------------------------------------------------ seeding

    protected long insertUser(String username, String role, String authClient, boolean firstLogin, boolean enabled) {
        return jdbc.queryForObject("""
                        INSERT INTO users (username, email, password, role, auth_client, enabled, account_non_locked,
                                           is_first_login, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, TRUE, ?, now(), now()) RETURNING id
                        """, Long.class, username, username + "@tinqa.test", passwordEncoder.encode(SEED_PASSWORD),
                role, authClient, enabled, firstLogin);
    }

    // Seeded admins are Super Admins, so their admin level alone decides what they can do (as before designations)
    protected long insertEmployee(long userId) {
        return insertEmployee(userId, "SUPER_ADMIN");
    }

    protected long insertEmployee(long userId, String designation) {
        return jdbc.queryForObject("""
                        INSERT INTO employee (user_id, employee_code, first_name, last_name, display_name, date_of_birth,
                                              gender, designation, department, employment_type, joining_date, phone,
                                              status, created_at, updated_at)
                        VALUES (?, ?, 'Seed', ?, ?, DATE '1990-05-20', 'MALE', ?, 'Procurement', 'FULL_TIME',
                                DATE '2015-01-01', ?, 'ACTIVE', now(), now()) RETURNING id
                        """, Long.class, userId, "SEED-" + userId, letters(), "Seed " + userId, designation,
                "+91" + nextLocalMobile());
    }

    private Actor actor(long userId, long employeeId) throws Exception {
        String name = username(userId);
        return new Actor(userId, employeeId, name, name + "@tinqa.test", login(name, SEED_PASSWORD));
    }

    private String username(long userId) {
        return jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, userId);
    }

    // ------------------------------------------------------------------ auth

    protected String login(String username, String password) throws Exception {
        return post("/auth/admin/login", null, Map.of("username", username, "password", password), 200)
                .get("accessToken").asText();
    }

    // ------------------------------------------------------------------ HTTP

    protected JsonNode get(String path, String token, int expectedStatus) throws Exception {
        return call(HttpMethod.GET, path, token, null, expectedStatus);
    }

    protected JsonNode post(String path, String token, Object body, int expectedStatus) throws Exception {
        return call(HttpMethod.POST, path, token, body, expectedStatus);
    }

    protected JsonNode put(String path, String token, Object body, int expectedStatus) throws Exception {
        return call(HttpMethod.PUT, path, token, body, expectedStatus);
    }

    protected JsonNode patch(String path, String token, Object body, int expectedStatus) throws Exception {
        return call(HttpMethod.PATCH, path, token, body, expectedStatus);
    }

    protected JsonNode delete(String path, String token, int expectedStatus) throws Exception {
        return call(HttpMethod.DELETE, path, token, null, expectedStatus);
    }

    protected JsonNode call(HttpMethod method, String path, String token, Object body, int expectedStatus) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, path).accept(MediaType.APPLICATION_JSON);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON)
                    .content(body instanceof String raw ? raw : objectMapper.writeValueAsString(body));
        }
        return perform(builder, expectedStatus);
    }

    protected JsonNode perform(MockHttpServletRequestBuilder builder, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(builder).andReturn();
        String content = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus())
                .as("HTTP status of %s %s, body: %s", result.getRequest().getMethod(), result.getRequest().getRequestURI(), content)
                .isEqualTo(expectedStatus);
        return content.isEmpty() ? objectMapper.nullNode() : objectMapper.readTree(content);
    }

    protected static JsonNode data(JsonNode response) {
        return response.get("data");
    }

    protected static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    // ------------------------------------------------------------------ unique values

    protected static int next() {
        return SEQUENCE.incrementAndGet();
    }

    protected static String uniq(String prefix) {
        return prefix + next();
    }

    // Letters only, for fields such as person names
    protected static String letters() {
        StringBuilder value = new StringBuilder("Q");
        for (char digit : String.valueOf(next()).toCharArray()) {
            value.append((char) ('a' + (digit - '0')));
        }
        return value.toString();
    }

    // A valid 10-digit Indian mobile number that no other test uses
    protected static String nextLocalMobile() {
        return "9" + String.format("%09d", next());
    }

    // ------------------------------------------------------------------ fixtures

    protected long createCategory(String kind, Actor actor) throws Exception {
        String code = uniq("CAT");
        return data(post("/v1/admin/" + kind + "/categories", actor.token(),
                body("name", "Category " + code, "code", code), 201)).get("id").asLong();
    }

    protected Map<String, Object> dealerBody(List<Long> categoryIds) {
        return body("name", "Dealer " + next(), "email", uniq("dealer") + "@example.com",
                "phoneNumber", nextLocalMobile(), "street", "12 MG Road", "city", "Bengaluru",
                "state", "Karnataka", "country", "India", "pincode", "560001", "categoryIds", categoryIds);
    }

    protected long createDealer() throws Exception {
        long category = createCategory("dealers", l1);
        return data(post("/v1/admin/dealers", l1.token(), dealerBody(List.of(category)), 201)).get("id").asLong();
    }

    protected Map<String, Object> itemBody(long categoryId) {
        return body("categoryId", categoryId, "name", "Item " + next(), "sku", uniq("SKU-"), "unitOfMeasure", "PCS",
                "mrp", new BigDecimal("1500.00"), "countryOfOrigin", "India",
                "warranties", List.of(body("warrantyType", "MANUFACTURER", "title", "One Year",
                        "durationValue", 12, "durationUnit", "MONTHS")));
    }

    protected long createItem() throws Exception {
        long category = createCategory("items", l1);
        return data(post("/v1/admin/items", l1.token(), itemBody(category), 201)).get("id").asLong();
    }

    protected Map<String, Object> orderBody(long dealerId, long itemId, String quantity) {
        return body("dealerId", dealerId, "itemId", itemId, "orderQuantity", new BigDecimal(quantity),
                "unitType", "PCS", "unitPrice", new BigDecimal("100.00"), "shipmentPrice", new BigDecimal("50.00"),
                "orderDate", LocalDate.now().minusDays(5).toString());
    }

    protected JsonNode createOrder(long dealerId, long itemId, String quantity) throws Exception {
        return data(post("/v1/orders", l1.token(), orderBody(dealerId, itemId, quantity), 201));
    }

    protected JsonNode moveOrder(long orderId, Actor actor, String status) throws Exception {
        Map<String, Object> request = body("status", status);
        if (status.equals("DELIVERED")) {
            request.put("actualDelivery", LocalDate.now().toString());
        }
        return data(patch("/v1/orders/" + orderId + "/status", actor.token(), request, 200));
    }

    /** Takes a new order through the whole forward workflow to DELIVERED. */
    protected JsonNode deliveredOrder(long dealerId, long itemId, String quantity) throws Exception {
        long orderId = createOrder(dealerId, itemId, quantity).get("id").asLong();
        moveOrder(orderId, l1, "PENDING");
        patch("/v1/orders/" + orderId + "/approval", l2a.token(), body("decision", "DEALER_LEVEL_PENDING"), 200);
        moveOrder(orderId, l2a, "CONFIRMED");
        moveOrder(orderId, l1, "SHIPPED");
        return moveOrder(orderId, l1, "DELIVERED");
    }

    protected JsonNode createStock(String orderNumber, String passed, String defected) throws Exception {
        return data(post("/v1/stocks", l1.token(), body("orderNumber", orderNumber,
                "unitsPassedTest", new BigDecimal(passed), "defectedUnits", new BigDecimal(defected),
                "hasTested", true, "dateOfArrival", LocalDate.now().toString()), 201));
    }

    /** An item with one approved stock entry holding {@code units} consumable units. */
    protected long itemWithApprovedStock(String units) throws Exception {
        long itemId = createItem();
        long dealerId = createDealer();
        String orderNumber = deliveredOrder(dealerId, itemId, units).get("orderNumber").asText();
        long stockId = createStock(orderNumber, units, "0").get("id").asLong();
        post("/v1/stocks/" + stockId + "/approval", l2a.token(), body("decision", "APPROVED"), 200);
        return itemId;
    }

    protected BigDecimal availableUnits(long itemId) throws Exception {
        return data(get("/v1/stocks/items/" + itemId + "/availability", l1.token(), 200))
                .get("totalAvailableUnits").decimalValue();
    }

    protected List<String> notificationTitles(Actor actor) throws Exception {
        return data(get("/v1/notifications", actor.token(), 200)).findValuesAsText("title");
    }

    protected static String randomUuid() {
        return UUID.randomUUID().toString();
    }
}
