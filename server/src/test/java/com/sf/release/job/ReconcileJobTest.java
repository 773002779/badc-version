package com.sf.release.job;

import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.application.PublishAppService;
import com.sf.release.domain.engine.BuildCmd;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.DeployCmd;
import com.sf.release.domain.engine.EngineException;
import com.sf.release.domain.engine.EngineResult;
import com.sf.release.domain.engine.EngineStatus;
import com.sf.release.domain.engine.JobKind;
import com.sf.release.domain.engine.TriggerResult;
import com.sf.release.domain.pipeline.NodeHandler;
import com.sf.release.domain.pipeline.NodeHandlerRegistry;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineOrchestrator;
import com.sf.release.domain.pipeline.port.DistributedLock;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.pipeline.port.TransactionRunner;
import com.sf.release.infra.engine.EngineRegistry;
import com.sf.release.infra.job.ReconcileJob;
import com.sf.release.infra.repo.NodeTaskMapper;
import com.sf.release.infra.repo.PipelineInstanceMapper;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 30s reconcile loop (tech design §6.3): stale {@code WAITING_EXTERNAL} tasks are queried
 * against the engine, terminal results are applied through the orchestrator, three consecutive
 * query failures time the task out, and RUNNING instances whose node is already complete are
 * advanced again (the durable backstop for a callback consumed while the pipe lock was busy,
 * or a crash between the completion commit and the handler).
 *
 * <p>No scheduler runs in the test profile (the quartz thread would race the shared test
 * schema), so {@link ReconcileJob#run()} is invoked directly and deterministically.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class ReconcileJobTest {

    private static final String OWNER = "01388007";
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 25);

    @Autowired JdbcTemplate jdbc;
    @Autowired ItemAppService itemService;
    @Autowired PublishAppService publishService;
    @Autowired PipelineOrchestrator orchestrator;
    @Autowired NodeHandlerRegistry handlers;
    @Autowired PipelineRepository repository;
    @Autowired TransactionRunner transactionRunner;
    @Autowired NodeTaskMapper taskMapper;
    @Autowired PipelineInstanceMapper instanceMapper;
    @Autowired DistributedLock distributedLock;

    private final Map<NodeType, AtomicInteger> starts = new EnumMap<>(NodeType.class);
    private StubEngine engine;
    private ReconcileJob job;
    private long instanceId;

    @BeforeEach
    void setUp() {
        TestUserContext.allUnits(OWNER);
        jdbc.update("DELETE FROM engine_event_dedup");
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
        long subsystemId = givenSubsystem("SCC-QUOTA");
        long releaseTaskId = givenTask(subsystemId, "202611-V6.10.12");
        long unitId = givenUnit(subsystemId, "quota-api");
        givenBinding(unitId, "BUILD", "P-BUILD-QUOTA");
        givenBinding(unitId, "TEST_DEPLOY", "P-TEST-QUOTA");
        givenBinding(unitId, "PROD_DEPLOY", "P-PROD-QUOTA");
        givenDe(unitId, "TEST", "quota-sit", 0);
        long itemId = itemService.create(releaseTaskId,
            new CreateItemCmd(unitId, "release/202611", "对账用例", PLAN_DATE));
        PublishAppService.PublishResult published = publishService.publish(itemId);
        instanceId = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class,
            published.pipelineNo());

        handlers.clear();
        starts.clear();
        registerCountingHandlers();
        orchestrator.advance(instanceId);

        engine = new StubEngine();
        job = new ReconcileJob(distributedLock, new EngineRegistry(List.of(engine)), "CODING",
            repository, orchestrator, taskMapper, instanceMapper);
    }

    @AfterEach
    void tearDown() {
        TestUserContext.clear();
        handlers.clear();
    }

    // ------------------------------------------------------------------ stale task queries

    @Test
    void reconcile_queries_a_stale_waiting_task_once_and_applies_the_terminal_result() {
        long taskId = waitingBuildTask(Duration.ofMinutes(5), Duration.ofHours(1));
        engine.result = new EngineResult(EngineStatus.SUCCESS, null, "SUCCESS");

        job.run();

        assertThat(engine.queries).isEqualTo(1);
        assertThat(engine.lastExternalId).isEqualTo("ext-1");
        assertThat(engine.lastKind)
            .as("the kind is derived from the task type so the right query endpoint is used")
            .isEqualTo(JobKind.BUILD);
        assertThat(taskStatus(taskId)).isEqualTo("SUCCESS");
        assertThat(queryFailures(taskId)).isZero();
    }

    @Test
    void reconcile_does_not_query_a_task_younger_than_ninety_seconds() {
        long taskId = waitingBuildTask(Duration.ZERO, Duration.ofHours(1));
        engine.result = new EngineResult(EngineStatus.SUCCESS, null, "SUCCESS");

        job.run();

        assertThat(engine.queries).isZero();
        assertThat(taskStatus(taskId)).isEqualTo("WAITING_EXTERNAL");
    }

    @Test
    void reconcile_keeps_a_running_job_waiting_and_refreshes_its_poll_time() {
        long taskId = waitingBuildTask(Duration.ofMinutes(5), Duration.ofHours(1));
        engine.result = new EngineResult(EngineStatus.RUNNING, null, "RUNNING");

        job.run();
        Timestamp refreshed = jdbc.queryForObject(
            "SELECT updated_at FROM node_task WHERE id = ?", Timestamp.class, taskId);
        job.run();

        assertThat(taskStatus(taskId)).isEqualTo("WAITING_EXTERNAL");
        assertThat(refreshed)
            .as("a successful non-terminal query moves updated_at forward")
            .isNotNull();
        assertThat(engine.queries)
            .as("the refreshed row is younger than 90s and is not polled again immediately")
            .isEqualTo(1);
    }

    // ------------------------------------------------------------------ failure escalation

    @Test
    void three_consecutive_query_failures_time_the_task_out(CapturedOutput output) {
        long taskId = waitingBuildTask(Duration.ofMinutes(5), Duration.ofHours(1));
        engine.failure = EngineException.unavailable("engine unreachable");

        job.run();
        job.run();
        assertThat(taskStatus(taskId)).isEqualTo("WAITING_EXTERNAL");
        assertThat(queryFailures(taskId)).isEqualTo(2);

        job.run();

        assertThat(queryFailures(taskId)).isEqualTo(3);
        assertThat(taskStatus(taskId))
            .as("three consecutive failures escalate to TIMEOUT")
            .isEqualTo("TIMEOUT");
        assertThat(jdbc.queryForObject(
            "SELECT instance_id FROM node_task WHERE id = ?", Long.class, taskId))
            .isEqualTo(instanceId);
        assertThat(instanceStatus()).isEqualTo("FAILED");
        assertThat(output.getAll())
            .as("the escalation is visible in the log")
            .contains("WARN")
            .contains("TIMEOUT");
    }

    @Test
    void a_successful_query_resets_the_consecutive_failure_counter() {
        long taskId = waitingBuildTask(Duration.ofMinutes(5), Duration.ofHours(1));
        engine.failure = EngineException.unavailable("engine unreachable");
        job.run();
        assertThat(queryFailures(taskId)).isEqualTo(1);

        engine.failure = null;
        engine.result = new EngineResult(EngineStatus.RUNNING, null, "RUNNING");
        job.run();

        assertThat(queryFailures(taskId)).isZero();
        assertThat(taskStatus(taskId)).isEqualTo("WAITING_EXTERNAL");
    }

    @Test
    void reconcile_times_out_a_job_whose_deadline_passed_even_when_it_still_reports_running() {
        long taskId = waitingBuildTask(Duration.ZERO, Duration.ofSeconds(-1));
        engine.result = new EngineResult(EngineStatus.RUNNING, null, "RUNNING");

        job.run();

        assertThat(engine.queries).isEqualTo(1);
        assertThat(taskStatus(taskId))
            .as("deadline_at is the engine-facing timeout (design §5.4)")
            .isEqualTo("TIMEOUT");
    }

    // ------------------------------------------------------------------ advance repair

    @Test
    void reconcile_repairs_an_advance_dropped_after_all_tasks_succeeded() {
        for (long taskId : taskIds(NodeType.BUILD)) {
            jdbc.update("UPDATE node_task SET status = 'SUCCESS', finished_at = NOW(3)"
                + " WHERE id = ?", taskId);
        }
        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("RUNNING");
        assertThat(starts(NodeType.TEST_DEPLOY)).isZero();

        job.run();

        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("SUCCESS");
        assertThat(nodeStatus(NodeType.TEST_DEPLOY)).isEqualTo("RUNNING");
        assertThat(currentNode()).isEqualTo("TEST_DEPLOY");
        assertThat(starts(NodeType.TEST_DEPLOY))
            .as("the dropped advance is replayed exactly once")
            .isEqualTo(1);

        job.run();
        assertThat(starts(NodeType.TEST_DEPLOY))
            .as("the repair is idempotent")
            .isEqualTo(1);
    }

    @Test
    void reconcile_does_not_advance_an_instance_whose_node_is_not_complete() {
        job.run();

        assertThat(starts(NodeType.TEST_DEPLOY)).isZero();
        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("RUNNING");
    }

    @Test
    void reconcile_skips_verification_nodes_that_only_have_check_tasks() {
        // Reaching VERIFY requires the BUILD and TEST_DEPLOY nodes to finish.
        for (long taskId : taskIds(NodeType.BUILD)) {
            jdbc.update("UPDATE node_task SET status = 'SUCCESS' WHERE id = ?", taskId);
        }
        orchestrator.advance(instanceId);
        assertThat(nodeStatus(NodeType.TEST_DEPLOY)).isEqualTo("RUNNING");
        for (long taskId : taskIds(NodeType.TEST_DEPLOY)) {
            jdbc.update("UPDATE node_task SET status = 'SUCCESS', finished_at = NOW(3)"
                + " WHERE id = ?", taskId);
        }
        orchestrator.advance(instanceId);
        assertThat(nodeStatus(NodeType.VERIFY)).isEqualTo("RUNNING");

        job.run();

        assertThat(nodeStatus(NodeType.VERIFY))
            .as("a CHECK-only node is completed by the gate/check path, not by reconcile")
            .isEqualTo("RUNNING");
        assertThat(starts(NodeType.PROD_DEPLOY)).isZero();
    }

    // ------------------------------------------------------------------ fixtures and helpers

    /** Moves the latest CODE_CHECK task of BUILD to WAITING_EXTERNAL with a controlled age. */
    private long waitingBuildTask(Duration age, Duration deadlineOffset) {
        long taskId = taskIds(NodeType.BUILD).get(0);
        Instant now = Instant.now();
        transactionRunner.inTransaction(() -> repository.markTaskWaitingExternal(
            taskId, "ext-1", null, now.plus(deadlineOffset), now));
        jdbc.update("UPDATE node_task SET updated_at = ?, deadline_at = ? WHERE id = ?",
            Timestamp.from(now.minus(age)), Timestamp.from(now.plus(deadlineOffset)), taskId);
        return taskId;
    }

    private void registerCountingHandlers() {
        for (NodeType type : NodeType.values()) {
            handlers.register(new CountingHandler(type));
        }
    }

    private int starts(NodeType type) {
        AtomicInteger count = starts.get(type);
        return count == null ? 0 : count.get();
    }

    private final class CountingHandler implements NodeHandler {

        private final NodeType type;

        private CountingHandler(NodeType type) {
            this.type = type;
        }

        @Override
        public NodeType nodeType() {
            return type;
        }

        @Override
        public void start(PipelineInstance instance) {
            starts.computeIfAbsent(type, ignored -> new AtomicInteger()).incrementAndGet();
        }
    }

    private List<Long> taskIds(NodeType nodeType) {
        return jdbc.queryForList(
            "SELECT id FROM node_task WHERE instance_id = ? AND node_type = ? ORDER BY id",
            Long.class, instanceId, nodeType.name());
    }

    private String taskStatus(long taskId) {
        return jdbc.queryForObject("SELECT status FROM node_task WHERE id = ?", String.class,
            taskId);
    }

    private int queryFailures(long taskId) {
        Integer failures = jdbc.queryForObject(
            "SELECT query_failures FROM node_task WHERE id = ?", Integer.class, taskId);
        return failures == null ? 0 : failures;
    }

    private String nodeStatus(NodeType nodeType) {
        return jdbc.queryForObject(
            "SELECT status FROM pipeline_node WHERE instance_id = ? AND node_type = ?",
            String.class, instanceId, nodeType.name());
    }

    private String instanceStatus() {
        return jdbc.queryForObject("SELECT status FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private String currentNode() {
        return jdbc.queryForObject("SELECT current_node FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private void givenUser(String userId) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, 'ACTIVE',"
            + " NOW(3))", userId, "用户" + userId);
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
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id,"
                + " framework_template, branch_mode, status, module_path, created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, 'spring-boot', 'STANDARD', 'ACTIVE',"
                + " 'outbound-os/quota', NOW(3), NOW(3))", subsystem, name, OWNER);
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

    private void givenDe(long unit, String envKind, String name, int batchNo) {
        jdbc.update("INSERT INTO env_de (unit_id, env_kind, de_name, deploy_batch_no,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, NOW(3), NOW(3))",
            unit, envKind, name, batchNo);
    }

    /** Minimal engine double; the reconcile loop only needs {@code query}. */
    private static final class StubEngine implements CicdEngine {

        private EngineResult result = new EngineResult(EngineStatus.RUNNING, null, "RUNNING");
        private RuntimeException failure;
        private int queries;
        private String lastExternalId;
        private JobKind lastKind;

        @Override
        public String name() {
            return "CODING";
        }

        @Override
        public TriggerResult triggerBuild(BuildCmd cmd) {
            throw new UnsupportedOperationException("not used by reconcile");
        }

        @Override
        public TriggerResult triggerDeploy(DeployCmd cmd) {
            throw new UnsupportedOperationException("not used by reconcile");
        }

        @Override
        public EngineResult query(String externalId, JobKind kind) {
            queries++;
            lastExternalId = externalId;
            lastKind = kind;
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        @Override
        public String logs(String externalId, JobKind kind, int offset, int limit) {
            return "";
        }

        @Override
        public void cancel(String externalId) {
        }
    }
}