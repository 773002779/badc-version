package com.sf.release.rollback;

import com.sf.release.application.RollbackService;
import com.sf.release.common.security.Permissions;
import com.sf.release.domain.engine.BuildCmd;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.DeployCmd;
import com.sf.release.domain.engine.EngineResult;
import com.sf.release.domain.engine.JobKind;
import com.sf.release.domain.engine.TriggerResult;
import com.sf.release.domain.pipeline.NodeTaskStatus;
import com.sf.release.domain.pipeline.PipelineOrchestrator;
import com.sf.release.infra.lock.InMemoryDistributedLock;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end rollback execution (P4-A R2): a real rollback instance is driven to {@code DONE} by the
 * real orchestrator and the real {@code ProdDeployNodeHandler}, with a stub engine naming the
 * deployed DEs. The assertions are the convergence rules of the design §3/§5:
 *
 * <ul>
 *   <li>the highest <em>original</em> production batch is rolled back first (reversed batches);</li>
 *   <li>{@code DONE} → record {@code SUCCESS}, source item {@code ROLLED_BACK}, source order
 *       {@code ROLLED_BACK};</li>
 *   <li>{@code FAILED} → record {@code FAILED} with the reason and the item unchanged;</li>
 *   <li>a source item that is no longer {@code DONE} is left alone (the pipeline completion is not
 *       {@code PROD_OK});</li>
 *   <li>a freeze never pauses a rollback (no boundary probe), it only warns;</li>
 *   <li>no {@code PROD_OK} item-failure row is written for the rollback instance.</li>
 * </ul>
 */
@SpringBootTest(classes = {com.sf.release.ReleaseApplication.class,
    RollbackFlowTest.StubEngineConfig.class})
@ActiveProfiles("test")
class RollbackFlowTest {

    private static final String USER = "rbflow-ops";
    private static final String CURRENT = "sha256:flow-current";
    private static final String TARGET = "sha256:flow-target";
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final String REASON = "线上故障止损";

    @Autowired RollbackService rollbacks;
    @Autowired PipelineOrchestrator orchestrator;
    @Autowired StubEngine engine;
    @Autowired JdbcTemplate jdbc;

    private final AtomicInteger seq = new AtomicInteger();

    private long subsystemId;
    private long unitId;
    private long taskId;
    private long batchOneDe;
    private long batchTwoDe;
    private long batchThreeDe;
    private long sourceItemId;
    private long sourceOrderId;
    private long sourceInstanceId;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        InMemoryDistributedLock.clearAllLeases();
        engine.clear();

        subsystemId = givenSubsystem("RBFLOW-SYS");
        unitId = givenUnit(subsystemId, "rbflow-unit");
        taskId = givenTask(subsystemId, "RBFLOW-TASK");
        givenBinding(unitId, "PROD_DEPLOY", "P-PROD-RBFLOW");
        batchOneDe = givenDe(unitId, "PROD", "flow-prod-1", 1);
        batchTwoDe = givenDe(unitId, "DR", "flow-dr-2", 2);
        batchThreeDe = givenDe(unitId, "BLUE", "flow-blue-3", 3);

        Instant now = Instant.now();
        Version source = addVersion(CURRENT, now.minusSeconds(7200), "ops-old", batchOneDe, 1);
        addVersion(TARGET, now.minusSeconds(10800), "ops-old", batchOneDe, 1);
        sourceItemId = source.itemId();
        sourceOrderId = source.orderId();
        sourceInstanceId = source.instanceId();

        TestUserContext.as(USER, Set.of("OPS"), Set.of(Permissions.ROLLBACK_EXECUTE));
    }

    @AfterEach
    void clearUser() {
        TestUserContext.clear();
    }

    // ------------------------------------------------------------------ success

    @Test
    void the_highest_original_batch_runs_first_and_the_instance_converges_to_success() {
        long instanceId = executeRollback();

        orchestrator.advance(instanceId);

        assertThat(engine.triggeredDeNames())
            .as("reversed batches: original batch 3 (BLUE) first")
            .containsExactly("flow-blue-3");
        assertThat(instanceStatus(instanceId)).isEqualTo("RUNNING");
        assertThat(nodeStatus(instanceId)).isEqualTo("RUNNING");

        completeFirstBatch(instanceId, batchThreeDe);
        assertThat(engine.triggeredDeNames())
            .containsExactly("flow-blue-3", "flow-dr-2");

        completeFirstBatch(instanceId, batchTwoDe);
        assertThat(engine.triggeredDeNames())
            .containsExactly("flow-blue-3", "flow-dr-2", "flow-prod-1");

        completeFirstBatch(instanceId, batchOneDe);

        assertThat(instanceStatus(instanceId)).isEqualTo("DONE");
        assertThat(engine.triggeredDeNames())
            .containsExactly("flow-blue-3", "flow-dr-2", "flow-prod-1");

        long rollbackId = rollbackIdOf(instanceId);
        assertThat(recordStatus(rollbackId)).isEqualTo("SUCCESS");
        assertThat(finishedAt(rollbackId)).isNotNull();
        assertThat(failureReason(rollbackId)).isNull();
        assertThat(itemStatus(sourceItemId)).isEqualTo("ROLLED_BACK");
        assertThat(orderStatus(sourceOrderId)).isEqualTo("ROLLED_BACK");
        assertThat(rowVersionOf(sourceItemId))
            .as("the completion moved the item once, through the ROLLBACK event")
            .isEqualTo(1);
        assertThat(itemFailureCount(instanceId))
            .as("a PROD_DEPLOY completion of a rollback is not a PROD_OK item event")
            .isZero();
        assertThat(unitLockCount()).as("the orchestrator releases the terminal lock").isZero();
    }

    // ------------------------------------------------------------------ failure

    @Test
    void a_failed_rollback_batch_fails_the_record_and_leaves_the_item_untouched() {
        long instanceId = executeRollback();

        orchestrator.advance(instanceId);
        long firstTask = taskIdOf(instanceId, batchThreeDe);
        orchestrator.onTaskCompleted(firstTask, NodeTaskStatus.FAILED, null, "deploy job 7001 failed");

        assertThat(instanceStatus(instanceId)).isEqualTo("FAILED");
        assertThat(engine.triggeredDeNames())
            .as("a failed batch never triggers the next one")
            .containsExactly("flow-blue-3");

        long rollbackId = rollbackIdOf(instanceId);
        assertThat(recordStatus(rollbackId)).isEqualTo("FAILED");
        assertThat(failureReason(rollbackId)).contains("7001");
        assertThat(finishedAt(rollbackId)).isNotNull();
        assertThat(itemStatus(sourceItemId)).isEqualTo("DONE");
        assertThat(orderStatus(sourceOrderId)).isEqualTo("DONE");
        assertThat(itemFailureCount(instanceId)).isZero();
    }

    // ------------------------------------------------------------------ source no longer DONE

    @Test
    void a_source_item_that_is_no_longer_done_is_not_changed_by_the_completion() {
        long instanceId = executeRollback();
        // The current online release was rerun after the rollback was requested (or a concurrent
        // release changed the item). PROD_OK must not fire: otherwise the pipeline would move the
        // item to DONE and the completion would then roll it back, which is not this rollback's
        // release state any more.
        jdbc.update("UPDATE release_item SET status = 'PROD_DEPLOYING' WHERE id = ?", sourceItemId);
        jdbc.update("UPDATE release_order SET status = 'EXECUTING' WHERE id = ?", sourceOrderId);

        orchestrator.advance(instanceId);
        completeFirstBatch(instanceId, batchThreeDe);
        completeFirstBatch(instanceId, batchTwoDe);
        completeFirstBatch(instanceId, batchOneDe);

        assertThat(instanceStatus(instanceId)).isEqualTo("DONE");
        long rollbackId = rollbackIdOf(instanceId);
        assertThat(recordStatus(rollbackId))
            .as("the rollback itself succeeded: only the item/order state is left as it was")
            .isEqualTo("SUCCESS");
        assertThat(itemStatus(sourceItemId)).isEqualTo("PROD_DEPLOYING");
        assertThat(orderStatus(sourceOrderId)).isEqualTo("EXECUTING");
        assertThat(rowVersionOf(sourceItemId)).isZero();
        assertThat(itemFailureCount(instanceId)).isZero();
    }

    // ------------------------------------------------------------------ freeze

    @Test
    void a_freeze_warns_but_does_not_pause_a_rollback() {
        givenFreezeInForce();

        RollbackService.ExecuteResult result = rollbacks.execute(unitId, TARGET, REASON);
        assertThat(result.freezeWarning()).contains("封板");
        long instanceId = instanceIdOf(result.pipelineNo());

        orchestrator.advance(instanceId);

        assertThat(instanceStatus(instanceId))
            .as("a rollback is the stop-loss path: the boundary probe must not pause it")
            .isEqualTo("RUNNING");
        assertThat(nodeStatus(instanceId)).isEqualTo("RUNNING");
        assertThat(statusReason(instanceId)).isNull();
        assertThat(engine.triggeredDeNames()).containsExactly("flow-blue-3");
        assertThat(taskStatus(instanceId, batchThreeDe)).isEqualTo("WAITING_EXTERNAL");
    }

    // ------------------------------------------------------------------ helpers

    private long executeRollback() {
        RollbackService.ExecuteResult result = rollbacks.execute(unitId, TARGET, REASON);
        return instanceIdOf(result.pipelineNo());
    }

    private void completeFirstBatch(long instanceId, long deId) {
        orchestrator.onTaskCompleted(taskIdOf(instanceId, deId), NodeTaskStatus.SUCCESS, null, null);
    }

    private long instanceIdOf(String pipelineNo) {
        Long id = jdbc.queryForObject("SELECT id FROM pipeline_instance WHERE pipeline_no = ?",
            Long.class, pipelineNo);
        return id == null ? 0L : id;
    }

    private long rollbackIdOf(long instanceId) {
        Long id = jdbc.queryForObject(
            "SELECT id FROM rollback_record WHERE instance_id = ?", Long.class, instanceId);
        return id == null ? 0L : id;
    }

    private long taskIdOf(long instanceId, long deId) {
        Long id = jdbc.queryForObject(
            "SELECT id FROM node_task WHERE instance_id = ? AND target_id = ? AND task_type ="
                + " 'DEPLOY'", Long.class, instanceId, deId);
        return id == null ? 0L : id;
    }

    private String instanceStatus(long instanceId) {
        return jdbc.queryForObject("SELECT status FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private String nodeStatus(long instanceId) {
        return jdbc.queryForObject(
            "SELECT status FROM pipeline_node WHERE instance_id = ? AND node_type = 'PROD_DEPLOY'",
            String.class, instanceId);
    }

    private String statusReason(long instanceId) {
        return jdbc.queryForObject("SELECT status_reason FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private String taskStatus(long instanceId, long deId) {
        return jdbc.queryForObject("SELECT status FROM node_task WHERE instance_id = ?"
            + " AND target_id = ? AND task_type = 'DEPLOY'", String.class, instanceId, deId);
    }

    private String recordStatus(long rollbackId) {
        return jdbc.queryForObject("SELECT status FROM rollback_record WHERE id = ?", String.class,
            rollbackId);
    }

    private Instant finishedAt(long rollbackId) {
        Timestamp value = jdbc.queryForObject(
            "SELECT finished_at FROM rollback_record WHERE id = ?", Timestamp.class, rollbackId);
        return value == null ? null : value.toInstant();
    }

    private String failureReason(long rollbackId) {
        return jdbc.queryForObject("SELECT failure_reason FROM rollback_record WHERE id = ?",
            String.class, rollbackId);
    }

    private String itemStatus(long itemId) {
        return jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?", String.class,
            itemId);
    }

    private int rowVersionOf(long itemId) {
        Integer value = jdbc.queryForObject("SELECT row_version FROM release_item WHERE id = ?",
            Integer.class, itemId);
        return value == null ? 0 : value;
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM release_order WHERE id = ?", String.class,
            orderId);
    }

    private int itemFailureCount(long instanceId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pipeline_item_failure WHERE instance_id = ?", Integer.class,
            instanceId);
        return count == null ? 0 : count;
    }

    private int unitLockCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM deploy_unit_lock",
            Integer.class);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------ fixtures

    private record Version(long orderId, long itemId, long instanceId, String publishNo) {
    }

    private Version addVersion(String digest, Instant finishedAt, String executedBy, long deId,
                               int batchNo) {
        int n = seq.incrementAndGet();
        String publishNo = "PO-RBF-V" + n;
        long itemId = givenItem(taskId, unitId, "CR-RBF-V" + n, "release/rbf-v" + n);
        long orderId = givenOrder(itemId, publishNo, "DONE", executedBy);
        long instanceId = givenInstance(orderId, "PL-RBF-V" + n, "INITIAL", digest);
        givenDeployTask(instanceId, deId, batchNo, finishedAt);
        return new Version(orderId, itemId, instanceId, publishNo);
    }

    private long givenSubsystem(String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, USER);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private long givenUnit(long subsystem, String name) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, owner_id, created_at, updated_at)"
            + " VALUES (?, ?, ?, NOW(3), NOW(3))", subsystem, name, USER);
        Long id = jdbc.queryForObject("SELECT id FROM deploy_unit WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private long givenTask(long subsystem, String name) {
        jdbc.update("INSERT INTO release_task (subsystem_id, name, creator, status, created_at,"
            + " updated_at) VALUES (?, ?, ?, 'ACTIVE', NOW(3), NOW(3))", subsystem, name, USER);
        Long id = jdbc.queryForObject("SELECT id FROM release_task WHERE name = ?", Long.class,
            name);
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

    private long givenItem(long task, long unit, String crid, String branch) {
        jdbc.update("INSERT INTO release_item (task_id, crid, type, unit_id, build_plan_id,"
                + " source_branch, version, plan_release_date, status, gate_status, test_status,"
                + " row_version, created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, 'P-BUILD', ?, 'v1', '2026-11-25', 'DONE',"
                + " 'CHECK_PASS', 'TEST_PASS', 0, NOW(3), NOW(3))",
            task, crid, unit, branch);
        Long id = jdbc.queryForObject("SELECT id FROM release_item WHERE crid = ?", Long.class,
            crid);
        return id == null ? 0L : id;
    }

    private long givenOrder(long itemId, String publishNo, String status, String executedBy) {
        jdbc.update("INSERT INTO release_order (publish_no, item_id, planned_release_time,"
                + " window_result, deadline_at, status, row_version, created_at, executed_by)"
                + " VALUES (?, ?, NOW(3), 'OUT', DATE_ADD(NOW(3), INTERVAL 2 HOUR), ?, 0, NOW(3),"
                + " ?)",
            publishNo, itemId, status, executedBy);
        Long id = jdbc.queryForObject("SELECT id FROM release_order WHERE publish_no = ?",
            Long.class, publishNo);
        return id == null ? 0L : id;
    }

    private long givenInstance(long orderId, String pipelineNo, String triggerType, String digest) {
        jdbc.update("INSERT INTO pipeline_instance (order_id, pipeline_no, exec_type, trigger_type,"
                + " current_node, status, image_digest, commit_sha, start_at, end_at, created_at)"
                + " VALUES (?, ?, 'ONLINE', ?, 'PROD_DEPLOY', 'DONE', ?, ?, NOW(3), NOW(3),"
                + " NOW(3))",
            orderId, pipelineNo, triggerType, digest, COMMIT);
        Long id = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class, pipelineNo);
        return id == null ? 0L : id;
    }

    private void givenDeployTask(long instanceId, long deId, int batchNo, Instant finishedAt) {
        jdbc.update("INSERT INTO node_task (instance_id, node_type, task_type, batch_no, target_id,"
                + " status, attempt, started_at, finished_at, updated_at)"
                + " VALUES (?, 'PROD_DEPLOY', 'DEPLOY', ?, ?, 'SUCCESS', 0, ?, ?, ?)",
            instanceId, batchNo, deId, Timestamp.from(finishedAt.minusSeconds(600)),
            Timestamp.from(finishedAt), Timestamp.from(finishedAt));
    }

    private void givenFreezeInForce() {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO freeze_event (scope, subsystem_id, reason, impact, initiator_id,"
                + " start_at, end_at, status, created_at)"
                + " VALUES ('SUBSYSTEM', ?, '大促封板', NULL, ?, ?, ?, 'ACTIVE', NOW(3))",
            subsystemId, USER, Timestamp.from(now.minusSeconds(60)),
            Timestamp.from(now.plusSeconds(3600)));
    }

    private void cleanDatabase() {
        jdbc.update("DELETE FROM rollback_record");
        jdbc.update("DELETE FROM pipeline_item_failure");
        jdbc.update("DELETE FROM node_task");
        jdbc.update("DELETE FROM pipeline_node");
        jdbc.update("DELETE FROM pipeline_instance");
        jdbc.update("DELETE FROM deploy_unit_lock");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM release_item");
        jdbc.update("DELETE FROM release_task_member");
        jdbc.update("DELETE FROM release_task");
        jdbc.update("DELETE FROM env_de");
        jdbc.update("DELETE FROM coding_task_binding");
        jdbc.update("DELETE FROM deploy_unit");
        jdbc.update("DELETE FROM freeze_event");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM admission_snapshot");
        jdbc.update("DELETE FROM gate_waiver");
        jdbc.update("DELETE FROM gate_check");
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM user_scope");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user");
    }

    /** Stub engine: records the production deploy triggers; builds are not used here. */
    static final class StubEngine implements CicdEngine {

        private final List<DeployCall> deployCalls = new ArrayList<>();

        @Override
        public String name() {
            return "STUB";
        }

        @Override
        public TriggerResult triggerBuild(BuildCmd cmd) {
            throw new UnsupportedOperationException("no build in the rollback flow test");
        }

        @Override
        public TriggerResult triggerDeploy(DeployCmd cmd) {
            deployCalls.add(new DeployCall(cmd));
            return new TriggerResult("rb-dep-" + deployCalls.size(),
                "https://coding.example/deploy/rb-" + deployCalls.size());
        }

        @Override
        public EngineResult query(String externalId, JobKind kind) {
            throw new UnsupportedOperationException("no query in the rollback flow test");
        }

        @Override
        public String logs(String externalId, JobKind kind, int offset, int limit) {
            throw new UnsupportedOperationException("no logs in the rollback flow test");
        }

        @Override
        public void cancel(String externalId) {
            throw new UnsupportedOperationException("no cancel in the rollback flow test");
        }

        List<String> triggeredDeNames() {
            return deployCalls.stream().map(call -> call.cmd().deName()).toList();
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
        StubEngine rollbackStubEngine() {
            return new StubEngine();
        }
    }
}