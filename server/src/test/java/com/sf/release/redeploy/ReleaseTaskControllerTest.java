package com.sf.release.redeploy;

import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.application.PublishAppService;
import com.sf.release.application.PublishAppService.PublishResult;
import com.sf.release.domain.engine.BuildCmd;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.DeployCmd;
import com.sf.release.domain.engine.EngineResult;
import com.sf.release.domain.engine.GateCheckCmd;
import com.sf.release.domain.engine.JobKind;
import com.sf.release.domain.engine.TriggerResult;
import com.sf.release.domain.pipeline.port.GitCommitProbe;
import com.sf.release.infra.lock.UnitLockService;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP seam of the two P6-G task actions on the 发布内容 page (design §3):
 * {@code POST /api/v1/releases/{publishNo}/tasks/{taskId}/redeploy} behind the
 * {@code control:execute-prod} permission gate and
 * {@code GET /api/v1/releases/{publishNo}/tasks/{taskId}/logs} behind login plus the unit scope.
 *
 * <p>Pinned here: a missing {@code X-User-Id} is 40009 for both endpoints; an eligible OPS caller
 * gets the redeploy read model (fromNode, batchNo, the retried DE ids and name) and one page of
 * the engine's log text; an ineligible task keeps its 40001 business error through the envelope.
 *
 * <p>{@code ControllerStubEngine} is the {@code @Primary} engine bean: {@code auto-start} is off
 * in the test profile so the redeploy itself never contacts CODING, and the log endpoint reads
 * the stub's fixed text.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReleaseTaskControllerTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String OPS = "p6g-ct-ops";
    private static final String OWNER = "p6g-ct-owner";
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 26);
    private static final String BASE = "a".repeat(40);
    private static final String BRANCH = "release/202611";
    private static final String MODULE = "outbound-os/quota";
    private static final String OLD_DIGEST = "sha256:p6g-ct-old";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ItemAppService itemService;
    @Autowired PublishAppService publishService;
    @Autowired UnitLockService unitLockService;
    @MockBean GitCommitProbe commitProbe;

    private long unitId;
    private long itemId;
    private long batch2DeBId;
    private long batch2DeCId;

    @BeforeEach
    void resetFixtures() {
        TestUserContext.allUnits(OWNER);
        jdbc.update("DELETE FROM node_task");
        jdbc.update("DELETE FROM pipeline_node");
        jdbc.update("DELETE FROM pipeline_instance");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM deploy_unit_lock");
        jdbc.update("DELETE FROM gate_check");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM release_item");
        jdbc.update("DELETE FROM release_task_member");
        jdbc.update("DELETE FROM release_task");
        jdbc.update("DELETE FROM env_de");
        jdbc.update("DELETE FROM coding_task_binding");
        jdbc.update("DELETE FROM deploy_unit");
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM user_scope WHERE user_id IN (?, ?)", OPS, OWNER);
        jdbc.update("DELETE FROM user_role WHERE user_id IN (?, ?)", OPS, OWNER);
        jdbc.update("DELETE FROM app_user WHERE id IN (?, ?)", OPS, OWNER);

        givenUser(OWNER, null);
        givenUser(OPS, "OPS");
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'ALL', 0)",
            OPS);
        long subsystemId = givenSubsystem("SCC-P6G-CT");
        long taskId = givenTask(subsystemId, "202611-P6G-CT");
        unitId = givenUnit(subsystemId, "p6g-ct-api");
        givenBinding(unitId, "BUILD", "P-BUILD-P6G-CT");
        givenBinding(unitId, "TEST_DEPLOY", "P-TEST-P6G-CT");
        givenBinding(unitId, "PROD_DEPLOY", "P-PROD-P6G-CT");
        givenDe(unitId, "TEST", "p6g-ct-sit", 0);
        givenDe(unitId, "PROD", "p6g-ct-prod", 1);
        batch2DeBId = givenDe(unitId, "PROD", "p6g-ct-prod-b", 2);
        batch2DeCId = givenDe(unitId, "PROD", "p6g-ct-prod-c", 2);
        itemId = itemService.create(taskId,
            new CreateItemCmd(unitId, BRANCH, "单 DE 重部署接口", PLAN_DATE));
    }

    @AfterEach
    void clearUserContext() {
        TestUserContext.clear();
    }

    @Test
    void redeploy_requires_authentication_40009() throws Exception {
        PublishResult published = givenAFailedProductionDeployment();

        mvc.perform(post(redeployPath(published.publishNo(), failedTask(published)))
                .header("Content-Type", "application/json"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));
    }

    @Test
    void redeploy_returns_the_new_execution_read_model() throws Exception {
        PublishResult published = givenAFailedProductionDeployment();

        mvc.perform(post(redeployPath(published.publishNo(), failedTask(published)))
                .header(USER_HEADER, OPS))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.publishNo").value(published.publishNo()))
            .andExpect(jsonPath("$.data.pipelineNo").isNotEmpty())
            .andExpect(jsonPath("$.data.fromNode").value("PROD_DEPLOY"))
            .andExpect(jsonPath("$.data.batchNo").value(2))
            .andExpect(jsonPath("$.data.targetDeIds.length()").value(2))
            .andExpect(jsonPath("$.data.targetDeName").value("p6g-ct-prod-b"));

        Integer instances = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pipeline_instance", Integer.class);
        assertThat(instances).isEqualTo(2);
    }

    @Test
    void redeploy_of_an_ineligible_task_keeps_its_40001() throws Exception {
        PublishResult published = givenAFailedProductionDeployment();
        long task = failedTask(published);
        jdbc.update("UPDATE node_task SET status = 'SUCCESS' WHERE id = ?", task);

        mvc.perform(post(redeployPath(published.publishNo(), task))
                .header(USER_HEADER, OPS))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void logs_requires_authentication_40009() throws Exception {
        PublishResult published = givenAFailedProductionDeployment();

        mvc.perform(get(redeployPath(published.publishNo(), failedTask(published)).replace(
                "/redeploy", "/logs")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009));
    }

    @Test
    void logs_returns_one_page_of_engine_text() throws Exception {
        PublishResult published = givenAFailedProductionDeployment();
        long task = failedTask(published);
        jdbc.update("UPDATE node_task SET external_id = 'ext-ct-deploy-1' WHERE id = ?", task);

        mvc.perform(get(redeployPath(published.publishNo(), task).replace("/redeploy", "/logs"))
                .header(USER_HEADER, OPS))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.content").value("ct-line-1\nct-line-2\n"))
            .andExpect(jsonPath("$.data.truncated").value(false))
            .andExpect(jsonPath("$.data.offset").value(0))
            .andExpect(jsonPath("$.data.limit").value(8000));
    }

    // ------------------------------------------------------------------ fixtures and helpers

    /** Publishes the item and turns the production deployment into the failure under test. */
    private PublishResult givenAFailedProductionDeployment() {
        PublishResult published = publishService.publish(itemId);
        long instanceId = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class,
            published.pipelineNo());
        for (String node : new String[] {"BUILD", "TEST_DEPLOY", "VERIFY"}) {
            jdbc.update("UPDATE pipeline_node SET status = 'SUCCESS' WHERE instance_id = ?"
                + " AND node_type = ?", instanceId, node);
        }
        jdbc.update("UPDATE pipeline_node SET status = 'FAILED' WHERE instance_id = ?"
            + " AND node_type = 'PROD_DEPLOY'", instanceId);
        jdbc.update("UPDATE pipeline_instance SET status = 'FAILED', image_digest = ?,"
            + " commit_sha = ? WHERE id = ?", OLD_DIGEST, BASE, instanceId);
        jdbc.update("UPDATE release_item SET status = 'FAILED', base_commit = ? WHERE id = ?",
            BASE, itemId);
        jdbc.update("UPDATE release_order SET status = 'FAILED'");
        jdbc.update("UPDATE node_task SET status = 'SUCCESS' WHERE instance_id = ?"
            + " AND node_type = 'PROD_DEPLOY' AND batch_no = 1", instanceId);
        jdbc.update("UPDATE node_task SET status = 'FAILED' WHERE instance_id = ?"
            + " AND node_type = 'PROD_DEPLOY' AND batch_no = 2", instanceId);
        assertThat(unitLockService.release(unitId, instanceId)).isTrue();
        return published;
    }

    private long failedTask(PublishResult published) {
        long instanceId = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class,
            published.pipelineNo());
        Long task = jdbc.queryForObject(
            "SELECT id FROM node_task WHERE instance_id = ? AND node_type = 'PROD_DEPLOY'"
                + " AND batch_no = 2 AND target_id = ?", Long.class, instanceId, batch2DeBId);
        return task == null ? 0L : task;
    }

    private static String redeployPath(String publishNo, long taskId) {
        return "/api/v1/releases/" + publishNo + "/tasks/" + taskId + "/redeploy";
    }

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

    private long givenDe(long unit, String envKind, String name, int batchNo) {
        jdbc.update("INSERT INTO env_de (unit_id, env_kind, de_name, deploy_batch_no,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, NOW(3), NOW(3))",
            unit, envKind, name, batchNo);
        Long id = jdbc.queryForObject(
            "SELECT id FROM env_de WHERE unit_id = ? AND de_name = ?", Long.class, unit, name);
        return id == null ? 0L : id;
    }

    /** A fixed-text engine; only the {@code logs} SPI is reachable in this test. */
    static class ControllerStubEngine implements CicdEngine {

        @Override
        public String name() {
            return "CT-STUB";
        }

        @Override
        public String logs(String externalId, JobKind kind, int offset, int limit) {
            return "ct-line-1\nct-line-2\n";
        }

        @Override
        public TriggerResult startCheck(GateCheckCmd cmd) {
            throw new UnsupportedOperationException("no gate start in the controller test");
        }

        @Override
        public TriggerResult triggerBuild(BuildCmd cmd) {
            throw new UnsupportedOperationException("no build in the controller test");
        }

        @Override
        public TriggerResult triggerDeploy(DeployCmd cmd) {
            throw new UnsupportedOperationException("no deploy in the controller test");
        }

        @Override
        public EngineResult query(String externalId, JobKind kind) {
            throw new UnsupportedOperationException("no query in the controller test");
        }

        @Override
        public void cancel(String externalId) {
            throw new UnsupportedOperationException("no cancel in the controller test");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ControllerStubEngineConfig {

        @Bean
        @Primary
        ControllerStubEngine controllerStubEngine() {
            return new ControllerStubEngine();
        }
    }
}
