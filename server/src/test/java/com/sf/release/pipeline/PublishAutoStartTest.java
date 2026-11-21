package com.sf.release.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Map;
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

/**
 * The production start path: with {@code release.pipeline.auto-start=true} (the default outside the
 * test profile) publishing must enter the BUILD node immediately instead of leaving the instance
 * PENDING for nobody to drive.
 *
 * <p>This suite boots with the flag on. No CODING connection is configured, so the BUILD handler's
 * trigger fails — that failure is exactly the proof that the pipeline really started: the instance,
 * the BUILD node and CODE_CHECK all turn FAILED instead of staying PENDING.
 */
@SpringBootTest(properties = {
    "release.pipeline.auto-start=true",
    "release.publish-probe=auto-start-context"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublishAutoStartTest {

    private static final String USER = "01388007";
    private static final String ITEMS = "/api/v1/items";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private long taskId;
    private long unitId;

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM pipeline_item_failure");
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
        jdbc.update("DELETE FROM user_scope WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM user_role WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM app_user WHERE id = ?", USER);

        jdbc.update("INSERT INTO app_user (id, name, status, created_at)"
            + " VALUES (?, 'Dev', 'ACTIVE', NOW(3))", USER);
        jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, 'ADMIN')", USER);
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'ALL', 0)",
            USER);

        long subsystemId = insertAndGetId(
            "INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
                + " VALUES ('SCC-AUTOSTART', ?, 'ACTIVE', NOW(3), NOW(3))", USER);
        unitId = insertAndGetId(
            "INSERT INTO deploy_unit (subsystem_id, name, type, owner_id, framework_template,"
                + " branch_mode, status, module_path, created_at, updated_at)"
                + " VALUES (?, 'autostart-api', 'UNIT', ?, 'spring-boot', 'STANDARD', 'ACTIVE',"
                + " 'autostart-api', NOW(3), NOW(3))", subsystemId, USER);
        jdbc.update("INSERT INTO coding_task_binding (unit_id, task_kind, task_id, params_json,"
            + " enabled, created_at, updated_at) VALUES (?, 'BUILD', 'P-BUILD',"
            + " '{\"branch\":\"release/autostart\"}', 1, NOW(3), NOW(3))", unitId);
        jdbc.update("INSERT INTO coding_task_binding (unit_id, task_kind, task_id, params_json,"
            + " enabled, created_at, updated_at) VALUES (?, 'TEST_DEPLOY', 'P-TEST', NULL, 1,"
            + " NOW(3), NOW(3))", unitId);
        jdbc.update("INSERT INTO coding_task_binding (unit_id, task_kind, task_id, params_json,"
            + " enabled, created_at, updated_at) VALUES (?, 'PROD_DEPLOY', 'P-PROD', NULL, 1,"
            + " NOW(3), NOW(3))", unitId);
        jdbc.update("INSERT INTO env_de (unit_id, env_kind, de_name, deploy_batch_no, created_at,"
            + " updated_at) VALUES (?, 'TEST', 'autostart-sit', 0, NOW(3), NOW(3))", unitId);
        jdbc.update("INSERT INTO env_de (unit_id, env_kind, de_name, deploy_batch_no, created_at,"
            + " updated_at) VALUES (?, 'PROD', 'autostart-prod', 1, NOW(3), NOW(3))", unitId);
        taskId = insertAndGetId(
            "INSERT INTO release_task (subsystem_id, name, creator, purpose, plan_finish_date,"
                + " status, created_at, updated_at)"
                + " VALUES (?, 'AUTOSTART-TASK', ?, '新功能', ?, 'ACTIVE', NOW(3), NOW(3))",
            subsystemId, USER, LocalDate.of(2026, 11, 25));
    }

    @Test
    void publish_enters_the_build_node_instead_of_leaving_the_instance_pending() throws Exception {
        long itemId = createItem();
        MvcResult published = mvc.perform(post(ITEMS + "/{id}/publish", itemId)
                .header("X-User-Id", USER))
            .andReturn();
        String publishNo = objectMapper.readTree(published.getResponse().getContentAsString())
            .path("data").path("publishNo").asText();
        assertThat(publishNo).isNotBlank();

        Map<String, Object> instance = jdbc.queryForMap(
            "SELECT pi.status, pi.current_node FROM pipeline_instance pi"
                + " JOIN release_order ro ON ro.id = pi.order_id WHERE ro.publish_no = ?", publishNo);
        assertThat(instance.get("status"))
            .as("publish must start the pipeline; with no CODING connection the build fails")
            .isEqualTo("FAILED");
        assertThat(instance.get("current_node")).isEqualTo("BUILD");

        Map<String, Object> node = jdbc.queryForMap(
            "SELECT pn.status FROM pipeline_node pn WHERE pn.instance_id ="
                + " (SELECT pi.id FROM pipeline_instance pi JOIN release_order ro"
                + " ON ro.id = pi.order_id WHERE ro.publish_no = ?) AND pn.node_type = 'BUILD'",
            publishNo);
        assertThat(node.get("status")).isEqualTo("FAILED");

        Map<String, Object> firstTask = jdbc.queryForMap(
            "SELECT nt.status, nt.result_summary FROM node_task nt WHERE nt.instance_id ="
                + " (SELECT pi.id FROM pipeline_instance pi JOIN release_order ro"
                + " ON ro.id = pi.order_id WHERE ro.publish_no = ?)"
                + " AND nt.task_type = 'CODE_CHECK'",
            publishNo);
        assertThat(firstTask.get("status")).isEqualTo("FAILED");
        assertThat(String.valueOf(firstTask.get("result_summary")))
            .as("the engine failure is visible on the task, not swallowed")
            .contains("ENGINE");
    }

    private long createItem() throws Exception {
        var body = objectMapper.createObjectNode()
            .put("taskId", taskId)
            .put("unitId", unitId)
            .put("version", "V0.0.1-autostart")
            .put("planReleaseDate", "2026-11-25")
            .toString();
        MvcResult result = mvc.perform(post(ITEMS)
                .header("X-User-Id", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();
        var node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(node.path("code").asInt()).as(node.path("message").asText()).isZero();
        return node.path("data").path("itemId").asLong();
    }

    private long insertAndGetId(String sql, Object... args) {
        jdbc.update(sql, args);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }
}