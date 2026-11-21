package com.sf.release.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP seam of 部署单元管理: the real interceptor/JdbcAuthProvider data scope, the
 * {@code setting:manage} gate, the create/environment JSON mapping and the
 * {@code deploy_batch_no} contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UnitControllerTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String ADMIN = "t5-http-admin";
    private static final String DEV = "t5-http-dev";
    private static final String NO_SCOPE = "t5-http-noscope";
    private static final String OWNER = "01388007";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private long subsystemId;

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM env_de");
        jdbc.update("DELETE FROM coding_task_binding");
        jdbc.update("DELETE FROM deploy_unit");
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM user_scope WHERE user_id IN (?, ?, ?)", ADMIN, DEV, NO_SCOPE);
        jdbc.update("DELETE FROM user_role WHERE user_id IN (?, ?, ?)", ADMIN, DEV, NO_SCOPE);
        jdbc.update("DELETE FROM app_user WHERE id IN (?, ?, ?, ?)", ADMIN, DEV, NO_SCOPE, OWNER);
        givenUser(ADMIN, "ADMIN");
        givenUser(DEV, "DEV");
        givenUser(NO_SCOPE, "TEST");
        givenUser(OWNER);
        subsystemId = givenSubsystem("SCC-QUOTA");
    }

    @Test
    void unit_list_honors_data_scope_from_the_request_identity() throws Exception {
        long unitA = createUnit(ADMIN, "quota-api", subsystemId);
        long unitB = createUnit(ADMIN, "quota-worker", subsystemId);
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'UNIT', ?)",
            DEV, unitA);

        mvc.perform(get("/units").header(USER_HEADER, DEV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].id").value(unitA))
            .andExpect(jsonPath("$.data.records[0].name").value("quota-api"));

        mvc.perform(get("/units/{id}", unitB).header(USER_HEADER, DEV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));

        // No scope row at all: the list is empty, not unrestricted.
        mvc.perform(get("/units").header(USER_HEADER, NO_SCOPE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.records").isEmpty());

        // ADMIN is the explicit unrestricted marker.
        mvc.perform(get("/units").header(USER_HEADER, ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    void mutation_requires_setting_manage_and_unknown_subsystem_is_40000() throws Exception {
        mvc.perform(post("/units")
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(unitBody("quota-denied", subsystemId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));

        assertThatNoUnits();

        mvc.perform(post("/units")
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(unitBody("quota-ghost", 999_999L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40000));

        long id = createUnit(ADMIN, "quota-api", subsystemId);

        mvc.perform(post("/units/{id}/env-des", id)
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"envKind\":\"PROD\",\"deName\":\"cwsp-quota-api-prod\","
                    + "\"deployBatchNo\":5}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        mvc.perform(get("/units/{id}/env-des", id).header(USER_HEADER, ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data[0].envKind").value("PROD"))
            .andExpect(jsonPath("$.data[0].deployBatchNo").value(5));
    }

    @Test
    void coding_task_bindings_round_trip_and_stay_scoped() throws Exception {
        long id = createUnit(ADMIN, "quota-api", subsystemId);

        mvc.perform(put("/units/{id}/coding-tasks", id)
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bindings\":["
                    + "{\"taskKind\":\"BUILD\",\"taskId\":\"P-BUILD\","
                    + "\"paramsJson\":\"{\\\"branch\\\":\\\"release/202611\\\"}\"},"
                    + "{\"taskKind\":\"TEST_DEPLOY\",\"taskId\":\"P-TEST\"}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        // The settings page reads the mapping back instead of blindly overwriting it.
        // params_json is a MySQL JSON column, so the document text comes back normalized.
        mvc.perform(get("/units/{id}/coding-tasks", id).header(USER_HEADER, ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].taskKind").value("BUILD"))
            .andExpect(jsonPath("$.data[0].taskId").value("P-BUILD"))
            .andExpect(jsonPath("$.data[0].enabled").value(true))
            .andExpect(jsonPath("$.data[0].paramsJson").value("{\"branch\": \"release/202611\"}"))
            .andExpect(jsonPath("$.data[1].taskKind").value("TEST_DEPLOY"))
            .andExpect(jsonPath("$.data[1].paramsJson").doesNotExist());

        // Read-only still means fail-closed: an unscoped caller cannot see the ids.
        mvc.perform(get("/units/{id}/coding-tasks", id).header(USER_HEADER, DEV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));
    }

    @Test
    void coding_jobs_endpoint_checks_the_project_before_talking_to_the_engine() throws Exception {
        long id = createUnit(ADMIN, "quota-api", subsystemId);

        // This subsystem has no CODING project, so the picker is empty for a truthful reason.
        mvc.perform(get("/units/{id}/coding-jobs", id)
                .param("taskKind", "BUILD")
                .header(USER_HEADER, ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40000));

        jdbc.update("UPDATE subsystem SET coding_project = 'quota-project' WHERE id = ?",
            subsystemId);

        // The real adapter has no connection in this profile: the UI falls back to manual input.
        mvc.perform(get("/units/{id}/coding-jobs", id)
                .param("taskKind", "BUILD")
                .header(USER_HEADER, ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(50001));

        // Unknown kinds never reach the engine.
        mvc.perform(get("/units/{id}/coding-jobs", id)
                .param("taskKind", "NOPE")
                .header(USER_HEADER, ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40000));
    }

    private long createUnit(String userId, String name, long subsystemId) throws Exception {
        MvcResult result = mvc.perform(post("/units")
                .header(USER_HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(unitBody(name, subsystemId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
            .path("data").asLong();
    }

    private static String unitBody(String name, long subsystemId) {
        return "{\"subsystemId\":" + subsystemId + ",\"name\":\"" + name + "\","
            + "\"ownerId\":\"" + OWNER + "\",\"du\":\"DU-QUOTA\",\"modulePath\":\"outbound-os\"}";
    }

    private void assertThatNoUnits() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM deploy_unit", Integer.class);
        assertThat(count).isZero();
    }

    private long givenSubsystem(String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, OWNER);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private void givenUser(String userId, String... roles) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, 'ACTIVE', NOW(3))",
            userId, "用户" + userId);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
        }
    }
}