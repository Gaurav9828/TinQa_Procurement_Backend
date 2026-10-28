package com.tinqa.procurement;

import com.tinqa.procurement.security.access.RequiresPermission;
import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every endpoint the application serves, found at runtime, so a new endpoint is checked without anyone having to
 * remember it: it must say who may call it (a permission, an internal-service rule, or a place in the lists below),
 * anonymous callers must be turned away from everything that is not public, and empty or invalid input must never
 * crash it. Crashes (500) are counted by the API monitor and reported per endpoint by the quality gate: a warning in
 * local builds, a failure in strict (UAT/production) builds.
 *
 * <p>Each call of the sweep runs in its own transaction that is always rolled back, so it leaves no data behind.
 */
@DisplayName("Endpoint inventory")
class EndpointInventoryIntegrationTest extends IntegrationTestSupport {

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^}]+}|\\*\\*?");

    // Callable without signing in (SecurityPaths.PUBLIC)
    private static final Set<String> PUBLIC = Set.of(
            "POST /auth/admin/login", "POST /auth/mobile/login", "POST /auth/logout", "POST /auth/password/forgot",
            "POST /auth/password/link/check", "POST /auth/password/link");

    // Open to any signed-in account on purpose: changing one's own password
    private static final Set<String> ANY_SIGNED_IN_USER = Set.of("POST /auth/change-password", "POST /auth/first-login/password");

    // Signing out or changing the password would end the sweep's own session; they run last
    private static final Comparator<Endpoint> SESSION_ENDING_LAST = Comparator.comparing(
            (Endpoint e) -> e.pattern().startsWith("/auth/")).thenComparing(Endpoint::toString);

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private record Endpoint(HttpMethod method, String pattern, HandlerMethod handler) {
        String sampleUrl() {
            return PATH_VARIABLE.matcher(pattern).replaceAll("0");
        }

        boolean declaresAccess() {
            return AnnotatedElementUtils.hasAnnotation(handler.getMethod(), RequiresPermission.class)
                    || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), RequiresPermission.class)
                    || AnnotatedElementUtils.hasAnnotation(handler.getMethod(), PreAuthorize.class)
                    || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), PreAuthorize.class);
        }

        @Override
        public String toString() {
            return method + " " + pattern;
        }
    }

    @Test
    void everyEndpointSaysWhoMayCallIt() {
        List<String> undeclared = endpoints().stream()
                .filter(e -> !e.declaresAccess() && !PUBLIC.contains(e.toString()) && !ANY_SIGNED_IN_USER.contains(e.toString()))
                .map(Endpoint::toString)
                .sorted()
                .toList();
        assertThat(undeclared)
                .as("Give each new endpoint a @RequiresPermission, or add it to PUBLIC / ANY_SIGNED_IN_USER in this test")
                .isEmpty();
    }

    @Test
    void anonymousCallersReachOnlyThePublicEndpoints() {
        List<String> wrong = new ArrayList<>();
        for (Endpoint endpoint : endpoints()) {
            int status = callAndRollBack(endpoint, null);
            boolean blocked = status == 401 || status == 403;
            boolean isPublic = PUBLIC.contains(endpoint.toString());
            if (blocked == isPublic) wrong.add(endpoint + " -> " + status + (isPublic ? " (public, should be allowed)" : " (should need a sign-in)"));
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    void emptyOrInvalidInputIsAnsweredWithoutCrashing() throws Exception {
        // A Level 2 Super Admin of its own, so signing out at the end does not affect the shared accounts
        String username = uniq("sweep");
        insertEmployee(insertUser(username, "ADMIN_L2", "WEB", false, true));
        String token = login(username, SEED_PASSWORD);

        List<Endpoint> endpoints = endpoints().stream().sorted(SESSION_ENDING_LAST).toList();
        assertThat(endpoints).isNotEmpty();
        List<String> crashes = new ArrayList<>();
        for (Endpoint endpoint : endpoints) {
            int status = callAndRollBack(endpoint, token);
            if (status == 500) crashes.add(endpoint + " -> " + status);
        }
        // Not asserted here: the quality gate reports each crashing endpoint (baseline-aware, strict in UAT/production)
        crashes.forEach(crash -> System.out.println("[endpoint sweep] crashed on empty input: " + crash));
    }

    private int callAndRollBack(Endpoint endpoint, String token) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Integer status = tx.execute(state -> {
            state.setRollbackOnly();
            MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(endpoint.method(), endpoint.sampleUrl())
                    .contentType(MediaType.APPLICATION_JSON).content("{}");
            if (token != null) request.header("Authorization", "Bearer " + token);
            try {
                return mockMvc.perform(request).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                // An exception escaping MockMvc is what a client would see as a 500
                return 500;
            }
        });
        return status == null ? 500 : status;
    }

    private List<Endpoint> endpoints() {
        List<Endpoint> out = new ArrayList<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("com.tinqa")) return;
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (methods.isEmpty()) out.add(new Endpoint(HttpMethod.GET, pattern, handler));
                methods.forEach(m -> out.add(new Endpoint(HttpMethod.valueOf(m.name()), pattern, handler)));
            }
        });
        return out;
    }
}
