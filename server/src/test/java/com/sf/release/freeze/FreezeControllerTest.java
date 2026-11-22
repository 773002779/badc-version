package com.sf.release.freeze;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP seam of 封板 (tech design 4.8): the {@code /api/v1/freezes} contract with the real interceptor
 * and the real test schema.
 *
 * <p>Pinned here: anybody logged in may <em>read</em> the freeze list (the banner is for everybody),
 * declaring needs {@code control:freeze} (部门经理) and lifting needs {@code control:unfreeze}
 * (运维组长); the declaring/lifting user comes from the request identity, not from the body; a
 * reversed range is 40000 with the offending field, and lifting twice is 40001.
 *
 * <p>The test lives in {@code com.sf.release.freeze}, not in a {@code ..web..} package: the
 * architecture rule forbids {@code web} depending on {@code domain}/{@code infra}, and a test in
 * {@code web} would drag those in.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FreezeControllerTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String MANAGER = "fc-manager";
    private static final String LEAD = "fc-lead";
    private static final String TESTER = "fc-tester";
    private static final String ADMIN = "fc-admin";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM admission_snapshot");
        jdbc.update("DELETE FROM freeze_event");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM release_item");
        jdbc.update("DELETE FROM release_task");
        jdbc.update("DELETE FROM deploy_unit");
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM window_exception");
        jdbc.update("DELETE FROM window_rule");
        jdbc.update("DELETE FROM release_window");
        jdbc.update("DELETE FROM user_scope");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user");

        givenUser(MANAGER, "DEPT_MANAGER");
        givenUser(LEAD, "OPS_LEAD");
        givenUser(TESTER, "TEST");
        givenUser(ADMIN, "ADMIN");
    }

    @Test
    void declaring_a_freeze_needs_control_freeze() throws Exception {
        mvc.perform(post("/api/v1/freezes")
                .header(USER_HEADER, TESTER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(freezeBody("GLOBAL", null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.perm").value("control:freeze"));
    }

    @Test
    void a_department_manager_declares_an_active_freeze() throws Exception {
        mvc.perform(post("/api/v1/freezes")
                .header(USER_HEADER, MANAGER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(freezeBody("GLOBAL", null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.id").isNumber())
            .andExpect(jsonPath("$.data.scope").value("GLOBAL"))
            .andExpect(jsonPath("$.data.scopeLabel").value("全局"))
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.statusLabel").value("生效中"))
            .andExpect(jsonPath("$.data.active").value(true))
            .andExpect(jsonPath("$.data.initiator").value(MANAGER))
            .andExpect(jsonPath("$.data.reason").value("大促封板"));

        mvc.perform(get("/api/v1/freezes").header(USER_HEADER, TESTER))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void a_reversed_range_is_40000_with_the_offending_field() throws Exception {
        mvc.perform(post("/api/v1/freezes")
                .header(USER_HEADER, MANAGER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"GLOBAL\",\"reason\":\"大促封板\","
                    + "\"startAt\":\"2026-11-24T00:00:00Z\",\"endAt\":\"2026-11-23T00:00:00Z\"}"))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.endAt").isNotEmpty());
    }

    @Test
    void a_subsystem_freeze_needs_an_existing_system() throws Exception {
        mvc.perform(post("/api/v1/freezes")
                .header(USER_HEADER, MANAGER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(freezeBody("SUBSYSTEM", 999_999L)))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.subsystemId").isNotEmpty());

        long subsystemId = givenSubsystem("FC-SYS");
        mvc.perform(post("/api/v1/freezes")
                .header(USER_HEADER, MANAGER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(freezeBody("SUBSYSTEM", subsystemId)))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.subsystemId").value(subsystemId));
    }

    @Test
    void lifting_a_freeze_needs_control_unfreeze() throws Exception {
        long freezeId = givenFreeze();

        mvc.perform(post("/api/v1/freezes/{id}/release", freezeId)
                .header(USER_HEADER, TESTER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"解除\"}"))
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.perm").value("control:unfreeze"));
    }

    @Test
    void an_operations_lead_lifts_the_freeze_once() throws Exception {
        long freezeId = givenFreeze();

        mvc.perform(post("/api/v1/freezes/{id}/release", freezeId)
                .header(USER_HEADER, LEAD)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"大促结束\"}"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.status").value("RELEASED"))
            .andExpect(jsonPath("$.data.statusLabel").value("已解除"))
            .andExpect(jsonPath("$.data.releasedBy").value(LEAD))
            .andExpect(jsonPath("$.data.releasedAt").isNotEmpty())
            .andExpect(jsonPath("$.data.active").value(false));

        mvc.perform(post("/api/v1/freezes/{id}/release", freezeId)
                .header(USER_HEADER, LEAD)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"再解除一次\"}"))
            .andExpect(jsonPath("$.code").value(40001))
            .andExpect(jsonPath("$.data.status").value("RELEASED"));
    }

    @Test
    void lifting_requires_a_reason() throws Exception {
        long freezeId = givenFreeze();

        mvc.perform(post("/api/v1/freezes/{id}/release", freezeId)
                .header(USER_HEADER, LEAD)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"  \"}"))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.reason").isNotEmpty());
    }

    @Test
    void the_history_list_is_reachable_with_active_false() throws Exception {
        long freezeId = givenFreeze();
        mvc.perform(post("/api/v1/freezes/{id}/release", freezeId)
                .header(USER_HEADER, LEAD)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"解除\"}"))
            .andExpect(jsonPath("$.code").value(0));

        mvc.perform(get("/api/v1/freezes").header(USER_HEADER, TESTER))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data").isEmpty());

        mvc.perform(get("/api/v1/freezes").param("active", "false")
                .header(USER_HEADER, TESTER))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].status").value("RELEASED"));
    }

    @Test
    void a_missing_request_header_is_40009() throws Exception {
        mvc.perform(get("/api/v1/freezes"))
            .andExpect(jsonPath("$.code").value(40009));
        mvc.perform(post("/api/v1/freezes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(freezeBody("GLOBAL", null)))
            .andExpect(jsonPath("$.code").value(40009));
    }

    @Test
    void a_negative_subsystem_filter_is_40000() throws Exception {
        mvc.perform(get("/api/v1/freezes").param("subsystemId", "-1")
                .header(USER_HEADER, TESTER))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.subsystemId").isNotEmpty());
    }

    // ------------------------------------------------------------------ fixtures

    private static String freezeBody(String scope, Long subsystemId) {
        String end = java.time.Instant.now().plusSeconds(3600).toString();
        return "{\"scope\":\"" + scope + "\",\"reason\":\"大促封板\",\"impact\":\"全平台只读\","
            + "\"subsystemId\":" + subsystemId + ",\"endAt\":\"" + end + "\"}";
    }

    private long givenFreeze() {
        jdbc.update("INSERT INTO freeze_event (scope, subsystem_id, reason, impact, initiator_id,"
                + " start_at, end_at, status, created_at)"
                + " VALUES ('GLOBAL', NULL, '大促封板', NULL, ?, NOW(3), DATE_ADD(NOW(3),"
                + " INTERVAL 1 HOUR), 'ACTIVE', NOW(3))", MANAGER);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM freeze_event", Long.class);
        assertThat(id).isNotNull();
        return id;
    }

    private long givenSubsystem(String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, MANAGER);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private void givenUser(String userId, String... roles) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3))", userId, "用户" + userId);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
        }
    }
}