package com.sf.release.pipeline;

import com.sf.release.application.ItemAppService;
import com.sf.release.application.ItemAppService.CreateItemCmd;
import com.sf.release.application.PublishAppService;
import com.sf.release.domain.pipeline.InstanceStatus;
import com.sf.release.domain.pipeline.NodeHandler;
import com.sf.release.domain.pipeline.NodeHandlerRegistry;
import com.sf.release.domain.pipeline.NodeStatus;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.PipelineOrchestrator;
import com.sf.release.domain.pipeline.event.InstanceFinishedEvent;
import com.sf.release.domain.pipeline.event.NodeCompletedEvent;
import com.sf.release.domain.pipeline.event.NodeTaskCompletedEvent;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.infra.lock.UnitLockService;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pipeline advancement against the real test schema (MySQL, Flyway V1-V8).
 *
 * <p>The orchestrator owns the rules of tech design §5.2: one advance at a time per instance
 * ({@code lock:pipe:{instanceId}}), completion when every non-skipped task of the node is
 * {@code SUCCESS}, stop-on-failure with the still-pending siblings skipped, sticky
 * {@code SUCCESS}, and the unit lease renewed whenever an advance inspects the instance.
 *
 * <p>Handlers are fake counters here: triggering the engine is Task 12/13 work. They prove
 * the node-entry rule ("the next node starts exactly once") without an engine.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(OrchestratorTest.EventRecorderConfig.class)
class OrchestratorTest {

    private static final String OWNER = "01388007";
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 11, 25);
    private static final long OTHER_INSTANCE_ID = 999_999L;

    @Autowired ItemAppService itemService;
    @Autowired PublishAppService publishService;
    @Autowired PipelineOrchestrator orchestrator;
    @Autowired NodeHandlerRegistry handlers;
    @Autowired PipelineRepository repository;
    @Autowired UnitLockService unitLockService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired EventRecorder recorder;

    private final Map<NodeType, AtomicInteger> startCounts = new EnumMap<>(NodeType.class);

    private long subsystemId;
    private long taskId;
    private long unitId;
    private long itemId;
    private long orderId;
    private long instanceId;
    private long testDe;
    private long prodDe;

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
        subsystemId = givenSubsystem("SCC-QUOTA");
        taskId = givenTask(subsystemId, "202611-V6.10.12");
        unitId = givenUnit(subsystemId, "quota-api");
        givenBinding(unitId, "BUILD", "P-BUILD-QUOTA");
        givenBinding(unitId, "TEST_DEPLOY", "P-TEST-QUOTA");
        givenBinding(unitId, "PROD_DEPLOY", "P-PROD-QUOTA");
        testDe = givenDe(unitId, "TEST", "quota-sit", 0);
        prodDe = givenDe(unitId, "PROD", "quota-prod-a", 1);
        itemId = itemService.create(taskId,
            new CreateItemCmd(unitId, "release/202611", "配额优化", PLAN_DATE));

        PublishAppService.PublishResult published = publishService.publish(itemId);
        Map<String, Object> instance = jdbc.queryForMap(
            "SELECT id, order_id FROM pipeline_instance WHERE pipeline_no = ?",
            published.pipelineNo());
        instanceId = ((Number) instance.get("id")).longValue();
        orderId = ((Number) instance.get("order_id")).longValue();

        handlers.clear();
        startCounts.clear();
        recorder.clear();
        registerCountingHandlers();
    }

    @AfterEach
    void clearUserContext() {
        TestUserContext.clear();
        handlers.clear();
    }

    // ------------------------------------------------------------------ idempotent advance

    @Test
    void duplicate_completion_event_advances_only_once() throws Exception {
        orchestrator.advance(instanceId);
        List<Long> buildTasks = taskIds(NodeType.BUILD);
        orchestrator.onTaskCompleted(buildTasks.get(0), NodeTaskStatus.SUCCESS, null, null);
        orchestrator.onTaskCompleted(buildTasks.get(1), NodeTaskStatus.SUCCESS, null, null);
        resetStartCounts();

        long imageBuild = buildTasks.get(2);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                orchestrator.onTaskCompleted(imageBuild, NodeTaskStatus.SUCCESS,
                    "sha256:duplicate", null);
                return null;
            }));
        }
        go.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(taskStatus(imageBuild)).isEqualTo("SUCCESS");
        assertThat(buildNodeFinishedAt()).as("the BUILD node completes exactly once").isNotNull();
        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("SUCCESS");
        assertThat(starts(NodeType.TEST_DEPLOY))
            .as("the next node is entered exactly once despite ten callbacks")
            .isEqualTo(1);
        assertThat(nodeStatus(NodeType.TEST_DEPLOY)).isEqualTo("RUNNING");
        assertThat(instanceStatus()).isEqualTo("RUNNING");
        assertThat(currentNode()).isEqualTo("TEST_DEPLOY");
        assertThat(instanceImageDigest()).isEqualTo("sha256:duplicate");
        assertThat(recorder.nodeCompleted)
            .filteredOn(event -> event.nodeType() == NodeType.BUILD
                && event.status() == NodeStatus.SUCCESS)
            .hasSize(1);
        assertThat(recorder.taskCompleted)
            .filteredOn(event -> event.taskId() == imageBuild)
            .hasSize(1);
    }

    @Test
    void out_of_order_success_then_running_does_not_regress() {
        orchestrator.advance(instanceId);
        long imageBuild = taskIds(NodeType.BUILD).get(2);

        orchestrator.onTaskCompleted(imageBuild, NodeTaskStatus.SUCCESS, "sha256:ab", null);
        Instant finishedAt = taskFinishedAt(imageBuild);
        orchestrator.onTaskCompleted(imageBuild, NodeTaskStatus.RUNNING, null, null);

        assertThat(taskStatus(imageBuild)).isEqualTo("SUCCESS");
        assertThat(taskFinishedAt(imageBuild))
            .as("the late RUNNING callback cannot clear the completion timestamp")
            .isEqualTo(finishedAt);
        assertThat(instanceImageDigest()).isEqualTo("sha256:ab");
        assertThat(starts(NodeType.TEST_DEPLOY)).isZero();
    }

    @Test
    void node_completes_only_when_all_tasks_succeed() {
        orchestrator.advance(instanceId);
        List<Long> buildTasks = taskIds(NodeType.BUILD);
        orchestrator.onTaskCompleted(buildTasks.get(0), NodeTaskStatus.SUCCESS, null, null);
        orchestrator.onTaskCompleted(buildTasks.get(1), NodeTaskStatus.SUCCESS, null, null);
        orchestrator.onTaskCompleted(buildTasks.get(2), NodeTaskStatus.RUNNING, null, null);

        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("RUNNING");
        assertThat(starts(NodeType.TEST_DEPLOY)).isZero();
        assertThat(instanceStatus()).isEqualTo("RUNNING");

        orchestrator.onTaskCompleted(buildTasks.get(2), NodeTaskStatus.SUCCESS, null, null);

        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("SUCCESS");
        assertThat(starts(NodeType.TEST_DEPLOY)).isEqualTo(1);
        assertThat(nodeStatus(NodeType.TEST_DEPLOY)).isEqualTo("RUNNING");
        assertThat(currentNode()).isEqualTo("TEST_DEPLOY");
    }

    @Test
    void concurrent_completions_of_the_last_two_tasks_still_complete_the_node() throws Exception {
        orchestrator.advance(instanceId);
        List<Long> buildTasks = taskIds(NodeType.BUILD);
        orchestrator.onTaskCompleted(buildTasks.get(0), NodeTaskStatus.SUCCESS, null, null);

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> first = pool.submit(() -> {
            go.await();
            orchestrator.onTaskCompleted(buildTasks.get(1), NodeTaskStatus.SUCCESS, null, null);
            return null;
        });
        Future<?> second = pool.submit(() -> {
            go.await();
            orchestrator.onTaskCompleted(buildTasks.get(2), NodeTaskStatus.SUCCESS, null, null);
            return null;
        });
        go.countDown();
        first.get(30, TimeUnit.SECONDS);
        second.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("SUCCESS");
        assertThat(starts(NodeType.TEST_DEPLOY))
            .as("the bounded lock retry lets the loser see the winner's completion; one entry")
            .isEqualTo(1);
        assertThat(nodeStatus(NodeType.TEST_DEPLOY)).isEqualTo("RUNNING");
    }

    @Test
    void retried_task_uses_the_latest_attempt_for_completion() {
        long failedAttempt = taskIds(NodeType.BUILD).get(0);
        jdbc.update("UPDATE node_task SET status = 'FAILED', finished_at = NOW(3),"
            + " result_summary = 'previous attempt failed' WHERE id = ?", failedAttempt);
        jdbc.update("INSERT INTO node_task (instance_id, node_type, task_type, batch_no,"
            + " target_id, status, attempt)"
            + " SELECT instance_id, node_type, task_type, batch_no, target_id, 'PENDING', 1"
            + " FROM node_task WHERE id = ?", failedAttempt);
        long retriedAttempt = jdbc.queryForObject(
            "SELECT id FROM node_task WHERE instance_id = ? AND node_type = 'BUILD'"
                + " AND task_type = 'CODE_CHECK' AND attempt = 1", Long.class, instanceId);

        orchestrator.advance(instanceId);
        for (long taskId : taskIds(NodeType.BUILD)) {
            if (taskId != failedAttempt && taskId != retriedAttempt) {
                orchestrator.onTaskCompleted(taskId, NodeTaskStatus.SUCCESS, null, null);
            }
        }
        orchestrator.onTaskCompleted(retriedAttempt, NodeTaskStatus.SUCCESS, null, null);

        assertThat(taskStatus(failedAttempt))
            .as("the retained old attempt row is left intact")
            .isEqualTo("FAILED");
        assertThat(nodeStatus(NodeType.BUILD))
            .as("only the latest attempt of a logical task decides node completion")
            .isEqualTo("SUCCESS");
        assertThat(starts(NodeType.TEST_DEPLOY)).isEqualTo(1);
    }

    @Test
    void check_tasks_do_not_gate_but_a_check_only_node_never_completes_on_its_own() {
        orchestrator.advance(instanceId);
        completeBuildNode();
        orchestrator.onTaskCompleted(taskIds(NodeType.TEST_DEPLOY).get(0),
            NodeTaskStatus.SUCCESS, null, null);
        assertThat(nodeStatus(NodeType.VERIFY)).isEqualTo("RUNNING");
        assertThat(taskIds(NodeType.VERIFY)).hasSize(7);
        assertThat(taskIds(NodeType.VERIFY))
            .allSatisfy(taskId -> assertThat(taskStatus(taskId)).isEqualTo("PENDING"));

        orchestrator.advance(instanceId);

        // C3: a node needs at least one counted (non-CHECK) task before it can complete. The
        // CHECK results are asynchronous, so VERIFY belongs to the gate/check path (P2) and must
        // not be auto-completed by an empty completion fold.
        assertThat(nodeStatus(NodeType.VERIFY)).isEqualTo("RUNNING");
        assertThat(starts(NodeType.PROD_DEPLOY)).isZero();
        assertThat(taskIds(NodeType.VERIFY))
            .allSatisfy(taskId -> assertThat(taskStatus(taskId)).isEqualTo("PENDING"));
        assertThat(itemStatus()).isEqualTo("TESTING");

        long verifyNodeId = jdbc.queryForObject(
            "SELECT id FROM pipeline_node WHERE instance_id = ? AND node_type = 'VERIFY'",
            Long.class, instanceId);
        assertThat(repository.finishNodeIfComplete(verifyNodeId, Instant.now()))
            .as("the SQL completion guard mirrors the empty-node rule")
            .isFalse();
    }

    @Test
    void failure_skips_pending_tasks_and_keeps_success_rows() {
        orchestrator.advance(instanceId);
        List<Long> buildTasks = taskIds(NodeType.BUILD);
        long codeCheck = buildTasks.get(0);
        long pkgBuild = buildTasks.get(1);
        long imageBuild = buildTasks.get(2);
        orchestrator.onTaskCompleted(codeCheck, NodeTaskStatus.SUCCESS, null, null);

        orchestrator.onTaskCompleted(pkgBuild, NodeTaskStatus.FAILED, null, "boom");

        assertThat(taskStatus(codeCheck))
            .as("an already successful task row is left intact")
            .isEqualTo("SUCCESS");
        assertThat(taskStatus(imageBuild))
            .as("the still-pending sibling of a failed node is skipped")
            .isEqualTo("SKIPPED");
        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("FAILED");
        assertThat(instanceStatus()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
            "SELECT end_at FROM pipeline_instance WHERE id = ?", Timestamp.class, instanceId))
            .isNotNull();
        assertThat(starts(NodeType.TEST_DEPLOY))
            .as("a failed node never starts the next node")
            .isZero();
        assertThat(recorder.instanceFinished).hasSize(1);
        assertThat(recorder.instanceFinished.get(0).status()).isEqualTo(InstanceStatus.FAILED);
        assertThat(recorder.instanceFinished.get(0).reason()).contains("boom");
        assertThat(unitLockRows()).isZero();
        assertThat(unitLockService.acquire(unitId, OTHER_INSTANCE_ID))
            .as("a failed instance releases the unit so the C15 republish can acquire it")
            .isTrue();
    }

    @Test
    void done_releases_the_unit_lock() {
        enterLastNode();

        orchestrator.onTaskCompleted(taskIds(NodeType.PROD_DEPLOY).get(0),
            NodeTaskStatus.SUCCESS, null, null);

        assertThat(nodeStatus(NodeType.PROD_DEPLOY)).isEqualTo("SUCCESS");
        assertThat(instanceStatus()).isEqualTo("DONE");
        assertThat(unitLockRows())
            .as("a terminal instance deletes its deploy_unit_lock row after the commit")
            .isZero();
        assertThat(unitLockService.acquire(unitId, OTHER_INSTANCE_ID)).isTrue();
    }

    @Test
    void missing_handler_for_the_next_node_pauses_without_rolling_the_completion_back() {
        orchestrator.advance(instanceId);
        List<Long> buildTasks = taskIds(NodeType.BUILD);
        orchestrator.onTaskCompleted(buildTasks.get(0), NodeTaskStatus.SUCCESS, null, null);
        orchestrator.onTaskCompleted(buildTasks.get(1), NodeTaskStatus.SUCCESS, null, null);

        // The production registry only ships BUILD and TEST_DEPLOY (phase 1 boundary), so
        // TEST_DEPLOY is now the next node while no handler is registered for it.
        handlers.clear();
        handlers.register(new CountingHandler(NodeType.BUILD));
        orchestrator.onTaskCompleted(buildTasks.get(2), NodeTaskStatus.SUCCESS, "sha256:pause",
            null);

        assertThat(nodeStatus(NodeType.BUILD))
            .as("the completion that was already written is not rolled back")
            .isEqualTo("SUCCESS");
        assertThat(nodeStatus(NodeType.TEST_DEPLOY)).isEqualTo("PENDING");
        assertThat(instanceStatus()).isEqualTo("PAUSED");
        assertThat(currentNode()).isEqualTo("TEST_DEPLOY");
        assertThat(statusReason()).contains("TEST_DEPLOY").contains("NodeHandler");
        assertThat(unitLockRows())
            .as("a paused pipeline no longer holds the deployment unit")
            .isZero();
        assertThat(recorder.instanceFinished)
            .anySatisfy(event -> assertThat(event.status()).isEqualTo(InstanceStatus.PAUSED));
        assertThat(recorder.nodeCompleted)
            .anySatisfy(event -> {
                assertThat(event.nodeType()).isEqualTo(NodeType.BUILD);
                assertThat(event.status()).isEqualTo(NodeStatus.SUCCESS);
            });

        // A replay (reconcile advance repair or a duplicate callback) must not loop on the
        // blocked node entry.
        orchestrator.advance(instanceId);
        assertThat(instanceStatus()).isEqualTo("PAUSED");
        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("SUCCESS");
        assertThat(nodeStatus(NodeType.TEST_DEPLOY)).isEqualTo("PENDING");
        assertThat(recorder.instanceFinished)
            .filteredOn(event -> event.status() == InstanceStatus.PAUSED)
            .as("the pause event is published once")
            .hasSize(1);
    }

    @Test
    void advance_on_a_terminal_instance_heals_a_stale_unit_lock() {
        jdbc.update("UPDATE pipeline_instance SET status = 'FAILED', end_at = NOW(3)"
            + " WHERE id = ?", instanceId);
        assertThat(unitLockRows()).isEqualTo(1);

        orchestrator.advance(instanceId);

        assertThat(unitLockRows())
            .as("an already-terminal instance releases a leftover unit lock")
            .isZero();
        assertThat(unitLockService.acquire(unitId, OTHER_INSTANCE_ID)).isTrue();
    }

    // ------------------------------------------------------------------ unit lock renewal

    @Test
    void advance_renews_the_unit_lock() {
        orchestrator.advance(instanceId);
        jdbc.update("UPDATE deploy_unit_lock SET expire_at = ? WHERE unit_id = ?",
            Timestamp.from(Instant.now().plus(Duration.ofMinutes(10))), unitId);
        Instant before = lockExpireAt();

        orchestrator.advance(instanceId);

        Instant after = lockExpireAt();
        assertThat(after).isAfter(before);
        assertThat(Duration.between(Instant.now(), after))
            .as("a successful advance renews the unit lease to four hours")
            .isBetween(Duration.ofHours(3).plusMinutes(55), Duration.ofHours(4).plusMinutes(5));
    }

    @Test
    void lost_unit_lock_fails_the_instance_with_a_clear_reason() {
        assertThat(unitLockService.release(unitId, instanceId)).isTrue();
        assertThat(unitLockService.acquire(unitId, OTHER_INSTANCE_ID)).isTrue();

        orchestrator.advance(instanceId);

        assertThat(instanceStatus()).isEqualTo("FAILED");
        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("PENDING");
        assertThat(starts(NodeType.BUILD))
            .as("advancing stops instead of entering the node without its unit lease")
            .isZero();
        assertThat(recorder.instanceFinished).hasSize(1);
        InstanceFinishedEvent finished = recorder.instanceFinished.get(0);
        assertThat(finished.status()).isEqualTo(InstanceStatus.FAILED);
        assertThat(finished.reason())
            .as("the lock-lost failure carries a reason an operator can act on")
            .contains("锁丢失")
            .contains("unitId=" + unitId);
    }

    // ------------------------------------------------------------------ handler registry

    @Test
    void entering_a_node_without_a_handler_throws_loudly() {
        handlers.clear();

        assertThatThrownBy(() -> orchestrator.advance(instanceId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("BUILD");

        assertThat(instanceStatus())
            .as("the failed node entry rolls back the instances state changes")
            .isEqualTo("PENDING");
        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("PENDING");
        assertThat(starts(NodeType.BUILD)).isZero();

        registerCountingHandlers();
        orchestrator.advance(instanceId);
        assertThat(starts(NodeType.BUILD)).isEqualTo(1);
        assertThat(instanceStatus()).isEqualTo("RUNNING");
    }

    @Test
    void handler_failure_after_commit_fails_the_node() {
        handlers.clear();
        handlers.register(new NodeHandler() {
            @Override
            public NodeType nodeType() {
                return NodeType.BUILD;
            }

            @Override
            public void start(PipelineInstance instance) {
                throw new IllegalStateException("engine down");
            }
        });

        orchestrator.advance(instanceId);

        assertThat(nodeStatus(NodeType.BUILD)).isEqualTo("FAILED");
        assertThat(instanceStatus()).isEqualTo("FAILED");
        assertThat(recorder.instanceFinished).hasSize(1);
        assertThat(recorder.instanceFinished.get(0).reason()).contains("engine down");
        assertThat(unitLockRows())
            .as("the handler-failure path also releases the unit")
            .isZero();
    }

    @Test
    void handler_runs_outside_the_transaction_holding_the_instance_row() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        handlers.clear();
        handlers.register(new NodeHandler() {
            @Override
            public NodeType nodeType() {
                return NodeType.BUILD;
            }

            @Override
            public void start(PipelineInstance instance) {
                entered.countDown();
                try {
                    releaseHandler.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> advancing = pool.submit(() -> {
            orchestrator.advance(instanceId);
            return null;
        });
        assertThat(entered.await(5, TimeUnit.SECONDS))
            .as("the handler is running")
            .isTrue();

        Future<Integer> rowUpdate = pool.submit(() -> jdbc.update(
            "UPDATE pipeline_instance SET commit_sha = ? WHERE id = ?", "deadbeef", instanceId));
        assertThat(rowUpdate.get(3, TimeUnit.SECONDS))
            .as("the instance row is not locked while the handler runs")
            .isEqualTo(1);

        long firstTask = taskIds(NodeType.BUILD).get(0);
        orchestrator.onTaskCompleted(firstTask, NodeTaskStatus.SUCCESS, null, null);
        assertThat(taskStatus(firstTask))
            .as("a completion commits while the slow handler still runs")
            .isEqualTo("SUCCESS");

        releaseHandler.countDown();
        advancing.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------ item state wiring

    @Test
    void build_success_moves_the_item_to_testing() {
        orchestrator.advance(instanceId);
        completeBuildNode();

        assertThat(itemStatus()).isEqualTo("TESTING");
    }

    @Test
    void build_failure_moves_the_item_to_failed() {
        orchestrator.advance(instanceId);
        List<Long> buildTasks = taskIds(NodeType.BUILD);
        orchestrator.onTaskCompleted(buildTasks.get(0), NodeTaskStatus.FAILED, null, "compile");

        assertThat(itemStatus()).isEqualTo("FAILED");
    }

    @Test
    void test_deploy_success_keeps_the_item_testing() {
        orchestrator.advance(instanceId);
        completeBuildNode();
        long testTask = taskIds(NodeType.TEST_DEPLOY).get(0);

        orchestrator.onTaskCompleted(testTask, NodeTaskStatus.SUCCESS, null, null);

        assertThat(itemStatus()).isEqualTo("TESTING");
        assertThat(rowVersion()).isEqualTo(3);
        assertThat(nodeStatus(NodeType.VERIFY)).isEqualTo("RUNNING");
    }

    @Test
    void test_deploy_failure_moves_the_item_to_failed() {
        orchestrator.advance(instanceId);
        completeBuildNode();
        long testTask = taskIds(NodeType.TEST_DEPLOY).get(0);

        orchestrator.onTaskCompleted(testTask, NodeTaskStatus.FAILED, null, "deploy failed");

        assertThat(itemStatus()).isEqualTo("FAILED");
    }

    @Test
    void prod_success_moves_the_item_to_done() {
        jdbc.update("UPDATE release_item SET status = 'PROD_DEPLOYING' WHERE id = ?", itemId);

        eventPublisher.publishEvent(new NodeCompletedEvent(instanceId, orderId,
            NodeType.PROD_DEPLOY, NodeStatus.SUCCESS, null, Instant.now()));

        assertThat(itemStatus()).isEqualTo("DONE");
    }

    @Test
    void prod_failure_moves_the_item_to_failed() {
        jdbc.update("UPDATE release_item SET status = 'PROD_DEPLOYING' WHERE id = ?", itemId);

        eventPublisher.publishEvent(new NodeCompletedEvent(instanceId, orderId,
            NodeType.PROD_DEPLOY, NodeStatus.FAILED, "prod boom", Instant.now()));

        assertThat(itemStatus()).isEqualTo("FAILED");
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private void completeBuildNode() {
        for (long taskId : taskIds(NodeType.BUILD)) {
            orchestrator.onTaskCompleted(taskId, NodeTaskStatus.SUCCESS, null, null);
        }
    }

    /**
     * Seeds the instance at the last node ({@code PROD_DEPLOY}): the three earlier nodes and
     * their tasks succeeded, the last node is {@code RUNNING}. Reaching it through the pipeline
     * is impossible by design in phase 1 (no VERIFY/PROD_DEPLOY handler, and a check-only node
     * never completes), so the terminal state is exercised from the state it really has.
     */
    private void enterLastNode() {
        jdbc.update("UPDATE pipeline_instance SET status = 'RUNNING',"
            + " current_node = 'PROD_DEPLOY', start_at = NOW(3) WHERE id = ?", instanceId);
        jdbc.update("UPDATE pipeline_node SET status = 'SUCCESS', started_at = NOW(3),"
            + " finished_at = NOW(3) WHERE instance_id = ?"
            + " AND node_type IN ('BUILD', 'TEST_DEPLOY', 'VERIFY')", instanceId);
        jdbc.update("UPDATE pipeline_node SET status = 'RUNNING', started_at = NOW(3)"
            + " WHERE instance_id = ? AND node_type = 'PROD_DEPLOY'", instanceId);
        jdbc.update("UPDATE node_task SET status = 'SUCCESS', finished_at = NOW(3)"
            + " WHERE instance_id = ? AND node_type <> 'PROD_DEPLOY'", instanceId);
    }

    private void registerCountingHandlers() {
        for (NodeType type : NodeType.values()) {
            handlers.register(new CountingHandler(type));
        }
    }

    private void resetStartCounts() {
        for (AtomicInteger count : startCounts.values()) {
            count.set(0);
        }
    }

    private int starts(NodeType type) {
        AtomicInteger count = startCounts.get(type);
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
            startCounts.computeIfAbsent(type, ignored -> new AtomicInteger()).incrementAndGet();
        }
    }

    private List<Long> taskIds(NodeType nodeType) {
        return jdbc.queryForList(
            "SELECT id FROM node_task WHERE instance_id = ? AND node_type = ? ORDER BY id",
            Long.class, instanceId, nodeType.name());
    }

    private String taskStatus(long taskId) {
        return jdbc.queryForObject("SELECT status FROM node_task WHERE id = ?",
            String.class, taskId);
    }

    private Instant taskFinishedAt(long taskId) {
        Timestamp finishedAt = jdbc.queryForObject(
            "SELECT finished_at FROM node_task WHERE id = ?", Timestamp.class, taskId);
        return finishedAt == null ? null : finishedAt.toInstant();
    }

    private String nodeStatus(NodeType nodeType) {
        return jdbc.queryForObject(
            "SELECT status FROM pipeline_node WHERE instance_id = ? AND node_type = ?",
            String.class, instanceId, nodeType.name());
    }

    private Instant buildNodeFinishedAt() {
        Timestamp finishedAt = jdbc.queryForObject(
            "SELECT finished_at FROM pipeline_node WHERE instance_id = ? AND node_type = ?",
            Timestamp.class, instanceId, NodeType.BUILD.name());
        return finishedAt == null ? null : finishedAt.toInstant();
    }

    private String instanceStatus() {
        return jdbc.queryForObject("SELECT status FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private String statusReason() {
        return jdbc.queryForObject("SELECT status_reason FROM pipeline_instance WHERE id = ?",
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

    private String itemStatus() {
        return jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?",
            String.class, itemId);
    }

    private int rowVersion() {
        Integer version = jdbc.queryForObject(
            "SELECT row_version FROM release_item WHERE id = ?", Integer.class, itemId);
        return version == null ? 0 : version;
    }

    private Instant lockExpireAt() {
        Timestamp expireAt = jdbc.queryForObject(
            "SELECT expire_at FROM deploy_unit_lock WHERE unit_id = ?", Timestamp.class, unitId);
        return expireAt == null ? null : expireAt.toInstant();
    }

    private int unitLockRows() {
        Integer rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM deploy_unit_lock WHERE unit_id = ?", Integer.class, unitId);
        return rows == null ? 0 : rows;
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

    /** Records the domain events the application event bus republishes. */
    static final class EventRecorder {

        final List<NodeTaskCompletedEvent> taskCompleted = new CopyOnWriteArrayList<>();
        final List<NodeCompletedEvent> nodeCompleted = new CopyOnWriteArrayList<>();
        final List<InstanceFinishedEvent> instanceFinished = new CopyOnWriteArrayList<>();

        @EventListener
        void onTaskCompleted(NodeTaskCompletedEvent event) {
            taskCompleted.add(event);
        }

        @EventListener
        void onNodeCompleted(NodeCompletedEvent event) {
            nodeCompleted.add(event);
        }

        @EventListener
        void onInstanceFinished(InstanceFinishedEvent event) {
            instanceFinished.add(event);
        }

        void clear() {
            taskCompleted.clear();
            nodeCompleted.clear();
            instanceFinished.clear();
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