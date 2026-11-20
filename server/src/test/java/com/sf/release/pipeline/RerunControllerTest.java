package com.sf.release.pipeline;

import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.application.PublishAppService;
import com.sf.release.application.PublishAppService.PublishResult;
import com.sf.release.domain.pipeline.port.GitCommitProbe;
import com.sf.release.infra.lock.UnitLockService;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP seam of {@code POST /api/v1/items/{itemId}/rerun}: authentication plus the
 * {@code release:publish} permission gate, the optional confirmation body and the response
 * shape of the rerun decision.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RerunControllerTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String DEV = "t15-http-dev";
    private static final String OWNER = "01388007";
    private static final String PATH = "/api/v1/items/{itemId}/rerun";
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 25);
    private static final String BASE = "a".repeat(40);
    private static final String OTHER = "b".repeat(40);
    private static final String BRANCH = "release/202611";
    private static final String MODULE = "outbound-os/quota";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ItemAppService itemService;
    @Autowired PublishAppService publishService;
    @Autowired UnitLockService unitLockService;
    @MockBean GitCommitProbe commitProbe;

    private long unitId;
    private long itemId;

    @BeforeEach
    void resetFixtures() {
        TestUserContext.allUnits(DEV);
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
        jdbc.update("DELETE FROM user_scope WHERE user_id IN (?, ?)", DEV, OWNER);
        jdbc.update("DELETE FROM user_role WHERE user_id IN (?, ?)", DEV, OWNER);
        jdbc.update("DELETE FROM app_user WHERE id IN (?, ?)", DEV, OWNER);

        givenUser(OWNER, null);
        givenUser(DEV, "DEV");
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'ALL', 0)",
            DEV);
        long subsystemId = givenSubsystem("SCC-T15-HTTP");
        long taskId = givenTask(subsystemId, "202611-T15-HTTP");
        unitId = givenUnit(subsystemId, "t15-http-api");
        givenBinding(unitId, "BUILD", "P-BUILD-T15");
        givenBinding(unitId, "TEST_DEPLOY", "P-TEST-T15");
        givenBinding(unitId, "PROD_DEPLOY", "P-PROD-T15");
        givenDe(unitId, "TEST", "t15-http-sit", 0);
        itemId = itemService.create(taskId,
            new CreateItemCmd(unitId, BRANCH, "重新发布判定", PLAN_DATE));
    }

    @AfterEach
    void clearUserContext() {
        TestUserContext.clear();
    }

    @Test
    void rerun_requires_authentication_and_release_publish() throws Exception {
        mvc.perform(post(PATH, itemId).contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));
    }

    @Test
    void confirmed_rerun_returns_the_new_pipeline_and_the_decision() throws Exception {
        PublishResult published = publishService.publish(itemId);
        long oldInstance = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class,
            published.pipelineNo());
        jdbc.update("UPDATE release_item SET status = 'FAILED', base_commit = ?,"
            + " last_test_pass_by = 'tester-t15', last_test_pass_at = NOW(3) WHERE id = ?",
            BASE, itemId);
        jdbc.update("UPDATE pipeline_instance SET status = 'FAILED', image_digest = ?,"
            + " commit_sha = ? WHERE id = ?", "sha256:t15-old", BASE, oldInstance);
        unitLockService.release(unitId, oldInstance);
        when(commitProbe.headSha(anyLong(), anyString())).thenReturn(OTHER);
        when(commitProbe.hasChangesUnder(anyLong(), anyString(), anyString(), anyString()))
            .thenReturn(true);

        mvc.perform(post(PATH, itemId)
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmRevokeTestPass\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.pipelineNo").isNotEmpty())
            .andExpect(jsonPath("$.data.full").value(true))
            .andExpect(jsonPath("$.data.revokedTestPass").value(true))
            .andExpect(jsonPath("$.data.degraded").value(false));

        Integer instances = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pipeline_instance", Integer.class);
        assertThat(instances).isEqualTo(2);
    }

    @Test
    void changed_commit_without_confirmation_is_a_business_error() throws Exception {
        PublishResult published = publishService.publish(itemId);
        long oldInstance = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class,
            published.pipelineNo());
        jdbc.update("UPDATE release_item SET status = 'FAILED', base_commit = ?,"
            + " last_test_pass_by = 'tester-t15', last_test_pass_at = NOW(3) WHERE id = ?",
            BASE, itemId);
        jdbc.update("UPDATE pipeline_instance SET status = 'FAILED', image_digest = ?,"
            + " commit_sha = ? WHERE id = ?", "sha256:t15-old", BASE, oldInstance);
        unitLockService.release(unitId, oldInstance);
        when(commitProbe.headSha(anyLong(), anyString())).thenReturn(OTHER);
        when(commitProbe.hasChangesUnder(anyLong(), anyString(), anyString(), anyString()))
            .thenReturn(true);

        mvc.perform(post(PATH, itemId).header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.message").value(
                org.hamcrest.Matchers.containsString("二次确认")));
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private void givenUser(String userId, String role) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, 'ACTIVE', NOW(3))",
            userId, "用户" + userId);
        if (role != null) {
            jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
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
            subsystem, name, OWNER, PLAN_DATE);
        Long id = jdbc.queryForObject(
            "SELECT id FROM release_task WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystem, name);
        return id == null ? 0L : id;
    }

    private long givenUnit(long subsystem, String name) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id, framework_template,"
                + " branch_mode, status, module_path, created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, 'spring-boot', 'STANDARD', 'ACTIVE', ?,"
                + " NOW(3), NOW(3))",
            subsystem, name, OWNER, MODULE);
        Long id = jdbc.queryForObject(
            "SELECT id FROM deploy_unit WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystem, name);
        return id == null ? 0L : id;
    }

    private void givenBinding(long unit, String kind, String taskId) {
        jdbc.update("INSERT INTO coding_task_binding (unit_id, task_kind, task_id, enabled,"
                + " created_at, updated_at) VALUES (?, ?, ?, 1, NOW(3), NOW(3))",
            unit, kind, taskId);
    }

    private void givenDe(long unit, String envKind, String name, int batchNo) {
        jdbc.update("INSERT INTO env_de (unit_id, env_kind, de_name, deploy_batch_no,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, NOW(3), NOW(3))",
            unit, envKind, name, batchNo);
    }
}
