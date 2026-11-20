package com.sf.release.pipeline;

import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.application.PublishAppService;
import com.sf.release.domain.engine.BuildCmd;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.DeployCmd;
import com.sf.release.domain.engine.EngineException;
import com.sf.release.domain.engine.EngineResult;
import com.sf.release.domain.engine.JobKind;
import com.sf.release.domain.engine.TriggerResult;
import com.sf.release.domain.pipeline.NodeHandler;
import com.sf.release.domain.pipeline.NodeHandlerRegistry;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineOrchestrator;
import com.sf.release.domain.pipeline.TaskType;
import com.sf.release.domain.pipeline.handler.BuildNodeHandler;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The BUILD node chain against the real test schema (MySQL, Flyway V1-V8) with a stub
 * {@link CicdEngine} (the Spring bean is Task 14 wiring).
 *
 * <p>The chain is strictly sequential: entering {@code BUILD} starts only {@code CODE_CHECK};
 * the following step is started when the previous one reports {@code SUCCESS} through the
 * orchestrator's completion callback and {@link NodeHandler#onTaskCompleted}. Every step
 * stays {@code WAITING_EXTERNAL} while the engine runs. A successful {@code IMG_BUILD}
 * persists the image digest and the built commit on the instance (production same-image
 * check J27).
 */
@SpringBootTest
@ActiveProfiles("test")
class BuildNodeHandlerTest {

    private static final String OWNER = "01388007";
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 25);
    private static final String BRANCH = "release/202611";
    private static final String COMMIT = "9f2a1c3d4e5f60718293a4b5c6d7e8f901234567";
    private static final String PLAN_ID = "P-BUILD-QUOTA";
    private static final String DIGEST = "sha256:cd";

    @Autowired ItemAppService itemService;
    @Autowired PublishAppService publishService;
    @Autowired PipelineOrchestrator orchestrator;
    @Autowired NodeHandlerRegistry handlers;
    @Autowired PipelineRepository repository;
    @Autowired TransactionRunner transactionRunner;
    @Autowired JdbcTemplate jdbc;

    private StubEngine engine;
    private long instanceId;
    private long itemId;
    private long unitId;
    private String pipelineNo;

    @BeforeEach
    void setUp() {
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
        long subsystemId = givenSubsystem("SCC-QUOTA");
        long taskId = givenTask(subsystemId, "202611-V6.10.12");
        unitId = givenUnit(subsystemId, "quota-api");
        givenBinding(unitId, "BUILD", PLAN_ID);
        givenBinding(unitId, "TEST_DEPLOY", "P-TEST-QUOTA");
        givenBinding(unitId, "PROD_DEPLOY", "P-PROD-QUOTA");
        itemId = itemService.create(taskId, new CreateItemCmd(unitId, BRANCH, "配额优化", PLAN_DATE));
        jdbc.update("UPDATE release_item SET base_commit = ? WHERE id = ?", COMMIT, itemId);

        PublishAppService.PublishResult published = publishService.publish(itemId);
        pipelineNo = published.pipelineNo();
        instanceId = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class, pipelineNo);

        engine = new StubEngine();
        handlers.clear();
        handlers.register(new BuildNodeHandler(engine, repository, transactionRunner));
        handlers.register(noop(NodeType.TEST_DEPLOY));
        handlers.register(noop(NodeType.VERIFY));
        handlers.register(noop(NodeType.PROD_DEPLOY));
    }

    @AfterEach
    void tearDown() {
        TestUserContext.clear();
        handlers.clear();
    }

    // ------------------------------------------------------------------ sequential chain

    @Test
    void entering_build_starts_only_code_check_with_the_bound_plan_branch_and_commit() {
        orchestrator.advance(instanceId);

        assertThat(engine.triggeredTypes()).containsExactly(TaskType.CODE_CHECK);
        BuildCmd cmd = engine.calls.get(0).cmd();
        assertThat(cmd.unitId()).isEqualTo(unitId);
        assertThat(cmd.taskId()).as("the enabled BUILD binding is the CODING plan").isEqualTo(PLAN_ID);
        assertThat(cmd.branch()).isEqualTo(BRANCH);
        assertThat(cmd.commitSha()).isEqualTo(COMMIT);
        assertThat(cmd.pipelineNo()).isEqualTo(pipelineNo);
        assertThat(cmd.attempt()).isZero();

        assertThat(taskStatus(TaskType.PKG_BUILD)).isEqualTo("PENDING");
        assertThat(taskStatus(TaskType.IMG_BUILD)).isEqualTo("PENDING");
    }

    @Test
    void each_triggered_step_waits_for_the_engine_with_its_external_identifiers() {
        orchestrator.advance(instanceId);

        assertThat(taskStatus(TaskType.CODE_CHECK)).isEqualTo("WAITING_EXTERNAL");
        assertThat(externalId(TaskType.CODE_CHECK)).isEqualTo("ext-1");
        assertThat(externalUrl(TaskType.CODE_CHECK)).isEqualTo("https://coding.example/job/1");
        assertThat(taskStartedAt(TaskType.CODE_CHECK)).isNotNull();
        assertThat(taskFinishedAt(TaskType.CODE_CHECK)).isNull();

        orchestrator.onTaskCompleted(taskId(TaskType.CODE_CHECK), NodeTaskStatus.SUCCESS, null, null);

        assertThat(taskStatus(TaskType.PKG_BUILD)).isEqualTo("WAITING_EXTERNAL");
        assertThat(externalId(TaskType.PKG_BUILD)).isEqualTo("ext-2");
        assertThat(externalUrl(TaskType.PKG_BUILD)).isEqualTo("https://coding.example/job/2");
        assertThat(taskStatus(TaskType.IMG_BUILD)).isEqualTo("PENDING");
    }

    @Test
    void code_check_success_starts_pkg_build_then_pkg_success_starts_image_build() {
        orchestrator.advance(instanceId);

        orchestrator.onTaskCompleted(taskId(TaskType.CODE_CHECK), NodeTaskStatus.SUCCESS, null, null);

        assertThat(engine.triggeredTypes())
            .containsExactly(TaskType.CODE_CHECK, TaskType.PKG_BUILD);
        assertThat(taskStatus(TaskType.CODE_CHECK)).isEqualTo("SUCCESS");
        assertThat(taskStatus(TaskType.PKG_BUILD)).isEqualTo("WAITING_EXTERNAL");
        assertThat(taskStatus(TaskType.IMG_BUILD)).isEqualTo("PENDING");

        orchestrator.onTaskCompleted(taskId(TaskType.PKG_BUILD), NodeTaskStatus.SUCCESS, null, null);

        assertThat(engine.triggeredTypes())
            .containsExactly(TaskType.CODE_CHECK, TaskType.PKG_BUILD, TaskType.IMG_BUILD);
        assertThat(taskStatus(TaskType.PKG_BUILD)).isEqualTo("SUCCESS");
        assertThat(taskStatus(TaskType.IMG_BUILD)).isEqualTo("WAITING_EXTERNAL");
    }

    @Test
    void duplicate_completion_does_not_trigger_a_step_twice() {
        orchestrator.advance(instanceId);
        long codeCheck = taskId(TaskType.CODE_CHECK);

        orchestrator.onTaskCompleted(codeCheck, NodeTaskStatus.SUCCESS, null, null);
        orchestrator.onTaskCompleted(codeCheck, NodeTaskStatus.SUCCESS, null, null);

        assertThat(engine.triggeredTypes())
            .as("a duplicate callback must not restart PKG_BUILD")
            .containsExactly(TaskType.CODE_CHECK, TaskType.PKG_BUILD);
    }

    // ------------------------------------------------------------------ artifacts

    @Test
    void image_build_success_persists_digest_and_commit_for_the_same_image_check() {
        runChainUntilImageBuild();

        orchestrator.onTaskCompleted(taskId(TaskType.IMG_BUILD), NodeTaskStatus.SUCCESS, DIGEST, null);

        assertThat(instanceImageDigest()).isEqualTo(DIGEST);
        assertThat(instanceCommitSha()).isEqualTo(COMMIT);
        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("SUCCESS");
        assertThat(currentNode()).isEqualTo("TEST_DEPLOY");
    }

    // ------------------------------------------------------------------ failures

    @Test
    void step_failure_records_the_engine_message_and_does_not_start_the_next_step() {
        orchestrator.advance(instanceId);
        orchestrator.onTaskCompleted(taskId(TaskType.CODE_CHECK), NodeTaskStatus.SUCCESS, null, null);

        orchestrator.onTaskCompleted(taskId(TaskType.PKG_BUILD), NodeTaskStatus.FAILED, null,
            "disk full");

        assertThat(resultSummary(TaskType.PKG_BUILD)).contains("disk full");
        assertThat(engine.triggeredTypes())
            .as("a failed PKG_BUILD must not start IMG_BUILD")
            .containsExactly(TaskType.CODE_CHECK, TaskType.PKG_BUILD);
        assertThat(taskStatus(TaskType.IMG_BUILD)).isEqualTo("SKIPPED");
        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("FAILED");
        assertThat(instanceStatus()).isEqualTo("FAILED");
    }

    @Test
    void engine_unavailable_fails_the_task_and_does_not_start_the_next_step() {
        engine.failWith(TaskType.CODE_CHECK, EngineException.unavailable("coding 500"));

        orchestrator.advance(instanceId);

        assertThat(taskStatus(TaskType.CODE_CHECK)).isEqualTo("FAILED");
        assertThat(resultSummary(TaskType.CODE_CHECK))
            .contains("ENGINE_UNAVAILABLE")
            .contains("coding 500");
        assertThat(engine.triggeredTypes())
            .as("the engine failure must not start PKG_BUILD")
            .containsExactly(TaskType.CODE_CHECK);
        assertThat(taskStatus(TaskType.PKG_BUILD)).isEqualTo("SKIPPED");
        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("FAILED");
        assertThat(instanceStatus()).isEqualTo("FAILED");
    }

    @Test
    void engine_timeout_on_pkg_build_fails_the_task_and_does_not_start_image_build() {
        orchestrator.advance(instanceId);
        engine.failWith(TaskType.PKG_BUILD, EngineException.timeout("coding read timeout"));

        orchestrator.onTaskCompleted(taskId(TaskType.CODE_CHECK), NodeTaskStatus.SUCCESS, null, null);

        assertThat(taskStatus(TaskType.PKG_BUILD)).isEqualTo("FAILED");
        assertThat(resultSummary(TaskType.PKG_BUILD))
            .contains("ENGINE_TIMEOUT")
            .contains("coding read timeout");
        assertThat(engine.triggeredTypes())
            .as("the timeout on PKG_BUILD must not start IMG_BUILD")
            .containsExactly(TaskType.CODE_CHECK, TaskType.PKG_BUILD);
        assertThat(taskStatus(TaskType.IMG_BUILD)).isEqualTo("SKIPPED");
        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("FAILED");
        assertThat(instanceStatus()).isEqualTo("FAILED");
    }

    // ------------------------------------------------------------------ helpers

    private void runChainUntilImageBuild() {
        orchestrator.advance(instanceId);
        orchestrator.onTaskCompleted(taskId(TaskType.CODE_CHECK), NodeTaskStatus.SUCCESS, null, null);
        orchestrator.onTaskCompleted(taskId(TaskType.PKG_BUILD), NodeTaskStatus.SUCCESS, null, null);
        assertThat(taskStatus(TaskType.IMG_BUILD)).isEqualTo("WAITING_EXTERNAL");
    }

    private long taskId(TaskType type) {
        Long id = jdbc.queryForObject(
            "SELECT id FROM node_task WHERE instance_id = ? AND node_type = 'BUILD'"
                + " AND task_type = ?", Long.class, instanceId, type.name());
        return id == null ? 0L : id;
    }

    private String taskStatus(TaskType type) {
        return jdbc.queryForObject("SELECT status FROM node_task WHERE id = ?", String.class,
            taskId(type));
    }

    private String externalId(TaskType type) {
        return jdbc.queryForObject("SELECT external_id FROM node_task WHERE id = ?", String.class,
            taskId(type));
    }

    private String externalUrl(TaskType type) {
        return jdbc.queryForObject("SELECT external_url FROM node_task WHERE id = ?", String.class,
            taskId(type));
    }

    private String resultSummary(TaskType type) {
        return jdbc.queryForObject("SELECT result_summary FROM node_task WHERE id = ?",
            String.class, taskId(type));
    }

    private java.time.Instant taskStartedAt(TaskType type) {
        java.sql.Timestamp value = jdbc.queryForObject(
            "SELECT started_at FROM node_task WHERE id = ?", java.sql.Timestamp.class,
            taskId(type));
        return value == null ? null : value.toInstant();
    }

    private java.time.Instant taskFinishedAt(TaskType type) {
        java.sql.Timestamp value = jdbc.queryForObject(
            "SELECT finished_at FROM node_task WHERE id = ?", java.sql.Timestamp.class,
            taskId(type));
        return value == null ? null : value.toInstant();
    }

    private String nodeStatus(NodeType type) {
        return jdbc.queryForObject(
            "SELECT status FROM pipeline_node WHERE instance_id = ? AND node_type = ?",
            String.class, instanceId, type.name());
    }

    private String instanceStatus() {
        return jdbc.queryForObject("SELECT status FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private String currentNode() {
        return jdbc.queryForObject("SELECT current_node FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private String instanceImageDigest() {
        return jdbc.queryForObject("SELECT image_digest FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private String instanceCommitSha() {
        return jdbc.queryForObject("SELECT commit_sha FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
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
                + " 'outbound-os/quota', NOW(3), NOW(3))",
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

    private static NodeHandler noop(NodeType type) {
        return new NodeHandler() {
            @Override
            public NodeType nodeType() {
                return type;
            }

            @Override
            public void start(PipelineInstance instance) {
                // The BUILD chain test never needs the later nodes to do anything.
            }
        };
    }

    /** Stub engine: labels each trigger by its position in the fixed BUILD chain. */
    private static final class StubEngine implements CicdEngine {

        private static final List<TaskType> CHAIN = List.of(
            TaskType.CODE_CHECK, TaskType.PKG_BUILD, TaskType.IMG_BUILD);

        private final List<Call> calls = new ArrayList<>();
        private final Map<TaskType, RuntimeException> failures = new EnumMap<>(TaskType.class);

        @Override
        public String name() {
            return "STUB";
        }

        @Override
        public TriggerResult triggerBuild(BuildCmd cmd) {
            TaskType type = CHAIN.get(Math.min(calls.size(), CHAIN.size() - 1));
            calls.add(new Call(type, cmd));
            RuntimeException failure = failures.get(type);
            if (failure != null) {
                throw failure;
            }
            int seq = calls.size();
            return new TriggerResult("ext-" + seq, "https://coding.example/job/" + seq);
        }

        @Override
        public TriggerResult triggerDeploy(DeployCmd cmd) {
            throw new UnsupportedOperationException("no deploy in the BUILD chain test");
        }

        @Override
        public EngineResult query(String externalId, JobKind kind) {
            throw new UnsupportedOperationException("no query in the BUILD chain test");
        }

        @Override
        public String logs(String externalId, JobKind kind, int offset, int limit) {
            throw new UnsupportedOperationException("no logs in the BUILD chain test");
        }

        @Override
        public void cancel(String externalId) {
            throw new UnsupportedOperationException("no cancel in the BUILD chain test");
        }

        void failWith(TaskType type, RuntimeException failure) {
            failures.put(type, failure);
        }

        List<TaskType> triggeredTypes() {
            return calls.stream().map(Call::type).toList();
        }

        /** One trigger and the command the handler built. */
        record Call(TaskType type, BuildCmd cmd) {
        }
    }
}