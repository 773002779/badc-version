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
import com.sf.release.domain.pipeline.NodeTask;
import com.sf.release.domain.pipeline.NodeTaskCompletionService;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineOrchestrator;
import com.sf.release.domain.pipeline.TaskType;
import com.sf.release.domain.pipeline.event.NodeTaskCompletedEvent;
import com.sf.release.domain.pipeline.event.TestEnvReadyEvent;
import com.sf.release.domain.pipeline.handler.TestDeployNodeHandler;
import com.sf.release.domain.pipeline.port.PipelineEventBus;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.pipeline.port.TransactionRunner;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The TEST_DEPLOY node against the real test schema (MySQL, Flyway V1-V9) with a stub
 * {@link CicdEngine}.
 *
 * <p>Entering {@code TEST_DEPLOY} deploys the image built by {@code BUILD} to <em>every</em>
 * TEST DE in one go: one independent {@code DEPLOY} task per DE, each moved to
 * {@code WAITING_EXTERNAL} with its own engine id. The tasks are never chained (unlike BUILD),
 * so one DE completing or failing does not stop the others from being triggered. A single DE
 * failure keeps its own row failed and leaves the successful rows alone; the orchestrator then
 * fails the node and the instance. {@link TestEnvReadyEvent} is published once, after the
 * completion transaction, only when every TEST DE deploy task is {@code SUCCESS}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDeployNodeHandlerTest.EventRecorderConfig.class)
class TestDeployNodeHandlerTest {

    private static final String OWNER = "01388007";
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 25);
    private static final String BRANCH = "release/202611";
    private static final String PLAN_ID = "P-TEST-QUOTA";
    private static final String DIGEST = "sha256:test13";
    private static final String TEST_DE_ONE = "quota-sit";
    private static final String TEST_DE_TWO = "quota-uat";
    private static final String PROD_DE = "quota-prod-a";

    @Autowired ItemAppService itemService;
    @Autowired PublishAppService publishService;
    @Autowired PipelineOrchestrator orchestrator;
    @Autowired NodeHandlerRegistry handlers;
    @Autowired PipelineRepository repository;
    @Autowired TransactionRunner transactionRunner;
    @Autowired NodeTaskCompletionService completionService;
    @Autowired PipelineEventBus eventBus;
    @Autowired JdbcTemplate jdbc;
    @Autowired EventRecorder recorder;

    private StubEngine engine;
    private TestDeployNodeHandler handler;
    private long instanceId;
    private long unitId;
    private long testDeOne;
    private long testDeTwo;
    private String pipelineNo;
    private String publishNo;

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
        givenBinding(unitId, "BUILD", "P-BUILD-QUOTA");
        givenBinding(unitId, "TEST_DEPLOY", PLAN_ID);
        givenBinding(unitId, "PROD_DEPLOY", "P-PROD-QUOTA");
        testDeOne = givenDe(unitId, "TEST", TEST_DE_ONE, 0);
        testDeTwo = givenDe(unitId, "TEST", TEST_DE_TWO, 0);
        givenDe(unitId, "PROD", PROD_DE, 1);

        long itemId = itemService.create(taskId, new CreateItemCmd(unitId, BRANCH, "测试部署",
            PLAN_DATE));
        PublishAppService.PublishResult published = publishService.publish(itemId);
        pipelineNo = published.pipelineNo();
        publishNo = published.publishNo();
        instanceId = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class, pipelineNo);

        engine = new StubEngine();
        recorder.clear();
        handlers.clear();
        handler = new TestDeployNodeHandler(engine, repository, transactionRunner,
            completionService, eventBus);
        handlers.register(handler);
        handlers.register(noop(NodeType.BUILD));
        handlers.register(noop(NodeType.VERIFY));
        handlers.register(noop(NodeType.PROD_DEPLOY));
    }

    @AfterEach
    void tearDown() {
        TestUserContext.clear();
        handlers.clear();
    }

    // ------------------------------------------------------------------ parallel triggers

    @Test
    void entering_test_deploy_triggers_one_deploy_per_test_de_with_the_built_image() {
        enterTestDeploy();

        assertThat(engine.deployCalls)
            .as("exactly one deploy trigger per TEST DE, and none for the PROD DE")
            .hasSize(2);
        assertThat(engine.triggeredDeNames()).containsExactlyInAnyOrder(TEST_DE_ONE, TEST_DE_TWO);
        assertThat(engine.deployCalls).noneSatisfy(call ->
            assertThat(call.cmd().deName()).isEqualTo(PROD_DE));

        DeployCmd sit = engine.deployCallTo(TEST_DE_ONE).cmd();
        assertThat(sit.unitId()).isEqualTo(unitId);
        assertThat(sit.taskId()).as("the enabled TEST_DEPLOY binding is the deploy plan")
            .isEqualTo(PLAN_ID);
        assertThat(sit.envKind()).isEqualTo("TEST");
        assertThat(sit.imageDigest())
            .as("the image built by BUILD is deployed as-is, never rebuilt")
            .isEqualTo(DIGEST);
        assertThat(sit.pipelineNo()).isEqualTo(pipelineNo);
        assertThat(sit.attempt()).isZero();

        DeployCmd uat = engine.deployCallTo(TEST_DE_TWO).cmd();
        assertThat(uat.imageDigest()).isEqualTo(DIGEST);
        assertThat(uat.envKind()).isEqualTo("TEST");

        assertThat(taskStatus(taskIdFor(testDeOne))).isEqualTo("WAITING_EXTERNAL");
        assertThat(taskStatus(taskIdFor(testDeTwo))).isEqualTo("WAITING_EXTERNAL");
        assertThat(externalId(taskIdFor(testDeOne)))
            .isNotBlank().isNotEqualTo(externalId(taskIdFor(testDeTwo)));
        assertThat(externalUrl(taskIdFor(testDeOne))).isNotBlank();
        assertThat(deadlineAt(taskIdFor(testDeOne))).isNotNull();
        assertThat(nodeStatus(NodeType.TEST_DEPLOY)).isEqualTo("RUNNING");
        assertThat(instanceStatus()).isEqualTo("RUNNING");
        assertThat(instanceImageDigest())
            .as("TEST_DEPLOY never rewrites the image of the instance")
            .isEqualTo(DIGEST);
    }

    @Test
    void duplicate_entry_and_duplicate_callback_do_not_trigger_a_second_deploy_per_de() {
        PipelineInstance beforeEntry = repository.loadInstance(instanceId);

        enterTestDeploy();
        assertThat(engine.deployCalls).hasSize(2);

        handlers.handler(NodeType.TEST_DEPLOY).start(beforeEntry);
        assertThat(engine.deployCalls)
            .as("a duplicate entry with a stale aggregate must not retrigger any DE")
            .hasSize(2);

        orchestrator.advance(instanceId);
        assertThat(engine.deployCalls)
            .as("advancing a RUNNING TEST_DEPLOY node must not retrigger any DE")
            .hasSize(2);

        orchestrator.onTaskCompleted(taskIdFor(testDeOne), NodeTaskStatus.SUCCESS, null, null);
        orchestrator.onTaskCompleted(taskIdFor(testDeOne), NodeTaskStatus.SUCCESS, null, null);
        assertThat(engine.deployCalls).hasSize(2);

        // A re-entry (for example after a crash between two triggers) must only start the DE
        // that has no external job yet, never the one already WAITING_EXTERNAL.
        jdbc.update("UPDATE node_task SET status = 'PENDING', external_id = NULL,"
            + " external_url = NULL, deadline_at = NULL, started_at = NULL WHERE id = ?",
            taskIdFor(testDeTwo));
        handlers.handler(NodeType.TEST_DEPLOY).start(repository.loadInstance(instanceId));

        assertThat(engine.triggeredDeNames())
            .containsExactly(TEST_DE_ONE, TEST_DE_TWO, TEST_DE_TWO);
        assertThat(taskStatus(taskIdFor(testDeTwo))).isEqualTo("WAITING_EXTERNAL");
    }

    // ------------------------------------------------------------------ ready event

    @Test
    void ready_event_is_published_exactly_once_when_every_test_de_succeeded() {
        enterTestDeploy();

        orchestrator.onTaskCompleted(taskIdFor(testDeOne), NodeTaskStatus.SUCCESS, null, null);
        assertThat(recorder.testEnvReady)
            .as("an incomplete TEST_DEPLOY is not a ready test environment")
            .isEmpty();

        orchestrator.onTaskCompleted(taskIdFor(testDeTwo), NodeTaskStatus.SUCCESS, null, null);

        assertThat(recorder.testEnvReady).hasSize(1);
        TestEnvReadyEvent event = recorder.testEnvReady.get(0);
        assertThat(event.publishNo()).isEqualTo(publishNo);
        assertThat(event.instanceId()).isEqualTo(instanceId);
        assertThat(event.readyAt()).isNotNull();
        assertThat(testReadyAt())
            .as("the durable ready claim is written with the event")
            .isNotNull();

        assertThat(taskStatus(taskIdFor(testDeOne))).isEqualTo("SUCCESS");
        assertThat(taskStatus(taskIdFor(testDeTwo))).isEqualTo("SUCCESS");
        assertThat(nodeStatus(NodeType.TEST_DEPLOY)).isEqualTo("SUCCESS");
        assertThat(currentNode()).isEqualTo("VERIFY");
        assertThat(instanceStatus()).isEqualTo("RUNNING");

        orchestrator.onTaskCompleted(taskIdFor(testDeTwo), NodeTaskStatus.SUCCESS, null, null);
        assertThat(recorder.testEnvReady)
            .as("a duplicate completion callback publishes nothing")
            .hasSize(1);

        handler.onTaskCompleted(repository.loadInstance(instanceId),
            repository.loadTask(taskIdFor(testDeTwo)));
        assertThat(recorder.testEnvReady)
            .as("the durable claim suppresses a second publication even off the callback path")
            .hasSize(1);
    }

    @Test
    void only_one_concurrent_claim_of_the_ready_marker_wins() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> claims = new ArrayList<>();
            for (int attempt = 0; attempt < 2; attempt++) {
                claims.add(pool.submit(() -> {
                    start.await();
                    return repository.claimTestReady(instanceId);
                }));
            }
            start.countDown();

            long winners = 0;
            for (Future<Boolean> claim : claims) {
                if (claim.get(10, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertThat(winners)
                .as("the test_ready_at compare-and-set lets exactly one concurrent claim win")
                .isEqualTo(1);
            assertThat(testReadyAt()).isNotNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void ready_event_is_published_once_under_concurrent_completions() throws Exception {
        enterTestDeploy();
        long firstTask = taskIdFor(testDeOne);
        long secondTask = taskIdFor(testDeTwo);
        jdbc.update("UPDATE node_task SET status = 'SUCCESS', finished_at = NOW(3)"
            + " WHERE id IN (?, ?)", firstTask, secondTask);
        PipelineInstance current = repository.loadInstance(instanceId);
        NodeTask completedFirst = repository.loadTask(firstTask);
        NodeTask completedSecond = repository.loadTask(secondTask);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> calls = List.of(
                pool.submit(() -> {
                    start.await();
                    handler.onTaskCompleted(current, completedFirst);
                    return null;
                }),
                pool.submit(() -> {
                    start.await();
                    handler.onTaskCompleted(current, completedSecond);
                    return null;
                }));
            start.countDown();
            for (Future<?> call : calls) {
                call.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(recorder.testEnvReady)
            .as("two concurrent completion hooks claim the event once")
            .hasSize(1);
        assertThat(testReadyAt()).isNotNull();

        handler.onTaskCompleted(repository.loadInstance(instanceId), completedSecond);
        assertThat(recorder.testEnvReady).hasSize(1);
    }

    @Test
    void empty_test_deploy_node_never_claims_ready() {
        jdbc.update("DELETE FROM node_task WHERE instance_id = ?"
            + " AND node_type = 'TEST_DEPLOY'", instanceId);
        NodeTask completed = new NodeTask(0L, instanceId, NodeType.TEST_DEPLOY, TaskType.DEPLOY,
            0, testDeOne, NodeTaskStatus.SUCCESS, 0, null, null, null, null, null, null, null);

        handler.onTaskCompleted(repository.loadInstance(instanceId), completed);

        assertThat(recorder.testEnvReady).isEmpty();
        assertThat(testReadyAt()).isNull();
    }

    @Test
    void a_skipped_deploy_is_neutral_for_readiness() {
        enterTestDeploy();
        jdbc.update("UPDATE node_task SET status = 'SKIPPED', finished_at = NOW(3)"
            + " WHERE id = ?", taskIdFor(testDeTwo));

        orchestrator.onTaskCompleted(taskIdFor(testDeOne), NodeTaskStatus.SUCCESS, null, null);

        assertThat(recorder.testEnvReady)
            .as("SKIPPED is neutral in the orchestrator fold, so a completable node is ready")
            .hasSize(1);
        assertThat(taskStatus(taskIdFor(testDeOne))).isEqualTo("SUCCESS");
        assertThat(taskStatus(taskIdFor(testDeTwo))).isEqualTo("SKIPPED");
        assertThat(nodeStatus(NodeType.TEST_DEPLOY)).isEqualTo("SUCCESS");
    }

    @Test
    void missing_test_deploy_binding_fails_the_tasks_without_calling_the_engine() {
        jdbc.update("UPDATE coding_task_binding SET enabled = 0"
            + " WHERE unit_id = ? AND task_kind = 'TEST_DEPLOY'", unitId);

        enterTestDeploy();

        assertThat(engine.deployCalls)
            .as("no deploy may be sent with a null plan id")
            .isEmpty();
        assertThat(taskStatus(taskIdFor(testDeOne))).isEqualTo("FAILED");
        assertThat(taskStatus(taskIdFor(testDeTwo))).isEqualTo("FAILED");
        assertThat(resultSummary(taskIdFor(testDeOne)))
            .contains("UNIT_NOT_CONFIGURED")
            .contains("TEST_DEPLOY");
        assertThat(recorder.taskCompleted)
            .filteredOn(event -> event.status() == NodeTaskStatus.FAILED)
            .as("every affected deploy task fails through the completion service")
            .hasSize(2);
        assertThat(recorder.testEnvReady).isEmpty();
        assertThat(testReadyAt()).isNull();
        assertThat(nodeStatus(NodeType.TEST_DEPLOY)).isEqualTo("FAILED");
        assertThat(instanceStatus()).isEqualTo("FAILED");
    }

    @Test
    void missing_built_image_digest_fails_the_tasks_without_calling_the_engine() {
        enterTestDeploy(null);

        assertThat(engine.deployCalls)
            .as("a deploy without the built image must not reach the engine")
            .isEmpty();
        assertThat(taskStatus(taskIdFor(testDeOne))).isEqualTo("FAILED");
        assertThat(taskStatus(taskIdFor(testDeTwo))).isEqualTo("FAILED");
        assertThat(resultSummary(taskIdFor(testDeOne)))
            .contains("STATE_NOT_ALLOWED")
            .contains("image digest");
        assertThat(recorder.testEnvReady).isEmpty();
        assertThat(testReadyAt()).isNull();
        assertThat(nodeStatus(NodeType.TEST_DEPLOY)).isEqualTo("FAILED");
        assertThat(instanceStatus()).isEqualTo("FAILED");
    }

    @Test
    void one_de_failure_fails_the_instance_but_keeps_the_successful_deploy_record() {
        enterTestDeploy();

        orchestrator.onTaskCompleted(taskIdFor(testDeOne), NodeTaskStatus.SUCCESS, null, null);
        orchestrator.onTaskCompleted(taskIdFor(testDeTwo), NodeTaskStatus.FAILED, null,
            "deploy job 9001 failed");

        assertThat(recorder.testEnvReady)
            .as("a partial success is never a ready test environment")
            .isEmpty();
        assertThat(taskStatus(taskIdFor(testDeOne)))
            .as("the successful DE record stays SUCCESS")
            .isEqualTo("SUCCESS");
        assertThat(taskStatus(taskIdFor(testDeTwo))).isEqualTo("FAILED");
        assertThat(resultSummary(taskIdFor(testDeTwo))).contains("deploy job 9001 failed");
        assertThat(nodeStatus(NodeType.TEST_DEPLOY)).isEqualTo("FAILED");
        assertThat(instanceStatus()).isEqualTo("FAILED");
        assertThat(currentNode()).isEqualTo("TEST_DEPLOY");
    }

    @Test
    void engine_failure_on_one_de_fails_that_task_and_publishes_no_ready_event() {
        engine.failOn(TEST_DE_TWO, EngineException.unavailable("coding deploy 500"));

        enterTestDeploy();

        assertThat(engine.deployCalls).hasSize(2);
        long failedTask = taskIdFor(testDeTwo);
        assertThat(taskStatus(failedTask)).isEqualTo("FAILED");
        assertThat(resultSummary(failedTask))
            .contains("ENGINE_UNAVAILABLE")
            .contains("coding deploy 500");
        assertThat(recorder.taskCompleted)
            .as("the engine failure is routed through the completion service")
            .filteredOn(event -> event.taskId() == failedTask)
            .hasSize(1)
            .allSatisfy(event -> {
                assertThat(event.status()).isEqualTo(NodeTaskStatus.FAILED);
                assertThat(event.message()).contains("ENGINE_UNAVAILABLE");
            });
        assertThat(taskStatus(taskIdFor(testDeOne)))
            .as("the other DE record is not failed by the engine failure of its sibling")
            .isEqualTo("WAITING_EXTERNAL");
        assertThat(recorder.testEnvReady).isEmpty();
        assertThat(nodeStatus(NodeType.TEST_DEPLOY)).isEqualTo("FAILED");
        assertThat(instanceStatus()).isEqualTo("FAILED");
    }

    // ------------------------------------------------------------------ retries

    @Test
    void a_retry_row_triggers_the_latest_attempt_only() {
        long failedAttempt = taskIdFor(testDeOne);
        jdbc.update("UPDATE node_task SET status = 'FAILED', finished_at = NOW(3),"
            + " result_summary = 'superseded' WHERE id = ?", failedAttempt);
        jdbc.update("INSERT INTO node_task (instance_id, node_type, task_type, batch_no,"
            + " target_id, status, attempt)"
            + " SELECT instance_id, node_type, task_type, batch_no, target_id, 'PENDING', 1"
            + " FROM node_task WHERE id = ?", failedAttempt);
        long retriedAttempt = jdbc.queryForObject(
            "SELECT id FROM node_task WHERE instance_id = ? AND node_type = 'TEST_DEPLOY'"
                + " AND target_id = ? AND attempt = 1", Long.class, instanceId, testDeOne);

        enterTestDeploy();

        assertThat(engine.deployCalls).hasSize(2);
        assertThat(engine.deployCallTo(TEST_DE_ONE).cmd().attempt()).isEqualTo(1);
        assertThat(taskStatus(retriedAttempt)).isEqualTo("WAITING_EXTERNAL");
        assertThat(externalId(retriedAttempt)).isNotBlank();
        assertThat(taskStatus(failedAttempt))
            .as("the retained old attempt row is left intact")
            .isEqualTo("FAILED");
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * Moves a published instance into {@code TEST_DEPLOY}: BUILD already succeeded and the
     * built image is on the instance, exactly as the BUILD handler leaves it. The instance is
     * still {@code PENDING}, so the orchestrator enters the node through its normal
     * {@code startInstance}/{@code startNode} compare-and-set and calls the handler after the
     * commit.
     */
    private void enterTestDeploy() {
        enterTestDeploy(DIGEST);
    }

    private void enterTestDeploy(String imageDigest) {
        jdbc.update("UPDATE pipeline_instance SET current_node = 'TEST_DEPLOY', image_digest = ?"
            + " WHERE id = ?", imageDigest, instanceId);
        jdbc.update("UPDATE pipeline_node SET status = 'SUCCESS', started_at = NOW(3),"
            + " finished_at = NOW(3) WHERE instance_id = ? AND node_type = 'BUILD'", instanceId);
        jdbc.update("UPDATE node_task SET status = 'SUCCESS', started_at = NOW(3),"
            + " finished_at = NOW(3) WHERE instance_id = ? AND node_type = 'BUILD'", instanceId);
        orchestrator.advance(instanceId);
    }

    private long taskIdFor(long deId) {
        Long id = jdbc.queryForObject(
            "SELECT id FROM node_task WHERE instance_id = ? AND node_type = 'TEST_DEPLOY'"
                + " AND target_id = ?", Long.class, instanceId, deId);
        return id == null ? 0L : id;
    }

    private String taskStatus(long taskId) {
        return jdbc.queryForObject("SELECT status FROM node_task WHERE id = ?", String.class,
            taskId);
    }

    private String externalId(long taskId) {
        return jdbc.queryForObject("SELECT external_id FROM node_task WHERE id = ?", String.class,
            taskId);
    }

    private String externalUrl(long taskId) {
        return jdbc.queryForObject("SELECT external_url FROM node_task WHERE id = ?", String.class,
            taskId);
    }

    private String resultSummary(long taskId) {
        return jdbc.queryForObject("SELECT result_summary FROM node_task WHERE id = ?",
            String.class, taskId);
    }

    private Instant deadlineAt(long taskId) {
        java.sql.Timestamp value = jdbc.queryForObject(
            "SELECT deadline_at FROM node_task WHERE id = ?", java.sql.Timestamp.class, taskId);
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

    private Instant testReadyAt() {
        java.sql.Timestamp value = jdbc.queryForObject(
            "SELECT test_ready_at FROM pipeline_instance WHERE id = ?",
            java.sql.Timestamp.class, instanceId);
        return value == null ? null : value.toInstant();
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
                // Only TEST_DEPLOY is under test; the other nodes do nothing.
            }
        };
    }

    /** Stub engine: records each deploy trigger and can fail one DE by its name. */
    private static final class StubEngine implements CicdEngine {

        private final List<DeployCall> deployCalls = new ArrayList<>();
        private final Map<String, RuntimeException> deployFailures = new HashMap<>();

        @Override
        public String name() {
            return "STUB";
        }

        @Override
        public TriggerResult triggerBuild(BuildCmd cmd) {
            throw new UnsupportedOperationException("no build in the TEST_DEPLOY test");
        }

        @Override
        public TriggerResult triggerDeploy(DeployCmd cmd) {
            deployCalls.add(new DeployCall(cmd));
            RuntimeException failure = deployFailures.get(cmd.deName());
            if (failure != null) {
                throw failure;
            }
            int seq = deployCalls.size();
            return new TriggerResult("dep-" + seq,
                "https://coding.example/deploy/" + seq);
        }

        @Override
        public EngineResult query(String externalId, JobKind kind) {
            throw new UnsupportedOperationException("no query in the TEST_DEPLOY test");
        }

        @Override
        public String logs(String externalId, JobKind kind, int offset, int limit) {
            throw new UnsupportedOperationException("no logs in the TEST_DEPLOY test");
        }

        @Override
        public void cancel(String externalId) {
            throw new UnsupportedOperationException("no cancel in the TEST_DEPLOY test");
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

    /** Records the domain events republished by the Spring event bus. */
    static final class EventRecorder {

        final List<NodeTaskCompletedEvent> taskCompleted = new CopyOnWriteArrayList<>();
        final List<TestEnvReadyEvent> testEnvReady = new CopyOnWriteArrayList<>();

        @EventListener
        void onTaskCompleted(NodeTaskCompletedEvent event) {
            taskCompleted.add(event);
        }

        @EventListener
        void onTestEnvReady(TestEnvReadyEvent event) {
            testEnvReady.add(event);
        }

        void clear() {
            taskCompleted.clear();
            testEnvReady.clear();
        }
    }

    @TestConfiguration
    static class EventRecorderConfig {

        @Bean
        EventRecorder eventRecorder() {
            return new EventRecorder();
        }
    }
}