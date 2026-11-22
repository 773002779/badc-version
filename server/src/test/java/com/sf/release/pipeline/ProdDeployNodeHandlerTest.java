package com.sf.release.pipeline;

import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.application.PublishAppService;
import com.sf.release.domain.engine.BuildCmd;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.DeployCmd;
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
import com.sf.release.domain.pipeline.handler.ProdDeployNodeHandler;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.pipeline.port.ProductionBoundaryProbe;
import com.sf.release.domain.pipeline.port.TransactionRunner;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code PROD_DEPLOY} node against the real test schema and the real batch-boundary probe
 * (tech design 4.7): serial batches, a parallel fan-out inside a batch, the next batch only after the
 * previous one fully succeeded, a failed task stopping everything after it, and the boundary pause on
 * a freeze or a final gate block.
 *
 * <p>The instance is published through the real services (so the pre-generated production tasks carry
 * the DEs' {@code deploy_batch_no}) and then moved to "the release is executing" by hand: the earlier
 * nodes succeeded, the 变更项 is {@code PROD_DEPLOYING} and the production node is {@code RUNNING}. The
 * engine is a stub so the assertions can name the exact DEs triggered.
 */
@SpringBootTest(classes = {com.sf.release.ReleaseApplication.class,
    ProdDeployNodeHandlerTest.StubEngineConfig.class})
@ActiveProfiles("test")
class ProdDeployNodeHandlerTest {

    private static final String OWNER = "01388008";
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 25);
    private static final String BRANCH = "release/prod";
    private static final String PROD_PLAN = "P-PROD-DEPLOY";
    private static final String DIGEST = "sha256:prod13";
    private static final String BATCH_ONE_A = "prod-canary";
    private static final String BATCH_ONE_B = "prod-region-a";
    private static final String BATCH_TWO = "prod-region-b";

    @Autowired ItemAppService itemService;
    @Autowired PublishAppService publishService;
    @Autowired PipelineOrchestrator orchestrator;
    @Autowired NodeHandlerRegistry handlers;
    @Autowired PipelineRepository repository;
    @Autowired TransactionRunner transactionRunner;
    @Autowired NodeTaskCompletionService completionService;
    @Autowired ProductionBoundaryProbe boundary;
    @Autowired JdbcTemplate jdbc;
    @Autowired StubEngine engine;

    private ProdDeployNodeHandler handler;
    private long instanceId;
    private long orderId;
    private long itemId;
    private long batchOneA;
    private long batchOneB;
    private long batchTwo;
    private long taskOneA;
    private long taskOneB;
    private long taskTwo;

    @BeforeEach
    void setUp() {
        TestUserContext.allUnits(OWNER);
        cleanDatabase();
        engine.clear();

        givenUser(OWNER);
        long subsystemId = givenSubsystem("SCC-PROD");
        long taskId = givenTask(subsystemId, "202611-PROD");
        long unitId = givenUnit(subsystemId, "prod-api");
        givenBinding(unitId, "BUILD", "P-BUILD-PROD");
        givenBinding(unitId, "TEST_DEPLOY", "P-TEST-PROD");
        givenBinding(unitId, "PROD_DEPLOY", PROD_PLAN);
        givenDe(unitId, "TEST", "prod-sit", 0);
        batchOneA = givenDe(unitId, "PROD", BATCH_ONE_A, 1);
        batchOneB = givenDe(unitId, "PROD", BATCH_ONE_B, 1);
        batchTwo = givenDe(unitId, "DR", BATCH_TWO, 2);

        itemId = itemService.create(taskId, new CreateItemCmd(unitId, BRANCH, "生产部署", PLAN_DATE));
        PublishAppService.PublishResult published = publishService.publish(itemId);
        instanceId = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class,
            published.pipelineNo());
        orderId = jdbc.queryForObject("SELECT order_id FROM pipeline_instance WHERE id = ?",
            Long.class, instanceId);
        intoProductionExecution();
        taskOneA = taskIdFor(batchOneA);
        taskOneB = taskIdFor(batchOneB);
        taskTwo = taskIdFor(batchTwo);

        handler = new ProdDeployNodeHandler(engine, repository, transactionRunner,
            completionService, boundary);
        handlers.clear();
        handlers.register(handler);
        handlers.register(noop(NodeType.BUILD));
        handlers.register(noop(NodeType.TEST_DEPLOY));
        handlers.register(noop(NodeType.VERIFY));
    }

    @AfterEach
    void tearDown() {
        TestUserContext.clear();
        handlers.clear();
    }

    // ------------------------------------------------------------------ serial batches

    @Test
    void entering_the_node_triggers_only_the_lowest_batch_and_all_of_it_in_parallel() {
        handler.start(repository.loadInstance(instanceId));

        assertThat(engine.triggeredDeNames())
            .as("only batch 1 is deployed; batch 2 waits for it")
            .containsExactlyInAnyOrder(BATCH_ONE_A, BATCH_ONE_B);
        DeployCmd canary = engine.deployCallTo(BATCH_ONE_A).cmd();
        assertThat(canary.taskId()).as("the unit's PROD_DEPLOY binding is the deploy plan")
            .isEqualTo(PROD_PLAN);
        assertThat(canary.envKind()).isEqualTo("PROD");
        assertThat(canary.imageDigest()).isEqualTo(DIGEST);
        assertThat(canary.pipelineNo()).isEqualTo(pipelineNo());
        assertThat(canary.attempt()).isZero();

        assertThat(taskStatus(taskOneA)).isEqualTo("WAITING_EXTERNAL");
        assertThat(taskStatus(taskOneB)).isEqualTo("WAITING_EXTERNAL");
        assertThat(taskStatus(taskTwo))
            .as("the second batch is untouched until the first one fully succeeds")
            .isEqualTo("PENDING");
        assertThat(externalId(taskOneA)).isNotBlank().isNotEqualTo(externalId(taskOneB));
        assertThat(deadlineAt(taskOneA))
            .as("a production deploy has the same 8-minute deadline as a test deploy")
            .isAfter(Instant.now().plus(Duration.ofMinutes(7)))
            .isBefore(Instant.now().plus(Duration.ofMinutes(9)));
        assertThat(nodeStatus()).isEqualTo("RUNNING");
        assertThat(instanceStatus()).isEqualTo("RUNNING");
    }

    @Test
    void a_fully_successful_batch_triggers_the_next_one_and_the_last_one_completes_the_node() {
        handler.start(repository.loadInstance(instanceId));

        orchestrator.onTaskCompleted(taskOneA, NodeTaskStatus.SUCCESS, null, null);
        assertThat(engine.triggeredDeNames())
            .as("one task of the batch is not the whole batch")
            .containsExactlyInAnyOrder(BATCH_ONE_A, BATCH_ONE_B);
        assertThat(taskStatus(taskTwo)).isEqualTo("PENDING");

        orchestrator.onTaskCompleted(taskOneB, NodeTaskStatus.SUCCESS, null, null);

        assertThat(engine.triggeredDeNames())
            .as("the last success of batch 1 opens batch 2")
            .containsExactlyInAnyOrder(BATCH_ONE_A, BATCH_ONE_B, BATCH_TWO);
        assertThat(taskStatus(taskTwo)).isEqualTo("WAITING_EXTERNAL");
        assertThat(nodeStatus()).isEqualTo("RUNNING");

        orchestrator.onTaskCompleted(taskTwo, NodeTaskStatus.SUCCESS, null, null);

        assertThat(nodeStatus()).isEqualTo("SUCCESS");
        assertThat(instanceStatus()).isEqualTo("DONE");
        assertThat(itemStatus()).as("PROD_OK moves the item to DONE").isEqualTo("DONE");
        assertThat(engine.triggeredDeNames()).hasSize(3);
    }

    @Test
    void a_failed_task_in_batch_one_never_triggers_the_next_batch() {
        handler.start(repository.loadInstance(instanceId));

        orchestrator.onTaskCompleted(taskOneA, NodeTaskStatus.FAILED, null, "deploy job 7001 failed");

        assertThat(engine.triggeredDeNames())
            .as("the later batch must not be triggered after a failure")
            .containsExactlyInAnyOrder(BATCH_ONE_A, BATCH_ONE_B);
        assertThat(taskStatus(taskTwo))
            .as("the orchestrator skips the still-pending siblings of a failed node")
            .isEqualTo("SKIPPED");
        assertThat(nodeStatus()).isEqualTo("FAILED");
        assertThat(instanceStatus()).isEqualTo("FAILED");
        assertThat(itemStatus()).isEqualTo("FAILED");
    }

    // ------------------------------------------------------------------ boundary

    @Test
    void the_entrance_pauses_instead_of_deploying_while_a_freeze_is_in_force() {
        givenFreezeInForce();

        handler.start(repository.loadInstance(instanceId));

        assertThat(engine.deployCalls).as("nothing is deployed under a freeze").isEmpty();
        assertThat(instanceStatus()).isEqualTo("PAUSED");
        assertThat(currentNode()).isEqualTo("PROD_DEPLOY");
        assertThat(statusReason()).contains("封板").contains("PROD_DEPLOY");
        assertThat(taskStatus(taskOneA)).isEqualTo("PENDING");
        assertThat(taskStatus(taskTwo)).isEqualTo("PENDING");
    }

    @Test
    void the_entrance_pauses_on_a_non_waivable_gate_block() {
        givenGateCheck("FAIL", "S1");

        handler.start(repository.loadInstance(instanceId));

        assertThat(engine.deployCalls).as("a final gate block stops production").isEmpty();
        assertThat(instanceStatus()).isEqualTo("PAUSED");
        assertThat(currentNode()).isEqualTo("PROD_DEPLOY");
        assertThat(statusReason()).contains("门禁").contains("PROD_DEPLOY");
        assertThat(taskStatus(taskOneA)).isEqualTo("PENDING");
    }

    @Test
    void a_re_entry_does_not_trigger_a_task_that_is_already_waiting_for_the_engine() {
        handler.start(repository.loadInstance(instanceId));
        assertThat(engine.deployCalls).hasSize(2);

        handler.start(repository.loadInstance(instanceId));
        handler.onTaskCompleted(repository.loadInstance(instanceId),
            repository.loadTask(taskOneA));

        assertThat(engine.deployCalls)
            .as("an in-flight batch is never triggered twice")
            .hasSize(2);
        assertThat(engine.deployCalls.stream().map(call -> call.cmd().deName()).toList())
            .containsExactlyInAnyOrder(BATCH_ONE_A, BATCH_ONE_B);
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * The state right before the first production batch: the earlier nodes succeeded, the item is
     * executing, the node is running and the built image is on the instance.
     */
    private void intoProductionExecution() {
        jdbc.update("UPDATE release_item SET status = 'PROD_DEPLOYING', gate_status = 'CHECK_PASS',"
            + " test_status = 'TEST_PASS' WHERE id = ?", itemId);
        jdbc.update("UPDATE release_order SET status = 'EXECUTING' WHERE id = ?", orderId);
        jdbc.update("UPDATE pipeline_instance SET status = 'RUNNING', current_node = 'PROD_DEPLOY',"
            + " image_digest = ?, start_at = NOW(3) WHERE id = ?", DIGEST, instanceId);
        jdbc.update("UPDATE pipeline_node SET status = 'SUCCESS', started_at = NOW(3),"
            + " finished_at = NOW(3) WHERE instance_id = ? AND node_type IN"
            + " ('BUILD', 'TEST_DEPLOY', 'VERIFY')", instanceId);
        jdbc.update("UPDATE pipeline_node SET status = 'RUNNING', started_at = NOW(3)"
            + " WHERE instance_id = ? AND node_type = 'PROD_DEPLOY'", instanceId);
        jdbc.update("UPDATE node_task SET status = 'SUCCESS', started_at = NOW(3),"
            + " finished_at = NOW(3) WHERE instance_id = ? AND node_type IN"
            + " ('BUILD', 'TEST_DEPLOY', 'VERIFY')", instanceId);
    }

    private void givenFreezeInForce() {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO freeze_event (scope, subsystem_id, reason, impact, initiator_id,"
                + " start_at, end_at, status, created_at)"
                + " VALUES ('GLOBAL', NULL, '大促封板', NULL, ?, ?, ?, 'ACTIVE', NOW(3))",
            OWNER, java.sql.Timestamp.from(now.minusSeconds(60)),
            java.sql.Timestamp.from(now.plusSeconds(3600)));
    }

    private void givenGateCheck(String status, String severity) {
        jdbc.update("INSERT INTO gate_check (item_id, order_id, check_kind, stage, status, severity,"
                + " issues, attempt, created_at, updated_at)"
                + " VALUES (?, ?, 'SONAR', 'IMAGE', ?, ?, 1, 1, NOW(3), NOW(3))",
            itemId, orderId, status, severity);
    }

    private long taskIdFor(long deId) {
        Long id = jdbc.queryForObject(
            "SELECT id FROM node_task WHERE instance_id = ? AND node_type = 'PROD_DEPLOY'"
                + " AND target_id = ?", Long.class, instanceId, deId);
        return id == null ? 0L : id;
    }

    private String pipelineNo() {
        return jdbc.queryForObject("SELECT pipeline_no FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private String taskStatus(long taskId) {
        return jdbc.queryForObject("SELECT status FROM node_task WHERE id = ?", String.class,
            taskId);
    }

    private String externalId(long taskId) {
        return jdbc.queryForObject("SELECT external_id FROM node_task WHERE id = ?", String.class,
            taskId);
    }

    private Instant deadlineAt(long taskId) {
        java.sql.Timestamp value = jdbc.queryForObject(
            "SELECT deadline_at FROM node_task WHERE id = ?", java.sql.Timestamp.class, taskId);
        return value == null ? null : value.toInstant();
    }

    private String nodeStatus() {
        return jdbc.queryForObject(
            "SELECT status FROM pipeline_node WHERE instance_id = ? AND node_type = 'PROD_DEPLOY'",
            String.class, instanceId);
    }

    private String instanceStatus() {
        return jdbc.queryForObject("SELECT status FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private String currentNode() {
        return jdbc.queryForObject("SELECT current_node FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private String statusReason() {
        return jdbc.queryForObject("SELECT status_reason FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private String itemStatus() {
        return jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?", String.class,
            itemId);
    }

    private void cleanDatabase() {
        jdbc.update("DELETE FROM gate_check");
        jdbc.update("DELETE FROM gate_waiver");
        jdbc.update("DELETE FROM freeze_event");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM admission_snapshot");
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
        jdbc.update("DELETE FROM user_scope WHERE user_id = ?", OWNER);
        jdbc.update("DELETE FROM user_role WHERE user_id = ?", OWNER);
        jdbc.update("DELETE FROM app_user WHERE id = ?", OWNER);
    }

    private void givenUser(String userId) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3))", userId, "用户" + userId);
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'ALL', 0)",
            userId);
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
                + " 'outbound-os/prod', NOW(3), NOW(3))", subsystem, name, OWNER);
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

    private long givenDe(long unit, String envKind, String name, int batchNo) {
        jdbc.update("INSERT INTO env_de (unit_id, env_kind, de_name, deploy_batch_no,"
            + " created_at, updated_at) VALUES (?, ?, ?, ?, NOW(3), NOW(3))",
            unit, envKind, name, batchNo);
        Long id = jdbc.queryForObject(
            "SELECT id FROM env_de WHERE unit_id = ? AND de_name = ?", Long.class, unit, name);
        return id == null ? 0L : id;
    }

    private static NodeHandler noop(NodeType type) {
        return new NodeHandler() {
            @Override
            public NodeType nodeType() {
                return type;
            }

            @Override
            public void start(PipelineInstance instance) {
                // Only PROD_DEPLOY is under test; the other nodes do nothing.
            }
        };
    }

    /** Stub engine: records each production deploy trigger. */
    static final class StubEngine implements CicdEngine {

        private final List<DeployCall> deployCalls = new ArrayList<>();

        @Override
        public String name() {
            return "STUB";
        }

        @Override
        public TriggerResult triggerBuild(BuildCmd cmd) {
            throw new UnsupportedOperationException("no build in the PROD_DEPLOY test");
        }

        @Override
        public TriggerResult triggerDeploy(DeployCmd cmd) {
            deployCalls.add(new DeployCall(cmd));
            int seq = deployCalls.size();
            return new TriggerResult("dep-" + seq, "https://coding.example/deploy/" + seq);
        }

        @Override
        public EngineResult query(String externalId, JobKind kind) {
            throw new UnsupportedOperationException("no query in the PROD_DEPLOY test");
        }

        @Override
        public String logs(String externalId, JobKind kind, int offset, int limit) {
            throw new UnsupportedOperationException("no logs in the PROD_DEPLOY test");
        }

        @Override
        public void cancel(String externalId) {
            throw new UnsupportedOperationException("no cancel in the PROD_DEPLOY test");
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

        void clear() {
            deployCalls.clear();
        }

        record DeployCall(DeployCmd cmd) {
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubEngineConfig {

        @Bean
        @Primary
        StubEngine prodDeployStubEngine() {
            return new StubEngine();
        }
    }
}