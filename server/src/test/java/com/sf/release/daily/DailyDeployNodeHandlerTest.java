package com.sf.release.daily;

import com.sf.release.application.DailyReleaseService;
import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.common.security.Permissions;
import com.sf.release.domain.engine.BuildCmd;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.DeployCmd;
import com.sf.release.domain.engine.EngineException;
import com.sf.release.domain.engine.EngineResult;
import com.sf.release.domain.engine.JobKind;
import com.sf.release.domain.engine.TriggerResult;
import com.sf.release.domain.pipeline.NodeHandler;
import com.sf.release.domain.pipeline.NodeHandlerRegistry;
import com.sf.release.domain.pipeline.NodeTaskCompletionService;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineOrchestrator;
import com.sf.release.domain.pipeline.handler.DailyDeployNodeHandler;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.pipeline.port.TransactionRunner;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code DAILY_DEPLOY} handler against the real test schema with a stub {@link CicdEngine}
 * (P5-A design §4, §7 item 5).
 *
 * <p>Entering the node triggers <em>every</em> target once ({@code DAILY} DEs, or the {@code TEST}
 * DEs when the unit has none), with the daily deploy plan ({@code DAILY_DEPLOY} binding, else
 * {@code TEST_DEPLOY}) and the image the daily build reported. A duplicate entry triggers nothing
 * twice; all targets successful completes the node and the instance; one engine failure fails the
 * task, the node and the instance with the reason persisted, while a target already triggered keeps
 * its row (a daily release never rolls back a deploy it sent).
 *
 * <p>The test drives the pipeline through the real {@link PipelineOrchestrator}: the {@code BUILD}
 * node is marked successful directly, so the assertion is about the node transition into
 * {@code DAILY_DEPLOY}, not about a stubbed build.
 */
@SpringBootTest
@ActiveProfiles("test")
class DailyDeployNodeHandlerTest {

    private static final String OWNER = "01388007";
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 25);
    private static final String BRANCH = "release/202611";
    private static final String BUILD_PLAN = "P-BUILD-DAILY";
    private static final String DAILY_PLAN = "P-DAILY-DEPLOY";
    private static final String TEST_PLAN = "P-TEST-DEPLOY";
    private static final String DIGEST = "sha256:daily-handler";
    private static final String DAILY_DE_SIT = "daily-sit";
    private static final String DAILY_DE_UAT = "daily-uat";
    private static final String TEST_DE = "daily-test";

    @Autowired ItemAppService itemService;
    @Autowired DailyReleaseService dailyService;
    @Autowired PipelineOrchestrator orchestrator;
    @Autowired NodeHandlerRegistry handlers;
    @Autowired PipelineRepository repository;
    @Autowired TransactionRunner transactionRunner;
    @Autowired NodeTaskCompletionService completionService;
    @Autowired JdbcTemplate jdbc;

    private StubEngine engine;

    private long unitId;
    private long itemId;
    private long dailyDeSit;
    private long dailyDeUat;
    private long testDe;

    @BeforeEach
    void setUp() {
        TestUserContext.as(OWNER, Set.of("ADMIN"), Set.of(Permissions.RELEASE_DAILY));
        clearSchema();

        givenUser(OWNER);
        long subsystemId = givenSubsystem("SCC-DAILY-HANDLER");
        long taskId = givenTask(subsystemId, "202611-DAILY-H");
        unitId = givenUnit(subsystemId, "daily-handler-api");
        givenBinding(unitId, "BUILD", BUILD_PLAN);
        givenBinding(unitId, "DAILY_DEPLOY", DAILY_PLAN);
        givenBinding(unitId, "TEST_DEPLOY", TEST_PLAN);
        dailyDeSit = givenDe(unitId, "DAILY", DAILY_DE_SIT, 0);
        dailyDeUat = givenDe(unitId, "DAILY", DAILY_DE_UAT, 2);
        testDe = givenDe(unitId, "TEST", TEST_DE, 0);
        itemId = itemService.create(taskId, new CreateItemCmd(unitId, BRANCH, "日常部署", PLAN_DATE));

        engine = new StubEngine();
        handlers.clear();
        handlers.register(new DailyDeployNodeHandler(engine, repository, transactionRunner,
            completionService));
    }

    @AfterEach
    void tearDown() {
        TestUserContext.clear();
        handlers.clear();
    }

    // ------------------------------------------------------------------ fan-out

    @Test
    void entering_daily_deploy_triggers_every_daily_target_once_with_the_built_image() {
        long instanceId = enterDailyDeploy();

        assertThat(engine.deployCalls)
            .as("one trigger per DAILY DE, and none for the TEST DE")
            .hasSize(2);
        assertThat(engine.triggeredDeNames()).containsExactlyInAnyOrder(DAILY_DE_SIT, DAILY_DE_UAT);

        DeployCmd sit = engine.deployCallTo(DAILY_DE_SIT).cmd();
        assertThat(sit.unitId()).isEqualTo(unitId);
        assertThat(sit.taskId()).as("the enabled DAILY_DEPLOY binding is the deploy plan")
            .isEqualTo(DAILY_PLAN);
        assertThat(sit.envKind()).isEqualTo("DAILY");
        assertThat(sit.imageDigest())
            .as("the image the daily BUILD reported is deployed as-is")
            .isEqualTo(DIGEST);
        assertThat(sit.pipelineNo()).isNotBlank();
        assertThat(sit.attempt()).isZero();

        assertThat(taskStatus(instanceId, dailyDeSit)).isEqualTo("WAITING_EXTERNAL");
        assertThat(taskStatus(instanceId, dailyDeUat)).isEqualTo("WAITING_EXTERNAL");
        assertThat(externalId(instanceId, dailyDeSit))
            .isNotBlank().isNotEqualTo(externalId(instanceId, dailyDeUat));
        assertThat(externalUrl(instanceId, dailyDeSit)).isNotBlank();
        assertThat(deadline(instanceId, dailyDeSit))
            .as("a daily deploy waits 60 minutes (design §4)")
            .isBetween(Instant.now().plus(Duration.ofMinutes(59)),
                Instant.now().plus(Duration.ofMinutes(61)));
        assertThat(nodeStatus(instanceId, "DAILY_DEPLOY")).isEqualTo("RUNNING");
        assertThat(instanceStatus(instanceId)).isEqualTo("RUNNING");
        assertThat(instanceCurrentNode(instanceId)).isEqualTo("DAILY_DEPLOY");
    }

    @Test
    void a_duplicate_entry_and_a_running_advance_never_trigger_a_target_again() {
        long instanceId = enterDailyDeploy();
        assertThat(engine.deployCalls).hasSize(2);

        // A re-entry handed the (still) committed aggregate must not trigger anything again.
        handlers.handler(NodeType.DAILY_DEPLOY).start(repository.loadInstance(instanceId));
        assertThat(engine.deployCalls).hasSize(2);

        orchestrator.advance(instanceId);
        assertThat(engine.deployCalls)
            .as("advancing a RUNNING DAILY_DEPLOY node must not retrigger a target")
            .hasSize(2);
    }

    @Test
    void a_target_whose_status_is_not_pending_is_not_triggered_again() {
        long instanceId = enterDailyDeploy();
        assertThat(engine.deployCalls).hasSize(2);

        // Simulate a crash between the two triggers: one target was left PENDING with no job.
        jdbc.update("UPDATE node_task SET status = 'PENDING', external_id = NULL,"
            + " external_url = NULL, deadline_at = NULL, started_at = NULL"
            + " WHERE instance_id = ? AND target_id = ?", instanceId, dailyDeUat);
        handlers.handler(NodeType.DAILY_DEPLOY).start(repository.loadInstance(instanceId));

        assertThat(engine.triggeredDeNames())
            .containsExactly(DAILY_DE_SIT, DAILY_DE_UAT, DAILY_DE_UAT);
    }

    // ------------------------------------------------------------------ completion and failure

    @Test
    void all_targets_successful_completes_the_node_and_the_instance() {
        long instanceId = enterDailyDeploy();

        orchestrator.onTaskCompleted(taskId(instanceId, dailyDeSit), NodeTaskStatus.SUCCESS,
            null, null);
        assertThat(nodeStatus(instanceId, "DAILY_DEPLOY"))
            .as("one successful target does not complete a node with a second running target")
            .isEqualTo("RUNNING");
        assertThat(instanceStatus(instanceId)).isEqualTo("RUNNING");

        orchestrator.onTaskCompleted(taskId(instanceId, dailyDeUat), NodeTaskStatus.SUCCESS,
            null, null);

        assertThat(taskStatus(instanceId, dailyDeSit)).isEqualTo("SUCCESS");
        assertThat(taskStatus(instanceId, dailyDeUat)).isEqualTo("SUCCESS");
        assertThat(nodeStatus(instanceId, "DAILY_DEPLOY")).isEqualTo("SUCCESS");
        assertThat(nodeStatus(instanceId, "BUILD")).isEqualTo("SUCCESS");
        assertThat(instanceStatus(instanceId))
            .as("DAILY_DEPLOY is the last node of the daily pipeline")
            .isEqualTo("DONE");
        assertThat(instanceEndAt(instanceId)).isNotNull();
    }

    @Test
    void an_engine_failure_fails_the_task_node_and_instance_and_persists_the_reason() {
        engine.failOn(DAILY_DE_UAT, EngineException.unavailable("engine down"));

        long instanceId = enterDailyDeploy();

        assertThat(engine.deployCalls).hasSize(2);
        assertThat(taskStatus(instanceId, dailyDeUat)).isEqualTo("FAILED");
        assertThat(resultSummary(instanceId, dailyDeUat))
            .contains("ENGINE_UNAVAILABLE").contains("engine down");
        assertThat(taskStatus(instanceId, dailyDeSit))
            .as("a target already triggered keeps its row; a daily failure deploys no rollback")
            .isEqualTo("WAITING_EXTERNAL");
        assertThat(nodeStatus(instanceId, "DAILY_DEPLOY")).isEqualTo("FAILED");
        assertThat(instanceStatus(instanceId)).isEqualTo("FAILED");
        assertThat(instanceStatusReason(instanceId))
            .as("the operator must be able to read why the daily release failed")
            .contains("DAILY_DEPLOY").contains("engine down");
    }

    @Test
    void a_missing_deploy_binding_fails_the_tasks_before_any_engine_call() {
        long instanceId = dailyService.start(itemId).instanceId();
        markBuildSucceeded(instanceId, DIGEST);
        disableBinding(unitId, "DAILY_DEPLOY");
        disableBinding(unitId, "TEST_DEPLOY");

        orchestrator.advance(instanceId);

        assertThat(engine.deployCalls).isEmpty();
        assertThat(taskStatus(instanceId, dailyDeSit)).isEqualTo("FAILED");
        assertThat(taskStatus(instanceId, dailyDeUat)).isEqualTo("FAILED");
        assertThat(resultSummary(instanceId, dailyDeSit)).contains("UNIT_NOT_CONFIGURED");
        assertThat(nodeStatus(instanceId, "DAILY_DEPLOY")).isEqualTo("FAILED");
        assertThat(instanceStatus(instanceId)).isEqualTo("FAILED");
    }

    // ------------------------------------------------------------------ fallbacks

    @Test
    void the_deploy_plan_falls_back_to_the_test_binding_while_the_targets_stay_daily() {
        long instanceId = dailyService.start(itemId).instanceId();
        markBuildSucceeded(instanceId, DIGEST);
        disableBinding(unitId, "DAILY_DEPLOY");

        orchestrator.advance(instanceId);

        assertThat(engine.deployCalls).hasSize(2);
        assertThat(engine.triggeredDeNames()).containsExactlyInAnyOrder(DAILY_DE_SIT, DAILY_DE_UAT);
        DeployCmd cmd = engine.deployCallTo(DAILY_DE_SIT).cmd();
        assertThat(cmd.taskId()).as("C24: the TEST_DEPLOY binding is the fallback deploy plan")
            .isEqualTo(TEST_PLAN);
        assertThat(cmd.envKind()).as("the DE fallback is independent of the binding fallback")
            .isEqualTo("DAILY");
    }

    @Test
    void a_unit_without_a_daily_de_deploys_to_its_test_de() {
        long testOnlyUnit = givenUnit(givenSubsystem("SCC-DAILY-TESTO"), "daily-test-only");
        givenBinding(testOnlyUnit, "BUILD", BUILD_PLAN);
        givenBinding(testOnlyUnit, "TEST_DEPLOY", TEST_PLAN);
        long testOnlyDe = givenDe(testOnlyUnit, "TEST", TEST_DE, 0);
        long testOnlyTask = givenTask(subsystemOf(testOnlyUnit), "202611-DAILY-TO");
        long testOnlyItem = itemService.create(testOnlyTask,
            new CreateItemCmd(testOnlyUnit, BRANCH, "日常回退", PLAN_DATE));

        long instanceId = dailyService.start(testOnlyItem).instanceId();
        markBuildSucceeded(instanceId, DIGEST);
        orchestrator.advance(instanceId);

        assertThat(engine.deployCalls).as("one target: the unit's TEST DE").hasSize(1);
        DeployCmd cmd = engine.deployCallTo(TEST_DE).cmd();
        assertThat(cmd.envKind()).isEqualTo("TEST");
        assertThat(cmd.taskId()).isEqualTo(TEST_PLAN);
        assertThat(taskStatus(instanceId, testOnlyDe)).isEqualTo("WAITING_EXTERNAL");
    }

    // ------------------------------------------------------------------ pipeline driving

    /**
     * Starts a daily instance through the real service (auto-start is off in the test profile),
     * marks the BUILD node successful and advances once, so the orchestrator enters
     * {@code DAILY_DEPLOY} and calls the handler.
     */
    private long enterDailyDeploy() {
        long instanceId = dailyService.start(itemId).instanceId();
        markBuildSucceeded(instanceId, DIGEST);
        orchestrator.advance(instanceId);
        return instanceId;
    }

    private void markBuildSucceeded(long instanceId, String digest) {
        jdbc.update("UPDATE node_task SET status = 'SUCCESS', started_at = NOW(3),"
            + " finished_at = NOW(3) WHERE instance_id = ? AND node_type = 'BUILD'", instanceId);
        jdbc.update("UPDATE pipeline_node SET status = 'RUNNING', started_at = NOW(3)"
            + " WHERE instance_id = ? AND node_type = 'BUILD'", instanceId);
        jdbc.update("UPDATE pipeline_instance SET status = 'RUNNING', start_at = NOW(3),"
            + " current_node = 'BUILD', image_digest = ? WHERE id = ?", digest, instanceId);
    }

    private long dailyDeUAT() {
        return dailyDeUat;
    }

    // ------------------------------------------------------------------ row read-back

    private String instanceStatus(long instanceId) {
        return jdbc.queryForObject("SELECT status FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private String instanceCurrentNode(long instanceId) {
        return jdbc.queryForObject("SELECT current_node FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private String instanceStatusReason(long instanceId) {
        return jdbc.queryForObject("SELECT status_reason FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private Instant instanceEndAt(long instanceId) {
        java.sql.Timestamp value = jdbc.queryForObject(
            "SELECT end_at FROM pipeline_instance WHERE id = ?", java.sql.Timestamp.class,
            instanceId);
        return value == null ? null : value.toInstant();
    }

    private String nodeStatus(long instanceId, String nodeType) {
        return jdbc.queryForObject("SELECT status FROM pipeline_node"
            + " WHERE instance_id = ? AND node_type = ?", String.class, instanceId, nodeType);
    }

    private long taskId(long instanceId, long targetId) {
        Long id = jdbc.queryForObject("SELECT id FROM node_task WHERE instance_id = ?"
            + " AND node_type = 'DAILY_DEPLOY' AND target_id = ?", Long.class, instanceId,
            targetId);
        return id == null ? 0L : id;
    }

    private String taskStatus(long instanceId, long targetId) {
        return jdbc.queryForObject("SELECT status FROM node_task WHERE instance_id = ?"
            + " AND node_type = 'DAILY_DEPLOY' AND target_id = ?", String.class, instanceId,
            targetId);
    }

    private String resultSummary(long instanceId, long targetId) {
        return jdbc.queryForObject("SELECT result_summary FROM node_task WHERE instance_id = ?"
            + " AND node_type = 'DAILY_DEPLOY' AND target_id = ?", String.class, instanceId,
            targetId);
    }

    private String externalId(long instanceId, long targetId) {
        return jdbc.queryForObject("SELECT external_id FROM node_task WHERE instance_id = ?"
            + " AND node_type = 'DAILY_DEPLOY' AND target_id = ?", String.class, instanceId,
            targetId);
    }

    private String externalUrl(long instanceId, long targetId) {
        return jdbc.queryForObject("SELECT external_url FROM node_task WHERE instance_id = ?"
            + " AND node_type = 'DAILY_DEPLOY' AND target_id = ?", String.class, instanceId,
            targetId);
    }

    private Instant deadline(long instanceId, long targetId) {
        java.sql.Timestamp value = jdbc.queryForObject(
            "SELECT deadline_at FROM node_task WHERE instance_id = ?"
                + " AND node_type = 'DAILY_DEPLOY' AND target_id = ?",
            java.sql.Timestamp.class, instanceId, targetId);
        return value == null ? null : value.toInstant();
    }

    // ------------------------------------------------------------------ fixtures

    private void clearSchema() {
        for (String table : List.of("audit_log", "notification", "notification_setting",
            "coding_conn", "deploy_unit_lock", "rollback_record", "pipeline_item_failure",
            "node_task", "pipeline_node", "pipeline_instance", "env_de", "coding_task_binding",
            "release_order", "release_item", "release_task_member", "release_task", "deploy_unit",
            "subsystem", "user_scope", "user_role", "app_user")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    private void givenUser(String userId) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3))", userId, "用户" + userId);
    }

    private long givenSubsystem(String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, OWNER);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private long subsystemOf(long unitId) {
        return jdbc.queryForObject("SELECT subsystem_id FROM deploy_unit WHERE id = ?",
            Long.class, unitId);
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
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id,"
                + " framework_template, branch_mode, status, module_path, created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, 'spring-boot', 'STANDARD', 'ACTIVE',"
                + " 'outbound-os/daily', NOW(3), NOW(3))",
            subsystem, name, OWNER);
        Long id = jdbc.queryForObject(
            "SELECT id FROM deploy_unit WHERE subsystem_id = ? AND name = ?", Long.class,
            subsystem, name);
        return id == null ? 0L : id;
    }

    private void givenBinding(long unit, String kind, String codingTaskId) {
        jdbc.update("INSERT INTO coding_task_binding (unit_id, task_kind, task_id, enabled,"
                + " created_at, updated_at) VALUES (?, ?, ?, 1, NOW(3), NOW(3))",
            unit, kind, codingTaskId);
    }

    private void disableBinding(long unit, String kind) {
        jdbc.update("UPDATE coding_task_binding SET enabled = 0 WHERE unit_id = ?"
            + " AND task_kind = ?", unit, kind);
    }

    private long givenDe(long unit, String envKind, String name, int batchNo) {
        jdbc.update("INSERT INTO env_de (unit_id, env_kind, de_name, deploy_batch_no,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, NOW(3), NOW(3))",
            unit, envKind, name, batchNo);
        Long id = jdbc.queryForObject(
            "SELECT id FROM env_de WHERE unit_id = ? AND de_name = ?", Long.class, unit, name);
        return id == null ? 0L : id;
    }

    /** Stub engine: records each deploy trigger and can fail one target by its DE name. */
    private static final class StubEngine implements CicdEngine {

        private final List<DeployCall> deployCalls = new ArrayList<>();
        private final Map<String, RuntimeException> deployFailures = new HashMap<>();

        @Override
        public String name() {
            return "STUB";
        }

        @Override
        public TriggerResult triggerBuild(BuildCmd cmd) {
            throw new UnsupportedOperationException("no build in the DAILY_DEPLOY test");
        }

        @Override
        public TriggerResult triggerDeploy(DeployCmd cmd) {
            deployCalls.add(new DeployCall(cmd));
            RuntimeException failure = deployFailures.get(cmd.deName());
            if (failure != null) {
                throw failure;
            }
            int seq = deployCalls.size();
            return new TriggerResult("daily-dep-" + seq,
                "https://coding.example/daily/deploy/" + seq);
        }

        @Override
        public EngineResult query(String externalId, JobKind kind) {
            throw new UnsupportedOperationException("no query in the DAILY_DEPLOY test");
        }

        @Override
        public String logs(String externalId, JobKind kind, int offset, int limit) {
            throw new UnsupportedOperationException("no logs in the DAILY_DEPLOY test");
        }

        @Override
        public void cancel(String externalId) {
            throw new UnsupportedOperationException("no cancel in the DAILY_DEPLOY test");
        }

        void failOn(String deName, RuntimeException failure) {
            deployFailures.put(deName, failure);
        }

        List<String> triggeredDeNames() {
            return deployCalls.stream().map(call -> call.cmd().deName()).toList();
        }

        DeployCall deployCallTo(String deName) {
            return deployCalls.stream()
                .filter(call -> deName.equals(call.cmd().deName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no deploy trigger for DE " + deName));
        }

        /** One deploy trigger and the command the handler built. */
        record DeployCall(DeployCmd cmd) {
        }
    }
}