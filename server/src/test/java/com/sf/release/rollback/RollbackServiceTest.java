package com.sf.release.rollback;

import com.sf.release.application.RollbackRecordView;
import com.sf.release.application.RollbackService;
import com.sf.release.application.RollbackVersionView;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.security.Permissions;
import com.sf.release.infra.lock.InMemoryDistributedLock;
import com.sf.release.infra.lock.UnitLockService;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生产回滚 service against the real test schema (P4-A, tech design §8.5 / rollback design §2-§5):
 * the version list derived from successful production deployments, and the preconditions of the
 * single-step execution.
 *
 * <p>The version fixtures are written by hand (order + instance + successful {@code PROD_DEPLOY}
 * task) because the list is exactly "which images were deployed successfully", not "which orders
 * exist"; the execution tests assert the instance shape the factory produced (one node, reversed
 * batches, the target image).
 *
 * <p>Package {@code com.sf.release.rollback}, not {@code ..web..}: the architecture rule forbids the
 * web package depending on domain/infra, and this test needs the lock service and the schema.
 */
@SpringBootTest
@ActiveProfiles("test")
class RollbackServiceTest {

    private static final String USER = "rb-ops";
    private static final String CURRENT = "sha256:current";
    private static final String TARGET = "sha256:target";
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final String REASON = "线上故障止损";

    @Autowired RollbackService rollbacks;
    @Autowired UnitLockService unitLockService;
    @Autowired JdbcTemplate jdbc;

    private final AtomicInteger seq = new AtomicInteger();

    private long subsystemId;
    private long unitId;
    private long taskId;
    private long prodOne;
    private long drTwo;
    private long blueThree;
    private Version current;
    private Version target;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        InMemoryDistributedLock.clearAllLeases();

        subsystemId = givenSubsystem("RB-SYS");
        unitId = givenUnit(subsystemId, "rb-unit");
        taskId = givenTask(subsystemId, "RB-TASK");
        givenBinding(unitId, "PROD_DEPLOY", "P-PROD-RB");
        prodOne = givenDe(unitId, "PROD", "rb-prod-1", 1);
        drTwo = givenDe(unitId, "DR", "rb-dr-2", 2);
        blueThree = givenDe(unitId, "BLUE", "rb-blue-3", 3);

        Instant now = Instant.now();
        current = addVersion(CURRENT, now.minusSeconds(7200), "ops-old", prodOne, 1);
        target = addVersion(TARGET, now.minusSeconds(10800), "ops-old", prodOne, 1);

        TestUserContext.as(USER, Set.of("OPS"), Set.of(Permissions.ROLLBACK_EXECUTE));
    }

    @AfterEach
    void clearUser() {
        TestUserContext.clear();
    }

    // ------------------------------------------------------------------ version list

    @Test
    void an_empty_unit_has_no_rollback_versions_and_is_40001() {
        long emptyUnit = givenUnit(subsystemId, "rb-empty");

        assertBizError(() -> rollbacks.versions(emptyUnit), BizError.STATE_NOT_ALLOWED, "unitId");
        assertBizError(() -> rollbacks.execute(emptyUnit, TARGET, REASON),
            BizError.STATE_NOT_ALLOWED, "unitId");
        assertThat(recordCount()).isZero();
    }

    @Test
    void versions_returns_the_current_version_and_the_older_history() {
        RollbackService.VersionList list = rollbacks.versions(unitId);

        assertThat(list.current().digest()).isEqualTo(CURRENT);
        assertThat(list.current().publishNo()).isEqualTo(current.publishNo());
        assertThat(list.current().executedBy()).isEqualTo("ops-old");
        assertThat(list.current().batchCount()).isEqualTo(1);
        assertThat(list.current().environments())
            .extracting(RollbackVersionView.Environment::kind)
            .containsExactly("PROD");
        assertThat(list.history()).extracting(RollbackVersionView::digest)
            .containsExactly(TARGET);
        assertThat(list.history().get(0).publishNo()).isEqualTo(target.publishNo());
        assertThat(list.warnings())
            .as("the drawer's must-read notes are part of the answer")
            .isNotEmpty();
    }

    @Test
    void versions_returns_eleven_rows_at_most_ten_of_them_history() {
        Instant now = Instant.now();
        for (int i = 0; i < 10; i++) {
            addVersion("sha256:extra-" + i, now.minusSeconds(14400 + i * 3600L), "ops-old",
                prodOne, 1);
        }

        RollbackService.VersionList list = rollbacks.versions(unitId);

        assertThat(list.history()).hasSize(10);
        assertThat(list.current().digest()).isEqualTo(CURRENT);
        assertThat(list.history()).extracting(RollbackVersionView::digest)
            .as("the newest 10 historical versions are kept; the oldest is dropped")
            .contains(TARGET, "sha256:extra-0", "sha256:extra-8")
            .doesNotContain("sha256:extra-9");
    }

    @Test
    void versions_merges_repeated_deployments_of_one_digest_into_the_latest_one() {
        Instant now = Instant.now();
        Version redeploy = addVersion(CURRENT, now.minusSeconds(1800), "ops-new", drTwo, 2);

        RollbackService.VersionList list = rollbacks.versions(unitId);

        assertThat(list.current().digest()).isEqualTo(CURRENT);
        assertThat(list.current().publishNo())
            .as("the latest deployment of the digest names its publish number")
            .isEqualTo(redeploy.publishNo());
        assertThat(list.current().executedBy()).isEqualTo("ops-new");
        assertThat(list.current().batchCount())
            .as("distinct deploy batches the image went out in across its history")
            .isEqualTo(2);
        assertThat(list.history()).hasSize(1);
        assertThat(list.history()).extracting(RollbackVersionView::digest).containsExactly(TARGET);
    }

    @Test
    void batch_count_counts_deploy_batches_not_deploy_targets() {
        Instant now = Instant.now();
        Version multi = addVersion("sha256:multi-de", now.minusSeconds(600), "ops-new", prodOne, 1);
        // The same batch also deployed the DR DE; two targets, still one batch.
        givenDeployTask(multi.instanceId(), drTwo, 1, "SUCCESS", now.minusSeconds(600));

        RollbackService.VersionList list = rollbacks.versions(unitId);

        assertThat(list.current().digest()).isEqualTo("sha256:multi-de");
        assertThat(list.current().batchCount())
            .as("the drawer's 批次数 counts deploy batches, not the DEs inside a batch")
            .isEqualTo(1);
    }

    @Test
    void after_a_rollback_the_target_becomes_the_current_version() {
        // Independent review of Task 1: the authoritative version query excluded ROLLBACK
        // instances, so after a successful rollback the page kept showing the pre-rollback image as
        // "current" and a repeated rollback to the same target was not refused.
        Instant now = Instant.now().plusSeconds(60);
        long itemId = givenItem(taskId, unitId, "CR-RB-RB", "release/rb-rb", "DONE");
        long orderId = givenOrder(itemId, "PO-RB-ROLLBACK", "DONE", USER);
        long instanceId = givenInstance(orderId, "PL-RB-ROLLBACK", "ROLLBACK", TARGET, COMMIT,
            "DONE");
        givenDeployTask(instanceId, prodOne, 1, "SUCCESS", now);
        givenRollbackRecord(instanceId, unitId, current.orderId(), itemId, current.publishNo(),
            CURRENT, TARGET, target.publishNo());

        RollbackService.VersionList list = rollbacks.versions(unitId);

        assertThat(list.current().digest())
            .as("the rolled-back-to image is what production runs now")
            .isEqualTo(TARGET);
        assertThat(list.current().publishNo()).isEqualTo(target.publishNo());
        assertThat(list.current().executedBy()).isEqualTo("ops-rb");
        assertThat(list.history()).extracting(RollbackVersionView::digest)
            .as("the previous online image stays available for a roll-forward")
            .containsExactly(CURRENT);
        assertBizError(() -> rollbacks.execute(unitId, TARGET, REASON),
            BizError.PARAM_INVALID, "targetDigest");
    }

    @Test
    void a_release_that_failed_mid_batch_is_not_a_rollback_target() {
        Instant now = Instant.now();
        long itemId = givenItem(taskId, unitId, "CR-RB-PARTIAL", "release/rb-partial", "FAILED");
        long orderId = givenOrder(itemId, "PO-RB-PARTIAL", "FAILED", "ops-partial");
        long instanceId = givenInstance(orderId, "PL-RB-PARTIAL", "INITIAL", "sha256:partial",
            COMMIT, "FAILED");
        givenDeployTask(instanceId, prodOne, 1, "SUCCESS", now);
        givenDeployTask(instanceId, drTwo, 2, "FAILED", now);
        jdbc.update("UPDATE pipeline_node SET status = 'FAILED' WHERE instance_id = ?", instanceId);

        RollbackService.VersionList list = rollbacks.versions(unitId);

        assertThat(list.current().digest())
            .as("the partially deployed image is what production runs: the page must say so")
            .isEqualTo("sha256:partial");
        assertThat(list.history()).extracting(RollbackVersionView::digest)
            .as("only fully deployed releases are rollback targets")
            .containsExactly(CURRENT, TARGET);
        assertBizError(() -> rollbacks.execute(unitId, "sha256:partial", REASON),
            BizError.PARAM_INVALID, "targetDigest");

        rollbacks.execute(unitId, CURRENT, REASON);
        assertThat(recordCount())
            .as("rolling the partial deployment back to the last full release is allowed")
            .isEqualTo(1);
    }

    @Test
    void versions_requires_an_existing_unit_in_scope() {
        assertBizError(() -> rollbacks.versions(999_999L), BizError.PARAM_INVALID, "unitId");

        TestUserContext.scopedWithPerms(USER, Set.of(Permissions.ROLLBACK_EXECUTE));
        assertBizError(() -> rollbacks.versions(unitId), BizError.FORBIDDEN, "unitId");
    }

    // ------------------------------------------------------------------ execution preconditions

    @Test
    void execute_requires_rollback_execute() {
        TestUserContext.as(USER, Set.of("DEV"), Set.of());

        assertBizError(() -> rollbacks.execute(unitId, TARGET, REASON),
            BizError.FORBIDDEN, "perm");
        assertThat(recordCount()).isZero();
    }

    @Test
    void execute_requires_an_existing_unit_and_scope() {
        assertBizError(() -> rollbacks.execute(999_999L, TARGET, REASON),
            BizError.PARAM_INVALID, "unitId");

        TestUserContext.scopedWithPerms(USER, Set.of(Permissions.ROLLBACK_EXECUTE));
        assertBizError(() -> rollbacks.execute(unitId, TARGET, REASON),
            BizError.FORBIDDEN, "unitId");
        assertThat(recordCount()).isZero();
    }

    @Test
    void execute_requires_a_reason_of_at_most_two_hundred_characters() {
        assertBizError(() -> rollbacks.execute(unitId, TARGET, "  "),
            BizError.PARAM_INVALID, "reason");
        assertBizError(() -> rollbacks.execute(unitId, TARGET, "x".repeat(201)),
            BizError.PARAM_INVALID, "reason");
        assertThat(recordCount()).isZero();
    }

    @Test
    void execute_requires_a_target_inside_the_history_and_different_from_the_current_version() {
        assertBizError(() -> rollbacks.execute(unitId, "sha256:unknown", REASON),
            BizError.PARAM_INVALID, "targetDigest");
        assertBizError(() -> rollbacks.execute(unitId, null, REASON),
            BizError.PARAM_INVALID, "targetDigest");
        assertBizError(() -> rollbacks.execute(unitId, CURRENT, REASON),
            BizError.PARAM_INVALID, "targetDigest");
        assertThat(recordCount()).isZero();
    }

    @Test
    void execute_requires_the_prod_deploy_binding_and_a_production_family_de() {
        jdbc.update("DELETE FROM coding_task_binding WHERE unit_id = ?", unitId);
        assertBizErrorWithList(() -> rollbacks.execute(unitId, TARGET, REASON),
            BizError.UNIT_NOT_CONFIGURED, "missing", "PROD_DEPLOY");
        assertThat(recordCount()).isZero();

        givenBinding(unitId, "PROD_DEPLOY", "P-PROD-RB");
        jdbc.update("DELETE FROM env_de WHERE unit_id = ?", unitId);
        assertBizErrorWithList(() -> rollbacks.execute(unitId, TARGET, REASON),
            BizError.UNIT_NOT_CONFIGURED, "missing", "PROD_DE");
        assertThat(recordCount()).isZero();
    }

    @Test
    void execute_rejects_a_locked_unit() {
        assertThat(unitLockService.acquire(unitId, UnitLockService.UNBOUND_INSTANCE_ID)).isTrue();

        assertBizError(() -> rollbacks.execute(unitId, TARGET, REASON),
            BizError.UNIT_LOCKED, "unitId");
        assertThat(recordCount()).isZero();
    }

    // ------------------------------------------------------------------ execution result

    @Test
    void execute_creates_a_single_node_reversed_batch_rollback_instance() {
        RollbackService.ExecuteResult result = rollbacks.execute(unitId, TARGET, REASON);

        assertThat(result.rollbackId()).isPositive();
        assertThat(result.pipelineNo()).isNotBlank();
        assertThat(result.unitId()).isEqualTo(unitId);
        assertThat(result.targetDigest()).isEqualTo(TARGET);
        assertThat(result.freezeWarning()).isNull();

        Map<String, Object> record = jdbc.queryForMap(
            "SELECT * FROM rollback_record WHERE id = ?", result.rollbackId());
        assertThat(record.get("status")).isEqualTo("RUNNING");
        assertThat(record.get("source_order_id")).isEqualTo(current.orderId());
        assertThat(record.get("source_item_id")).isEqualTo(current.itemId());
        assertThat(record.get("source_publish_no")).isEqualTo(current.publishNo());
        assertThat(record.get("source_digest")).isEqualTo(CURRENT);
        assertThat(record.get("source_commit")).isEqualTo(COMMIT);
        assertThat(record.get("target_digest")).isEqualTo(TARGET);
        assertThat(record.get("target_publish_no")).isEqualTo(target.publishNo());
        assertThat(record.get("reason")).isEqualTo(REASON);
        assertThat(record.get("requested_by")).isEqualTo(USER);
        assertThat(record.get("pipeline_no")).isEqualTo(result.pipelineNo());
        assertThat(record.get("instance_id")).isNotNull();
        assertThat(record.get("finished_at")).isNull();

        long instanceId = ((Number) record.get("instance_id")).longValue();
        Map<String, Object> instance = jdbc.queryForMap(
            "SELECT * FROM pipeline_instance WHERE id = ?", instanceId);
        assertThat(instance.get("order_id")).isEqualTo(current.orderId());
        assertThat(instance.get("trigger_type")).isEqualTo("ROLLBACK");
        assertThat(instance.get("exec_type")).isEqualTo("ONLINE");
        assertThat(instance.get("current_node")).isEqualTo("PROD_DEPLOY");
        assertThat(instance.get("image_digest")).isEqualTo(TARGET);
        assertThat(instance.get("status")).isEqualTo("PENDING");

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM pipeline_node WHERE instance_id = ?", Integer.class, instanceId))
            .isEqualTo(1);
        Map<String, Object> node = jdbc.queryForMap(
            "SELECT * FROM pipeline_node WHERE instance_id = ?", instanceId);
        assertThat(node.get("node_type")).isEqualTo("PROD_DEPLOY");
        assertThat(node.get("seq_no")).isEqualTo(0);
        assertThat(node.get("status")).isEqualTo("PENDING");

        List<Map<String, Object>> batches = jdbc.queryForList(
            "SELECT nt.batch_no, ed.deploy_batch_no, ed.de_name, nt.task_type, nt.status"
                + " FROM node_task nt JOIN env_de ed ON ed.id = nt.target_id"
                + " WHERE nt.instance_id = ? ORDER BY nt.batch_no", instanceId);
        assertThat(batches).hasSize(3);
        assertThat(batches).allSatisfy(row -> {
            assertThat(row.get("task_type")).isEqualTo("DEPLOY");
            assertThat(row.get("status")).isEqualTo("PENDING");
        });
        assertThat(batches.get(0).get("batch_no")).isEqualTo(1);
        assertThat(batches.get(0).get("deploy_batch_no"))
            .as("the highest original batch (BLUE, 3) is the first to be rolled back")
            .isEqualTo(3);
        assertThat(batches.get(1).get("batch_no")).isEqualTo(2);
        assertThat(batches.get(1).get("deploy_batch_no")).isEqualTo(2);
        assertThat(batches.get(2).get("batch_no")).isEqualTo(3);
        assertThat(batches.get(2).get("deploy_batch_no")).isEqualTo(1);

        assertThat(jdbc.queryForObject(
            "SELECT instance_id FROM deploy_unit_lock WHERE unit_id = ?", Long.class, unitId))
            .isEqualTo(instanceId);
    }

    @Test
    void execute_returns_a_freeze_warning_but_still_creates_the_rollback() {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO freeze_event (scope, subsystem_id, reason, impact, initiator_id,"
                + " start_at, end_at, status, created_at)"
                + " VALUES ('SUBSYSTEM', ?, '大促封板', NULL, ?, ?, ?, 'ACTIVE', NOW(3))",
            subsystemId, USER, Timestamp.from(now.minusSeconds(60)),
            Timestamp.from(now.plusSeconds(3600)));

        RollbackService.ExecuteResult result = rollbacks.execute(unitId, TARGET, REASON);

        assertThat(result.freezeWarning())
            .as("a freeze is reported, not enforced: a rollback is a stop-loss action")
            .contains("封板");
        assertThat(recordCount()).isEqualTo(1);
        assertThat(instanceStatus(result.pipelineNo())).isEqualTo("PENDING");
    }

    @Test
    void detail_and_page_expose_the_record_and_the_scope() {
        RollbackService.ExecuteResult result = rollbacks.execute(unitId, TARGET, REASON);

        RollbackRecordView detail = rollbacks.detail(result.rollbackId());
        assertThat(detail.unitId()).isEqualTo(unitId);
        assertThat(detail.status()).isEqualTo("RUNNING");
        assertThat(detail.statusLabel()).isEqualTo("执行中");
        assertThat(detail.pipelineNo()).isEqualTo(result.pipelineNo());
        assertThat(detail.reason()).isEqualTo(REASON);
        assertThat(detail.targetDigest()).isEqualTo(TARGET);

        RollbackService.PageResult page = rollbacks.page(unitId, 1, 10);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.records()).extracting(RollbackRecordView::id)
            .containsExactly(result.rollbackId());

        TestUserContext.scopedWithPerms(USER, Set.of(Permissions.ROLLBACK_EXECUTE));
        assertBizError(() -> rollbacks.detail(result.rollbackId()), BizError.FORBIDDEN, "unitId");
        assertThat(rollbacks.page(null, 1, 10).records()).isEmpty();
    }

    // ------------------------------------------------------------------ fixtures

    private record Version(long orderId, long itemId, long instanceId, String publishNo) {
    }

    private Version addVersion(String digest, Instant finishedAt, String executedBy, long deId,
                               int batchNo) {
        int n = seq.incrementAndGet();
        String publishNo = "PO-RB-V" + n;
        long itemId = givenItem(taskId, unitId, "CR-RB-V" + n, "release/rb-v" + n, "DONE");
        long orderId = givenOrder(itemId, publishNo, "DONE", executedBy);
        long instanceId = givenInstance(orderId, "PL-RB-V" + n, "INITIAL", digest, COMMIT, "DONE");
        givenDeployTask(instanceId, deId, batchNo, "SUCCESS", finishedAt);
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

    private long givenItem(long task, long unit, String crid, String branch, String status) {
        jdbc.update("INSERT INTO release_item (task_id, crid, type, unit_id, build_plan_id,"
                + " source_branch, version, plan_release_date, status, gate_status, test_status,"
                + " row_version, created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, 'P-BUILD', ?, 'v1', '2026-11-25', ?, 'CHECK_PASS',"
                + " 'TEST_PASS', 0, NOW(3), NOW(3))",
            task, crid, unit, branch, status);
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

    private long givenInstance(long orderId, String pipelineNo, String triggerType, String digest,
                               String commitSha, String status) {
        jdbc.update("INSERT INTO pipeline_instance (order_id, pipeline_no, exec_type, trigger_type,"
                + " current_node, status, image_digest, commit_sha, start_at, end_at, created_at)"
                + " VALUES (?, ?, 'ONLINE', ?, 'PROD_DEPLOY', ?, ?, ?, NOW(3), NOW(3), NOW(3))",
            orderId, pipelineNo, triggerType, status, digest, commitSha);
        Long id = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class, pipelineNo);
        return id == null ? 0L : id;
    }

    private void givenDeployTask(long instanceId, long deId, int batchNo, String status,
                                 Instant finishedAt) {
        Integer nodes = jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_node"
            + " WHERE instance_id = ? AND node_type = 'PROD_DEPLOY'", Integer.class, instanceId);
        if (nodes == null || nodes == 0) {
            // The history query requires the node itself to be SUCCESS (every batch deployed);
            // tests for a partial release flip it to FAILED afterwards.
            jdbc.update("INSERT INTO pipeline_node (instance_id, node_type, seq_no, status,"
                    + " started_at, finished_at) VALUES (?, 'PROD_DEPLOY', 0, 'SUCCESS', NOW(3), ?)",
                instanceId, Timestamp.from(finishedAt));
        }
        jdbc.update("INSERT INTO node_task (instance_id, node_type, task_type, batch_no, target_id,"
                + " status, attempt, started_at, finished_at, updated_at)"
                + " VALUES (?, 'PROD_DEPLOY', 'DEPLOY', ?, ?, ?, 0, ?, ?, ?)",
            instanceId, batchNo, deId, status, Timestamp.from(finishedAt.minusSeconds(600)),
            Timestamp.from(finishedAt), Timestamp.from(finishedAt));
    }

    private void givenRollbackRecord(long instanceId, long unitId, long sourceOrderId,
                                     long sourceItemId, String sourcePublishNo,
                                     String sourceDigest, String targetDigest,
                                     String targetPublishNo) {
        jdbc.update("INSERT INTO rollback_record (unit_id, instance_id, pipeline_no,"
                + " source_order_id, source_item_id, source_publish_no, source_digest,"
                + " target_digest, target_publish_no, reason, requested_by, status, created_at,"
                + " finished_at)"
                + " SELECT ?, ?, pipeline_no, ?, ?, ?, ?, ?, ?, ?, 'ops-rb', 'SUCCESS', NOW(3),"
                + " NOW(3) FROM pipeline_instance WHERE id = ?",
            unitId, instanceId, sourceOrderId, sourceItemId, sourcePublishNo, sourceDigest,
            targetDigest, targetPublishNo, REASON, instanceId);
    }

    private long recordCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM rollback_record", Long.class);
        return count == null ? 0L : count;
    }

    private String instanceStatus(String pipelineNo) {
        return jdbc.queryForObject("SELECT status FROM pipeline_instance WHERE pipeline_no = ?",
            String.class, pipelineNo);
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

    private static void assertBizError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call, BizError error,
            String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getError()).isEqualTo(error);
            assertThat(ex.getPayload()).isInstanceOf(Map.class);
            assertThat((Map<String, Object>) ex.getPayload()).containsKey(field);
        });
    }

    private static void assertBizErrorWithList(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call, BizError error,
            String field, String expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getError()).isEqualTo(error);
            Map<String, Object> payload = (Map<String, Object>) ex.getPayload();
            assertThat(payload).containsKey(field);
            assertThat((List<Object>) payload.get(field)).contains(expected);
        });
    }
}