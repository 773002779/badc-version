package com.sf.release.infra.security;

import com.sf.release.common.ApiResponse;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.security.DataScope;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.Public;
import com.sf.release.common.security.RequirePerm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end guard tests: a real MySQL test schema (Flyway), the real
 * {@link JdbcAuthProvider}, and the real interceptor registered on the MVC chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PermissionInterceptorTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String ACTIVE = "ACTIVE";
    private static final String INACTIVE = "INACTIVE";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthProvider authProvider;

    /**
     * Test-only endpoints. They live in the security package (not {@code web}) so the
     * probe may call {@link ScopeGuard} without creating a forbidden web -> infra edge.
     */
    @TestConfiguration
    static class ProbeConfig {
        @Bean
        SecurityProbeController securityProbeController() {
            return new SecurityProbeController();
        }
    }

    @RestController
    @RequestMapping("/security-probe")
    public static class SecurityProbeController {

        @RequirePerm("release:publish")
        @GetMapping("/publish")
        public ApiResponse<String> publish() {
            return ApiResponse.ok(UserContext.current().userId());
        }

        @RequirePerm("item:create")
        @DataScope
        @GetMapping("/unit")
        public ApiResponse<String> unit(@RequestParam long unitId) {
            ScopeGuard.assertUnitInScope(unitId);
            return ApiResponse.ok("unit-" + unitId);
        }

        @GetMapping("/whoami")
        public ApiResponse<String> whoami() {
            return ApiResponse.ok(UserContext.current().userId());
        }

        @Public
        @GetMapping("/public")
        public ApiResponse<String> publicProbe() {
            return ApiResponse.ok("public-ok");
        }
    }

    @BeforeEach
    void resetAuthFixtures() {
        jdbc.update("DELETE FROM user_scope");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user");
        // Do not touch the shipped role -> permission seed; only drop rows a test adds.
        jdbc.update("DELETE FROM role_permission WHERE role IN ('OPS', 'OPS_LEAD') AND perm = 'release:publish'");
        jdbc.update("DELETE FROM role_permission WHERE role IN ('OPS', 'OPS_LEAD') AND perm = 'item:create'");
        UserContext.clear();
    }

    @Test
    void missing_permission_returns_40009() throws Exception {
        givenUser("u-test", ACTIVE, "TEST");

        mvc.perform(probe("/security-probe/publish", "u-test"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(40009))
           .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void out_of_scope_unit_returns_40009() throws Exception {
        givenUser("u-dev", ACTIVE, "DEV");
        givenUnitScope("u-dev", 1L);

        mvc.perform(probe("/security-probe/unit", "u-dev").param("unitId", "1"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0))
           .andExpect(jsonPath("$.data").value("unit-1"));

        mvc.perform(probe("/security-probe/unit", "u-dev").param("unitId", "2"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(40009))
           .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void ops_cannot_create_code_release() throws Exception {
        givenUser("u-ops", ACTIVE, "OPS");
        givenUser("u-ops-lead", ACTIVE, "OPS_LEAD");
        givenUser("u-ops-dev", ACTIVE, "DEV", "OPS");
        // Grant the permission through the mapping anyway: the hard rule must win (C20).
        grant("OPS", "release:publish");
        grant("OPS_LEAD", "release:publish");

        for (String userId : new String[] {"u-ops", "u-ops-lead", "u-ops-dev"}) {
            assertThat(authProvider.authenticate(userId).orElseThrow().hasPerm("release:publish"))
                .as("%s really holds release:publish through the mapping", userId)
                .isTrue();

            mvc.perform(probe("/security-probe/publish", userId))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.code").value(40009));
        }
    }

    @Test
    void ops_cannot_create_a_code_change_item() throws Exception {
        givenUser("u-ops-item", ACTIVE, "OPS");
        givenUser("u-ops-item-lead", ACTIVE, "OPS_LEAD");
        // Grant the permission through the mapping anyway: C20 also covers item creation.
        grant("OPS", "item:create");
        grant("OPS_LEAD", "item:create");

        for (String userId : new String[] {"u-ops-item", "u-ops-item-lead"}) {
            assertThat(authProvider.authenticate(userId).orElseThrow().hasPerm("item:create"))
                .as("%s really holds item:create through the mapping", userId)
                .isTrue();

            mvc.perform(probe("/security-probe/unit?unitId=1", userId))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.code").value(40009))
               .andExpect(jsonPath("$.message").value("运维角色不得发起代码版本发布或变更项"));
        }
    }

    @Test
    void missing_user_header_returns_40009() throws Exception {
        mvc.perform(probe("/security-probe/publish", null))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(40009))
           .andExpect(jsonPath("$.message").value("缺少 X-User-Id 请求头"))
           .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void unknown_user_returns_40009() throws Exception {
        mvc.perform(probe("/security-probe/publish", "u-ghost"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(40009))
           .andExpect(jsonPath("$.message").value("用户不存在或已停用"));
    }

    @Test
    void inactive_user_returns_40009() throws Exception {
        givenUser("u-inactive", INACTIVE, "DEV");

        mvc.perform(probe("/security-probe/publish", "u-inactive"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(40009))
           .andExpect(jsonPath("$.message").value("用户不存在或已停用"));
    }

    @Test
    void permitted_call_reaches_handler() throws Exception {
        givenUser("u-dev", ACTIVE, "DEV");

        mvc.perform(probe("/security-probe/publish", "u-dev"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0))
           .andExpect(jsonPath("$.data").value("u-dev"))
           .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void user_context_is_cleared_after_request() throws Exception {
        givenUser("u-dev", ACTIVE, "DEV");

        mvc.perform(probe("/security-probe/whoami", "u-dev"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0))
           .andExpect(jsonPath("$.data").value("u-dev"));

        assertThatThrownBy(UserContext::current)
            .isInstanceOf(BizException.class)
            .satisfies(ex -> assertThat(((BizException) ex).getError()).isEqualTo(BizError.FORBIDDEN));
    }

    @Test
    void empty_scope_denies_unit_access() throws Exception {
        givenUser("u-dev", ACTIVE, "DEV");

        mvc.perform(probe("/security-probe/unit", "u-dev").param("unitId", "42"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(40009))
           .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void explicit_all_units_marker_allows_any_unit() throws Exception {
        // ADMIN role is the seeded unrestricted marker.
        givenUser("u-admin", ACTIVE, "ADMIN");
        mvc.perform(probe("/security-probe/unit", "u-admin").param("unitId", "42"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0))
           .andExpect(jsonPath("$.data").value("unit-42"));

        // An explicit scope_type='ALL' row grants unrestricted access to any role.
        givenUser("u-dev", ACTIVE, "DEV");
        givenAllScope("u-dev");
        mvc.perform(probe("/security-probe/unit", "u-dev").param("unitId", "7"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0))
           .andExpect(jsonPath("$.data").value("unit-7"));
    }

    @Test
    void public_endpoint_is_reachable_without_identity() throws Exception {
        mvc.perform(get("/security-probe/public"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0))
           .andExpect(jsonPath("$.data").value("public-ok"));

        // A normal handler without an identity is still rejected.
        mvc.perform(probe("/security-probe/publish", null))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(40009));

        assertThatThrownBy(UserContext::current).isInstanceOf(BizException.class);
    }

    @Test
    void annotation_contract_is_runtime_visible() throws Exception {
        Method publish = SecurityProbeController.class.getDeclaredMethod("publish");
        Method unit = SecurityProbeController.class.getDeclaredMethod("unit", long.class);
        Method publicProbe = SecurityProbeController.class.getDeclaredMethod("publicProbe");

        assertThat(publish.getAnnotation(RequirePerm.class).value()).isEqualTo("release:publish");
        assertThat(unit.getAnnotation(RequirePerm.class).value()).isEqualTo("item:create");
        assertThat(unit.getAnnotation(DataScope.class)).isNotNull();
        assertThat(publicProbe.getAnnotation(Public.class)).isNotNull();
    }

    @Test
    void admin_role_holds_every_permission_point() {
        assertThat(Permissions.ALL).hasSize(16);
        for (String perm : Permissions.ALL) {
            Integer granted = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permission WHERE role = 'ADMIN' AND perm = ?",
                Integer.class, perm);
            assertThat(granted).as("ADMIN must hold %s", perm).isEqualTo(1);
        }
    }

    private void givenUser(String userId, String status, String... roles) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, ?, NOW(3))",
            userId, "用户" + userId, status);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
        }
    }

    private void givenUnitScope(String userId, long unitId) {
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'UNIT', ?)",
            userId, unitId);
    }

    private void givenAllScope(String userId) {
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'ALL', 0)",
            userId);
    }

    private void grant(String role, String perm) {
        jdbc.update("INSERT IGNORE INTO role_permission (role, perm) VALUES (?, ?)", role, perm);
    }

    private MockHttpServletRequestBuilder probe(String path, String userId) {
        MockHttpServletRequestBuilder request = get(path);
        if (userId != null) {
            request.header(USER_HEADER, userId);
        }
        return request;
    }
}
