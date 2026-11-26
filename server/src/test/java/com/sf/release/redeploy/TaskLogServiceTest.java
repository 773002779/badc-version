package com.sf.release.redeploy;

import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.application.PublishAppService;
import com.sf.release.application.PublishAppService.PublishResult;
import com.sf.release.application.TaskLogService;
import com.sf.release.application.TaskLogService.LogView;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.engine.BuildCmd;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.DeployCmd;
import com.sf.release.domain.engine.EngineException;
import com.sf.release.domain.engine.EngineResult;
import com.sf.release.domain.engine.GateCheckCmd;
import com.sf.release.domain.engine.JobKind;
import com.sf.release.domain.engine.TriggerResult;
import com.sf.release.domain.pipeline.port.GitCommitProbe;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6-G design §4.3: the task-log proxy against a recording fake engine.
 *
 * <p>Pinned here: the task type maps to the engine's job namespace ({@code CODE_CHECK} → BUILD,
 * {@code DEPLOY} → DEPLOY, {@code LIB_PUBLISH} → PUBLISH) and the page is passed through
 * unchanged; a {@code CHECK} task and a task without an {@code external_id} are 40001; a
 * historical-instance task, an unknown publish number/task and out-of-range parameters are
 * 40001/40000; a caller outside the unit scope is 40009; an engine failure keeps its own
 * 50001/50002 code and message; a full page is reported as {@code truncated}.
 *
 * <p>{@code LogsStubEngine} is the {@code @Primary} engine bean, so nothing contacts CODING.
 */
@SpringBootTest
@ActiveProfiles("test")
class TaskLogServiceTest {

    private static final String OWNER = "p6g-log-owner";
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 26);
    private static final String BASE = "a".repeat(40);
    private static final String BRANCH = "release/202611";
    private static final String MODULE = "outbound-os/quota";

    private static final int PARAM_INVALID_CODE = 40000;
    private static final int STATE_NOT_ALLOWED_CODE = 40001;
    private static final int FORBIDDEN_CODE = 40009;

    @Autowired TaskLogService logService;
    @Autowired LogsStubEngine engine;
    @Autowired JdbcTemplate jdbc;
    @Autowired ItemAppService itemService;
    @Autowired PublishAppService publishService;
    @MockBean GitCommitProbe commitProbe;

    private long unitId;
    private long itemId;
    private String publishNo;
    private long instanceId;
    private long codeCheckTask;
    private long deployTask;

    @BeforeEach
    void resetFixtures() {
        TestUserContext.allUnits(OWNER);
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
        jdbc.update("DELETE FROM app_user WHERE id = ?", OWNER);

        givenUser(OWNER);
        long subsystemId = givenSubsystem("SCC-P6G-LOG");
        long taskId = givenTask(subsystemId, "202611-P6G-LOG");
        unitId = givenUnit(subsystemId, "p6g-log-api");
        givenBinding(unitId, "BUILD", "P-BUILD-P6G-LOG");
        givenBinding(unitId, "TEST_DEPLOY", "P-TEST-P6G-LOG");
        givenBinding(unitId, "PROD_DEPLOY", "P-PROD-P6G-LOG");
        givenDe(unitId, "TEST", "p6g-log-sit", 0);
        givenDe(unitId, "PROD", "p6g-log-prod", 1);
        itemId = itemService.create(taskId,
            new CreateItemCmd(unitId, BRANCH, "任务日志代理", PLAN_DATE));

        PublishResult published = publishService.publish(itemId);
        publishNo = published.publishNo();
        instanceId = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class,
            published.pipelineNo());
        codeCheckTask = taskOf("CODE_CHECK");
        deployTask = taskOf("DEPLOY");
        jdbc.update("UPDATE node_task SET external_id = 'ext-code-1' WHERE id = ?", codeCheckTask);
        jdbc.update("UPDATE node_task SET external_id = 'ext-deploy-1' WHERE id = ?", deployTask);

        engine.reset("line-1\nline-2\n", null);
    }

    @AfterEach
    void clearUserContext() {
        TestUserContext.clear();
    }

    @Test
    void a_build_task_is_read_from_the_build_job_namespace() {
        LogView view = logService.logs(publishNo, codeCheckTask, 0, 100);

        assertThat(view.content()).isEqualTo("line-1\nline-2\n");
        assertThat(view.truncated()).isFalse();
        assertThat(view.offset()).isZero();
        assertThat(view.limit()).isEqualTo(100);
        assertThat(engine.calls).containsExactly(
            new EngineCall("ext-code-1", JobKind.BUILD, 0, 100));
    }

    @Test
    void a_deploy_task_is_read_from_the_deploy_job_namespace_with_the_page_untouched() {
        logService.logs(publishNo, deployTask, 500, 10);

        assertThat(engine.calls).containsExactly(
            new EngineCall("ext-deploy-1", JobKind.DEPLOY, 500, 10));
    }

    @Test
    void a_lib_publish_task_is_read_from_the_publish_job_namespace() {
        long publishJob = givenTaskRow("LIB_PUBLISH", "LIB_PUBLISH", 0, "ext-lib-1");

        logService.logs(publishNo, publishJob, 0, 100);

        assertThat(engine.calls).containsExactly(
            new EngineCall("ext-lib-1", JobKind.PUBLISH, 0, 100));
    }

    @Test
    void a_full_page_is_reported_as_truncated_and_a_short_one_is_not() {
        engine.reset("abcd", null);
        assertThat(logService.logs(publishNo, codeCheckTask, 0, 4).truncated())
            .as("a full page may hide more lines")
            .isTrue();

        engine.reset("abc", null);
        assertThat(logService.logs(publishNo, codeCheckTask, 0, 4).truncated()).isFalse();
    }

    @Test
    void a_check_task_has_no_engine_job_and_is_rejected_40001() {
        long checkTask = givenTaskRow("VERIFY", "CHECK", 0, null);

        assertThatThrownBy(() -> logService.logs(publishNo, checkTask, 0, 100))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("没有引擎作业")
            .satisfies(thrown -> assertThat(((BizException) thrown).getError().getCode())
                .isEqualTo(STATE_NOT_ALLOWED_CODE));
        assertThat(engine.calls).isEmpty();
    }

    @Test
    void a_task_without_an_external_id_is_rejected_40001() {
        jdbc.update("UPDATE node_task SET external_id = NULL WHERE id = ?", codeCheckTask);

        assertThatThrownBy(() -> logService.logs(publishNo, codeCheckTask, 0, 100))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("没有记录引擎作业号")
            .satisfies(thrown -> assertThat(((BizException) thrown).getError().getCode())
                .isEqualTo(STATE_NOT_ALLOWED_CODE));
        assertThat(engine.calls).isEmpty();
    }

    @Test
    void a_historical_instance_task_is_rejected_40001() {
        long orderId = jdbc.queryForObject(
            "SELECT order_id FROM pipeline_instance WHERE id = ?", Long.class, instanceId);
        jdbc.update("INSERT INTO pipeline_instance (order_id, pipeline_no, exec_type,"
                + " trigger_type, current_node, status, created_at)"
                + " VALUES (?, 'PL-P6G-LOG-NEWER', 'ONLINE', 'RERUN_FROM_NODE', 'PROD_DEPLOY',"
                + " 'PENDING', NOW(3))", orderId);

        assertThatThrownBy(() -> logService.logs(publishNo, codeCheckTask, 0, 100))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("历史实例")
            .satisfies(thrown -> assertThat(((BizException) thrown).getError().getCode())
                .isEqualTo(STATE_NOT_ALLOWED_CODE));
        assertThat(engine.calls).isEmpty();
    }

    @Test
    void out_of_range_offset_and_limit_are_rejected_40000() {
        for (int[] page : new int[][] {{-1, 100}, {0, 0}, {0, 8001}}) {
            assertThatThrownBy(() -> logService.logs(publishNo, codeCheckTask, page[0], page[1]))
                .isInstanceOf(BizException.class)
                .satisfies(thrown -> assertThat(((BizException) thrown).getError().getCode())
                    .isEqualTo(PARAM_INVALID_CODE));
        }
        assertThat(engine.calls).isEmpty();
    }

    @Test
    void unknown_publish_and_task_are_rejected_40000() {
        assertThatThrownBy(() -> logService.logs("PUB-NOT-EXIST", codeCheckTask, 0, 100))
            .isInstanceOf(BizException.class)
            .satisfies(thrown -> assertThat(((BizException) thrown).getError().getCode())
                .isEqualTo(PARAM_INVALID_CODE));
        assertThatThrownBy(() -> logService.logs(publishNo, 999_999L, 0, 100))
            .isInstanceOf(BizException.class)
            .satisfies(thrown -> assertThat(((BizException) thrown).getError().getCode())
                .isEqualTo(PARAM_INVALID_CODE));
        assertThat(engine.calls).isEmpty();
    }

    @Test
    void a_caller_outside_the_unit_scope_is_rejected_40009() {
        TestUserContext.scoped(OWNER, unitId + 1);

        assertThatThrownBy(() -> logService.logs(publishNo, codeCheckTask, 0, 100))
            .isInstanceOf(BizException.class)
            .satisfies(thrown -> assertThat(((BizException) thrown).getError().getCode())
                .isEqualTo(FORBIDDEN_CODE));
        assertThat(engine.calls).isEmpty();
    }

    @Test
    void an_engine_failure_keeps_its_own_code_and_message() {
        engine.reset("line-1", EngineException.unavailable("CODING 引擎不可达（已脱敏）"));

        assertThatThrownBy(() -> logService.logs(publishNo, codeCheckTask, 0, 100))
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("CODING 引擎不可达")
            .satisfies(thrown -> assertThat(((BizException) thrown).getError().getCode())
                .isEqualTo(BizError.ENGINE_UNAVAILABLE.getCode()));
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private long taskOf(String taskType) {
        Long id = jdbc.queryForObject(
            "SELECT id FROM node_task WHERE instance_id = ? AND task_type = ?"
                + " ORDER BY id LIMIT 1", Long.class, instanceId, taskType);
        return id == null ? 0L : id;
    }

    private long givenTaskRow(String nodeType, String taskType, long targetId, String externalId) {
        jdbc.update("INSERT INTO node_task (instance_id, node_type, task_type, batch_no,"
                + " target_id, status, attempt, external_id)"
                + " VALUES (?, ?, ?, 0, ?, 'SKIPPED', 0, ?)",
            instanceId, nodeType, taskType, targetId, externalId);
        Long id = jdbc.queryForObject(
            "SELECT id FROM node_task WHERE instance_id = ? AND node_type = ?"
                + " AND task_type = ? ORDER BY id DESC LIMIT 1",
            Long.class, instanceId, nodeType, taskType);
        return id == null ? 0L : id;
    }

    private void givenUser(String userId) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, 'ACTIVE', NOW(3))",
            userId, "用户" + userId);
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

    /** One recorded {@link CicdEngine#logs} call. */
    record EngineCall(String externalId, JobKind kind, int offset, int limit) {
    }

    /**
     * A fake engine that records every {@code logs()} call and returns either a preset text or a
     * preset failure. The remaining SPI methods are unreachable: {@code auto-start} is off in the
     * test profile, so no node handler runs.
     */
    static class LogsStubEngine implements CicdEngine {

        final List<EngineCall> calls = new ArrayList<>();
        private String nextLogs = "";
        private EngineException nextFailure;

        void reset(String nextLogs, EngineException nextFailure) {
            calls.clear();
            this.nextLogs = nextLogs;
            this.nextFailure = nextFailure;
        }

        @Override
        public String name() {
            return "LOGS-STUB";
        }

        @Override
        public String logs(String externalId, JobKind kind, int offset, int limit) {
            calls.add(new EngineCall(externalId, kind, offset, limit));
            if (nextFailure != null) {
                throw nextFailure;
            }
            return nextLogs;
        }

        @Override
        public TriggerResult startCheck(GateCheckCmd cmd) {
            throw new UnsupportedOperationException("no gate start in the log proxy test");
        }

        @Override
        public TriggerResult triggerBuild(BuildCmd cmd) {
            throw new UnsupportedOperationException("no build in the log proxy test");
        }

        @Override
        public TriggerResult triggerDeploy(DeployCmd cmd) {
            throw new UnsupportedOperationException("no deploy in the log proxy test");
        }

        @Override
        public EngineResult query(String externalId, JobKind kind) {
            throw new UnsupportedOperationException("no query in the log proxy test");
        }

        @Override
        public void cancel(String externalId) {
            throw new UnsupportedOperationException("no cancel in the log proxy test");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LogsStubEngineConfig {

        @Bean
        @Primary
        LogsStubEngine logsStubEngine() {
            return new LogsStubEngine();
        }
    }
}
