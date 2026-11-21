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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of the 变更项 write endpoints (C2):
 * {@code POST /api/v1/items} ({@code item:create}) and
 * {@code POST /api/v1/items/{itemId}/publish} ({@code release:publish}).
 *
 * <p>The create test proves the branch is read-only: a client that sends its own
 * {@code sourceBranch} still gets the binding's branch persisted (C21). The publish test proves
 * the endpoint returns the 发布单/pipeline numbers the UI needs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ItemControllerTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String DEV = "t2-http-dev";
    private static final String TESTER = "t2-http-tester";
    private static final String OWNER = "01388007";
    private static final String BRANCH = "release/202611";
    private static final String MODULE = "outbound-os/quota";
    private static final String ITEMS = "/api/v1/items";
    private static final String PUBLISH = "/api/v1/items/{itemId}/publish";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private long taskId;
    private long unitId;

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM node_task");
        jdbc.update("DELETE FROM pipeline_node");
        jdbc.update("DELETE FROM pipeline_instance");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM deploy_unit_lock");
        jdbc.update("DELETE FROM release_item");
        jdbc.update("DELETE FROM release_task_member");
        jdbc.update("DELETE FROM release_task");
        jdbc.update("DELETE FROM env_de");
        jdbc.update("DELETE FROM coding_task_binding");
        jdbc.update("DELETE FROM deploy_unit");
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM user_scope WHERE user_id IN (?, ?)", DEV, TESTER);
        jdbc.update("DELETE FROM user_role WHERE user_id IN (?, ?)", DEV, TESTER);
        jdbc.update("DELETE FROM app_user WHERE id IN (?, ?, ?)", DEV, TESTER, OWNER);

        givenUser(OWNER, (String[]) null);
        givenUser(DEV, "DEV");
        givenUser(TESTER, "TEST");
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'ALL', 0)",
            DEV);
        long subsystemId = givenSubsystem("SCC-T2");
        taskId = givenTask(subsystemId, "202611-T2");
        unitId = givenUnit(subsystemId, "t2-api");
        // The branch the platform is allowed to use is the one on the CODING build binding.
        givenBinding(unitId, "BUILD", "P-BUILD-T2", "{\"branch\":\"" + BRANCH + "\"}");
        givenBinding(unitId, "TEST_DEPLOY", "P-TEST-T2", null);
        givenBinding(unitId, "PROD_DEPLOY", "P-PROD-T2", null);
        givenDe(unitId, "TEST", "t2-sit", 0);
        givenDe(unitId, "PROD", "t2-prod", 1);
    }

    @Test
    void create_returns_item_id_and_crid_and_uses_the_binding_branch() throws Exception {
        MvcResult result = mvc.perform(post(ITEMS)
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(unitId, "client-supplied/branch")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.crid").value(org.hamcrest.Matchers.startsWith("CR-")))
            .andExpect(jsonPath("$.data.itemId").isNumber())
            .andReturn();

        long itemId = objectMapper.readTree(result.getResponse().getContentAsString())
            .path("data").path("itemId").asLong();
        assertThat(itemId).isPositive();

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT source_branch, module_path, build_plan_id, status FROM release_item"
                + " WHERE id = ?", itemId);
        assertThat(row.get("source_branch"))
            .as("the client branch is ignored; the binding branch is authoritative")
            .isEqualTo(BRANCH);
        assertThat(row.get("module_path")).isEqualTo(MODULE);
        assertThat(row.get("build_plan_id")).isEqualTo("P-BUILD-T2");
        assertThat(row.get("status")).isEqualTo("PENDING");
    }

    @Test
    void create_requires_authentication_and_item_create() throws Exception {
        mvc.perform(post(ITEMS).contentType(MediaType.APPLICATION_JSON)
                .content(createBody(unitId, null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));

        mvc.perform(post(ITEMS).header(USER_HEADER, TESTER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(unitId, null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));

        assertThat(itemCount()).isZero();
    }

    @Test
    void create_on_a_unit_without_a_binding_branch_is_40011_naming_the_missing_set()
        throws Exception {
        long unitWithoutBranch = givenUnit(givenSubsystem("SCC-T2-NOBRANCH"), "t2-nobranch-api");
        givenBinding(unitWithoutBranch, "BUILD", "P-BUILD-NOBRANCH", null);

        mvc.perform(post(ITEMS).header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(unitWithoutBranch, null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40011))
            .andExpect(jsonPath("$.data.missing[0]").value("BUILD_BRANCH"));

        assertThat(itemCount()).isZero();
    }

    @Test
    void create_out_of_scope_is_40009() throws Exception {
        long otherUnit = givenUnit(givenSubsystem("SCC-T2-OTHER"), "t2-other-api");
        givenBinding(otherUnit, "BUILD", "P-BUILD-OTHER", "{\"branch\":\"" + BRANCH + "\"}");
        jdbc.update("DELETE FROM user_scope WHERE user_id = ?", DEV);
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'UNIT', ?)",
            DEV, unitId);

        mvc.perform(post(ITEMS).header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(otherUnit, null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));

        assertThat(itemCount()).isZero();
    }

    @Test
    void publish_returns_the_publish_no_and_pipeline_no() throws Exception {
        long itemId = createViaHttp();

        mvc.perform(post(PUBLISH, itemId).header(USER_HEADER, DEV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.publishNo")
                .value(org.hamcrest.Matchers.startsWith("PO-")))
            .andExpect(jsonPath("$.data.pipelineNo")
                .value(org.hamcrest.Matchers.startsWith("PL-")));

        assertThat(count("release_order")).isEqualTo(1);
        assertThat(count("pipeline_instance")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT source_branch FROM release_item WHERE id = ?", String.class, itemId))
            .isEqualTo(BRANCH);
    }

    @Test
    void publish_requires_authentication_and_release_publish() throws Exception {
        long itemId = createViaHttp();

        mvc.perform(post(PUBLISH, itemId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));

        mvc.perform(post(PUBLISH, itemId).header(USER_HEADER, TESTER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));

        assertThat(count("release_order")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?",
            String.class, itemId)).isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private long createViaHttp() throws Exception {
        MvcResult result = mvc.perform(post(ITEMS)
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(unitId, null)))
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
            .path("data").path("itemId").asLong();
    }

    private String createBody(long unit, String clientBranch) throws Exception {
        var body = objectMapper.createObjectNode()
            .put("taskId", taskId)
            .put("unitId", unit)
            .put("version", "变更原因")
            .put("planReleaseDate", "2026-11-25");
        if (clientBranch != null) {
            body.put("sourceBranch", clientBranch);
        }
        return objectMapper.writeValueAsString(body);
    }

    private int itemCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM release_item", Integer.class);
        return count == null ? 0 : count;
    }

    private int count(String table) {
        List<Integer> counts = jdbc.queryForList("SELECT COUNT(*) FROM " + table, Integer.class);
        return counts.isEmpty() || counts.get(0) == null ? 0 : counts.get(0);
    }

    private void givenUser(String userId, String... roles) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, 'ACTIVE',"
            + " NOW(3))", userId, "用户" + userId);
        if (roles != null) {
            for (String role : roles) {
                jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
            }
        }
    }

    private long givenSubsystem(String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, OWNER);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private long givenTask(long subsystem, String name) {
        jdbc.update("INSERT INTO release_task (subsystem_id, name, creator, purpose,"
                + " plan_finish_date, status, created_at, updated_at)"
                + " VALUES (?, ?, ?, '新功能', ?, 'ACTIVE', NOW(3), NOW(3))",
            subsystem, name, OWNER, java.time.LocalDate.of(2026, 11, 25));
        Long id = jdbc.queryForObject(
            "SELECT id FROM release_task WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystem, name);
        return id == null ? 0L : id;
    }

    private long givenUnit(long subsystem, String name) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id,"
                + " framework_template, branch_mode, status, module_path, created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, 'spring-boot', 'STANDARD', 'ACTIVE', ?,"
                + " NOW(3), NOW(3))",
            subsystem, name, OWNER, MODULE);
        Long id = jdbc.queryForObject(
            "SELECT id FROM deploy_unit WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystem, name);
        return id == null ? 0L : id;
    }

    private void givenBinding(long unit, String kind, String taskId, String paramsJson) {
        jdbc.update("INSERT INTO coding_task_binding (unit_id, task_kind, task_id, params_json,"
                + " enabled, created_at, updated_at) VALUES (?, ?, ?, ?, 1, NOW(3), NOW(3))",
            unit, kind, taskId, paramsJson);
    }

    private void givenDe(long unit, String envKind, String name, int batchNo) {
        jdbc.update("INSERT INTO env_de (unit_id, env_kind, de_name, deploy_batch_no,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, NOW(3), NOW(3))",
            unit, envKind, name, batchNo);
    }
}