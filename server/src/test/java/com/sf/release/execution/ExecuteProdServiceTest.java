package com.sf.release.execution;

import com.sf.release.domain.engine.BuildCmd;
import com.sf.release.domain.engine.CicdEngine;
import com.sf.release.domain.engine.DeployCmd;
import com.sf.release.domain.engine.EngineResult;
import com.sf.release.domain.engine.JobKind;
import com.sf.release.domain.engine.TriggerResult;
import com.sf.release.domain.pipeline.port.DistributedLock;
import com.sf.release.infra.lock.InMemoryDistributedLock;
import com.sf.release.infra.lock.UnitLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 执行生产 over HTTP against the real pipeline (tech design 4.6/4.7): the pre-execution review, the
 * {@code READY → PROD_DEPLOYING} step, the resume of the paused instance, and the one subtle rule of
 * the endpoint — on a batch-boundary resume it must <strong>not</strong> apply {@code EXECUTE} again.
 *
 * <p>The instance is written by hand (its production tasks carry the DEs' {@code deploy_batch_no})
 * and the real {@code ProdDeployNodeHandler} is exercised with a stub engine, so the assertions can
 * name the batches that were triggered. The unit lease is taken the same way publish takes it, so the
 * orchestrator's renewal succeeds and the pipeline is allowed to advance.
 *
 * <p>Package {@code com.sf.release.execution} (not {@code ..web..}): the test needs the services, the
 * schema and the pipeline ports.
 */
@SpringBootTest(classes = {com.sf.release.ReleaseApplication.class,
    ExecuteProdServiceTest.StubEngineConfig.class})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecuteProdServiceTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String OPS = "ex-ops";
    private static final String OPS_NO_SCOPE = "ex-ops-noscope";
    private static final String DEV = "ex-dev";
    private static final String MANAGER = "ex-manager";
    private static final String PUBLISH_NO = "PO-EX-1";
    private static final String DIGEST = "sha256:exec";
    private static final String PROD_PLAN = "P-PROD-EX";
    private static final String BATCH_ONE = "ex-prod-a";
    private static final String BATCH_TWO = "ex-prod-b";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UnitLockService unitLockService;
    @Autowired DistributedLock distributedLock;
    @Autowired StubEngine engine;

    private long subsystemId;
    private long unitId;
    private long itemId;
    private long orderId;
    private long instanceId;
    private long batchOneDe;
    private long batchTwoDe;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        InMemoryDistributedLock.clearAllLeases();
        engine.clear();

        givenUser(OPS, "OPS");
        givenUser(OPS_NO_SCOPE, "OPS");
        givenUser(DEV, "DEV");
        givenUser(MANAGER, "DEPT_MANAGER");

        subsystemId = givenSubsystem("EX-SYS");
        unitId = givenUnit(subsystemId, "ex-unit");
        long taskId = givenTask(subsystemId, "EX-TASK");
        givenBinding(unitId, "PROD_DEPLOY", PROD_PLAN);
        batchOneDe = givenDe(unitId, "PROD", BATCH_ONE, 1);
        batchTwoDe = givenDe(unitId, "PROD", BATCH_TWO, 2);
        itemId = givenItem(taskId, unitId);
        orderId = givenOrder(itemId, "READY");
        instanceId = givenInstance(orderId, "PAUSED", "PROD_DEPLOY");
        givenProductionNode("PENDING");
        givenProductionTasks();
        givenCheck(itemId, orderId);
        givenSnapshot();

        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'UNIT', ?)",
            OPS, unitId);
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'UNIT', ?)",
            DEV, unitId);
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'UNIT', ?)",
            MANAGER, unitId);
        // The unit lease the publishing flow would hold while the instance is alive.
        unitLockService.acquire(unitId, instanceId);
    }

    // ------------------------------------------------------------------ first execution

    @Test
    void executing_a_ready_release_moves_it_to_prod_deploying_and_starts_the_first_batch()
            throws Exception {
        executeOk();

        assertThatItem("PROD_DEPLOYING");
        assertThatOrder("EXECUTING");
        assertThat(instanceStatus()).isEqualTo("RUNNING");
        assertThat(engine.triggeredDeNames())
            .as("the first execution triggers the first batch only")
            .containsExactlyInAnyOrder(BATCH_ONE);
        assertThat(taskStatus(batchOneDe)).isEqualTo("WAITING_EXTERNAL");
        assertThat(taskStatus(batchTwoDe)).isEqualTo("PENDING");
    }

    @Test
    void the_response_names_the_status_the_node_and_the_batch_count() throws Exception {
        mvc.perform(post("/api/v1/releases/{publishNo}/execute", PUBLISH_NO)
                .header(USER_HEADER, OPS))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.publishNo").value(PUBLISH_NO))
            .andExpect(jsonPath("$.data.status").value("PROD_DEPLOYING"))
            .andExpect(jsonPath("$.data.node").value("PROD_DEPLOY"))
            .andExpect(jsonPath("$.data.batchCount").value(2))
            .andExpect(jsonPath("$.data.review.blocked").value(false))
            .andExpect(jsonPath("$.data.review.digestMatched").value(true))
            .andExpect(jsonPath("$.data.statusReason").doesNotExist());
    }

    // ------------------------------------------------------------------ resume

    @Test
    void resuming_after_a_batch_boundary_pause_does_not_apply_execute_again() throws Exception {
        intoResumeState();

        executeOk();

        assertThatItem("PROD_DEPLOYING");
        assertThatOrder("EXECUTING");
        assertThat(instanceStatus()).isEqualTo("RUNNING");
        assertThat(engine.triggeredDeNames())
            .as("a resume triggers the next batch and nothing else")
            .containsExactlyInAnyOrder(BATCH_TWO);
        assertThat(taskStatus(batchTwoDe)).isEqualTo("WAITING_EXTERNAL");
        assertThat(taskStatus(batchOneDe))
            .as("the already deployed batch is not deployed again")
            .isEqualTo("SUCCESS");
    }

    // ------------------------------------------------------------------ review blocks

    @Test
    void a_freeze_in_force_pulls_a_ready_release_back_into_approval_and_answers_40002()
            throws Exception {
        givenFreezeInForce();

        mvc.perform(post("/api/v1/releases/{publishNo}/execute", PUBLISH_NO)
                .header(USER_HEADER, OPS))
            .andExpect(jsonPath("$.code").value(40002))
            .andExpect(jsonPath("$.data.reasons[0]").value(containsString("封板")))
            .andExpect(jsonPath("$.data.review.frozen").value(true));

        assertThatItem("PENDING_APPROVAL");
        assertThatOrder("PENDING_APPROVAL");
        assertThat(instanceStatus()).as("nothing was resumed").isEqualTo("PAUSED");
        assertThat(engine.deployCalls).isEmpty();
        Integer pending = jdbc.queryForObject(
            "SELECT COUNT(*) FROM approval WHERE order_id = ? AND role = 'DEPT_MANAGER'"
                + " AND status = 'PENDING'", Integer.class, orderId);
        assertThat(pending).as("the freeze adds the second signature").isEqualTo(1);
    }

    @Test
    void a_freeze_the_snapshot_already_recorded_blocks_without_demoting_a_ready_release()
            throws Exception {
        long freezeId = givenFreezeInForce();
        // The admission knew this freeze and its second signature was collected at admission time.
        jdbc.update("UPDATE admission_snapshot SET frozen = 1, freeze_ids = ? WHERE order_id = ?",
            "[" + freezeId + "]", orderId);
        givenDecidedApproval("DEPT_MANAGER", "APPROVED");

        mvc.perform(post("/api/v1/releases/{publishNo}/execute", PUBLISH_NO)
                .header(USER_HEADER, OPS))
            .andExpect(jsonPath("$.code").value(40002))
            .andExpect(jsonPath("$.data.review.frozen").value(true));

        assertThatItem("READY");
        assertThatOrder("READY");
        assertThat(instanceStatus()).isEqualTo("PAUSED");
        assertThat(engine.deployCalls).isEmpty();
        assertThat(pendingSecondSignatures())
            .as("a recorded freeze must not raise a signature that was already given")
            .isZero();
    }

    @Test
    void a_new_freeze_demotes_once_and_a_second_attempt_leaves_the_release_ready()
            throws Exception {
        // Review H1 regression: a freeze in force that the snapshot did not know about raises the
        // missing DEPT_MANAGER signature and pulls the item back. Once that signature is given the
        // release is READY again; a second execute attempt while the freeze persists must answer
        // 40002 and leave it READY -- not demote it again into a PENDING_APPROVAL dead-end.
        long freezeId = givenFreezeInForce();

        mvc.perform(post("/api/v1/releases/{publishNo}/execute", PUBLISH_NO)
                .header(USER_HEADER, OPS))
            .andExpect(jsonPath("$.code").value(40002));
        assertThatItem("PENDING_APPROVAL");
        assertThat(pendingSecondSignatures()).isEqualTo(1);

        mvc.perform(post("/api/v1/releases/{publishNo}/approve", PUBLISH_NO)
                .header(USER_HEADER, MANAGER)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.itemStatus").value("READY"));

        mvc.perform(post("/api/v1/releases/{publishNo}/execute", PUBLISH_NO)
                .header(USER_HEADER, OPS))
            .andExpect(jsonPath("$.code").value(40002));
        assertThatItem("READY");
        assertThat(instanceStatus()).isEqualTo("PAUSED");
        assertThat(engine.deployCalls).isEmpty();
        assertThat(pendingSecondSignatures()).isZero();

        // Lifting the freeze makes the same release executable without another approval round.
        jdbc.update("UPDATE freeze_event SET status = 'RELEASED', released_at = NOW(3) WHERE id = ?",
            freezeId);
        executeOk();

        assertThatItem("PROD_DEPLOYING");
        assertThat(engine.triggeredDeNames()).containsExactlyInAnyOrder(BATCH_ONE);
    }

    @Test
    void an_expired_unit_lease_is_40006_instead_of_a_failed_advance() throws Exception {
        distributedLock.unlock(UnitLockService.KEY_PREFIX + unitId);

        mvc.perform(post("/api/v1/releases/{publishNo}/execute", PUBLISH_NO)
                .header(USER_HEADER, OPS))
            .andExpect(jsonPath("$.code").value(40006))
            .andExpect(jsonPath("$.data.unitId").value(unitId));

        assertThatItem("READY");
        assertThat(instanceStatus()).isEqualTo("PAUSED");
        assertThat(engine.deployCalls).isEmpty();
    }

    @Test
    void a_production_rerun_executes_without_a_second_approval() throws Exception {
        intoProductionRerunAtProd();

        executeOk();

        assertThatItem("PROD_DEPLOYING");
        assertThat(instanceStatus()).isEqualTo("RUNNING");
        assertThat(engine.triggeredDeNames()).containsExactlyInAnyOrder(BATCH_ONE);
    }

    @Test
    void a_plain_testing_item_whose_instance_is_not_a_production_rerun_is_rejected()
            throws Exception {
        jdbc.update("UPDATE release_item SET status = 'TESTING' WHERE id = ?", itemId);

        mvc.perform(post("/api/v1/releases/{publishNo}/execute", PUBLISH_NO)
                .header(USER_HEADER, OPS))
            .andExpect(jsonPath("$.code").value(40001))
            .andExpect(jsonPath("$.data.status").doesNotExist())
            .andExpect(jsonPath("$.data.triggerType").value("INITIAL"));
        assertThat(engine.deployCalls).isEmpty();
    }

    @Test
    void an_expired_waiver_of_the_snapshot_blocks_the_execution() throws Exception {
        long waiverId = givenWaiver("APPROVED", Instant.now().minusSeconds(60));
        jdbc.update("UPDATE admission_snapshot SET waiver_ids = ? WHERE order_id = ?",
            "[" + waiverId + "]", orderId);

        mvc.perform(post("/api/v1/releases/{publishNo}/execute", PUBLISH_NO)
                .header(USER_HEADER, OPS))
            .andExpect(jsonPath("$.code").value(40002))
            .andExpect(jsonPath("$.data.waiverInvalid").value(true));

        assertThatItem("READY");
        assertThat(instanceStatus()).isEqualTo("PAUSED");
        assertThat(engine.deployCalls).isEmpty();
    }

    @Test
    void an_image_digest_that_differs_from_the_snapshot_is_a_hard_40007() throws Exception {
        jdbc.update("UPDATE pipeline_instance SET image_digest = 'sha256:rebuilt' WHERE id = ?",
            instanceId);

        mvc.perform(post("/api/v1/releases/{publishNo}/execute", PUBLISH_NO)
                .header(USER_HEADER, OPS))
            .andExpect(jsonPath("$.code").value(40007))
            .andExpect(jsonPath("$.data.digestMatched").value(false));

        assertThatItem("READY");
        assertThat(engine.deployCalls).isEmpty();
    }

    // ------------------------------------------------------------------ wrong preconditions

    @Test
    void a_release_that_is_not_ready_cannot_be_executed() throws Exception {
        jdbc.update("UPDATE release_item SET status = 'GATE_PENDING' WHERE id = ?", itemId);

        mvc.perform(post("/api/v1/releases/{publishNo}/execute", PUBLISH_NO)
                .header(USER_HEADER, OPS))
            .andExpect(jsonPath("$.code").value(40001))
            .andExpect(jsonPath("$.data.status").value("GATE_PENDING"));
        assertThat(engine.deployCalls).isEmpty();
    }

    @Test
    void an_instance_that_is_not_paused_at_production_cannot_be_executed() throws Exception {
        jdbc.update("UPDATE pipeline_instance SET status = 'RUNNING' WHERE id = ?", instanceId);

        mvc.perform(post("/api/v1/releases/{publishNo}/execute", PUBLISH_NO)
                .header(USER_HEADER, OPS))
            .andExpect(jsonPath("$.code").value(40001))
            .andExpect(jsonPath("$.data.instanceStatus").value("RUNNING"));
    }

    @Test
    void executing_needs_control_execute_prod_and_the_unit_scope() throws Exception {
        mvc.perform(post("/api/v1/releases/{publishNo}/execute", PUBLISH_NO)
                .header(USER_HEADER, DEV))
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.perm").value("control:execute-prod"));

        mvc.perform(post("/api/v1/releases/{publishNo}/execute", PUBLISH_NO)
                .header(USER_HEADER, OPS_NO_SCOPE))
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.unitId").value(unitId));
    }

    // ------------------------------------------------------------------ fixtures

    private void executeOk() throws Exception {
        mvc.perform(post("/api/v1/releases/{publishNo}/execute", PUBLISH_NO)
                .header(USER_HEADER, OPS))
            .andExpect(jsonPath("$.code").value(0));
    }

    /** The state left by a batch-boundary pause: batch 1 succeeded, the node is still running. */
    private void intoResumeState() {
        jdbc.update("UPDATE release_item SET status = 'PROD_DEPLOYING' WHERE id = ?", itemId);
        jdbc.update("UPDATE release_order SET status = 'EXECUTING' WHERE id = ?", orderId);
        jdbc.update("UPDATE pipeline_node SET status = 'RUNNING' WHERE instance_id = ?"
            + " AND node_type = 'PROD_DEPLOY'", instanceId);
        jdbc.update("UPDATE node_task SET status = 'SUCCESS', started_at = NOW(3),"
            + " finished_at = NOW(3) WHERE instance_id = ? AND node_type = 'PROD_DEPLOY'"
            + " AND target_id = ?", instanceId, batchOneDe);
        jdbc.update("UPDATE pipeline_instance SET status_reason = '批次边界封板' WHERE id = ?",
            instanceId);
    }

    /** A rerun that resumes at PROD_DEPLOY: the item is TESTING, the production node never started. */
    private void intoProductionRerunAtProd() {
        jdbc.update("UPDATE release_item SET status = 'TESTING' WHERE id = ?", itemId);
        jdbc.update("UPDATE release_order SET status = 'FAILED' WHERE id = ?", orderId);
        jdbc.update("UPDATE pipeline_instance SET trigger_type = 'RERUN_FROM_NODE' WHERE id = ?",
            instanceId);
    }

    /** One decided DEPT_MANAGER step on the latest snapshot (the second signature of a freeze). */
    private void givenDecidedApproval(String role, String status) {
        Long snapshotId = jdbc.queryForObject("SELECT MAX(id) FROM admission_snapshot"
            + " WHERE order_id = ?", Long.class, orderId);
        jdbc.update("INSERT INTO approval (order_id, snapshot_id, step_no, role, status,"
                + " decided_by, decided_at, comment, created_at)"
                + " VALUES (?, ?, 1, ?, ?, ?, NOW(3), '同意', NOW(3))",
            orderId, snapshotId, role, status, MANAGER);
    }

    private int pendingSecondSignatures() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE order_id = ?"
            + " AND role = 'DEPT_MANAGER' AND status = 'PENDING'", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private void assertThatItem(String status) {
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT status FROM release_item WHERE id = ?", String.class, itemId))
            .isEqualTo(status);
    }

    private void assertThatOrder(String status) {
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT status FROM release_order WHERE id = ?", String.class, orderId))
            .isEqualTo(status);
    }

    private String instanceStatus() {
        return jdbc.queryForObject("SELECT status FROM pipeline_instance WHERE id = ?",
            String.class, instanceId);
    }

    private String taskStatus(long deId) {
        return jdbc.queryForObject("SELECT status FROM node_task WHERE instance_id = ?"
            + " AND node_type = 'PROD_DEPLOY' AND target_id = ?", String.class, instanceId, deId);
    }

    private void givenSnapshot() {
        jdbc.update("INSERT INTO admission_snapshot (order_id, item_id, submitted_by,"
                + " planned_release_time, in_window, window_id, frozen, freeze_ids, max_severity,"
                + " check_conclusion, waiver_ids, test_conclusion, test_pass_by, oa_ticket_no,"
                + " image_digest, chain_json, created_at)"
                + " VALUES (?, ?, ?, ?, 0, NULL, 0, '[]', 'S3', 'PASS', '[]', 'TEST_PASS', ?,"
                + " 'OA-EX-1', ?, '[]', NOW(3))",
            orderId, itemId, DEV, Timestamp.from(Instant.now().plusSeconds(3600)), OPS, DIGEST);
    }

    private long givenFreezeInForce() {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO freeze_event (scope, subsystem_id, reason, impact, initiator_id,"
                + " start_at, end_at, status, created_at)"
                + " VALUES ('SUBSYSTEM', ?, '大促封板', NULL, ?, ?, ?, 'ACTIVE', NOW(3))",
            subsystemId, OPS, Timestamp.from(now.minusSeconds(60)),
            Timestamp.from(now.plusSeconds(3600)));
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM freeze_event", Long.class);
        return id == null ? 0L : id;
    }

    private long givenWaiver(String status, Instant expireAt) {
        jdbc.update("INSERT INTO gate_waiver (item_id, order_id, status, reason, impact,"
                + " requested_by, requested_at, approved_by, approved_at, decide_reason, expire_at,"
                + " commit_sha, image_digest, created_at, updated_at)"
                + " VALUES (?, ?, ?, '已知问题', '影响可控', ?, NOW(3), ?, NOW(3), '同意', ?, NULL,"
                + " ?, NOW(3), NOW(3))",
            itemId, orderId, status, DEV, OPS, Timestamp.from(expireAt), DIGEST);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM gate_waiver", Long.class);
        return id == null ? 0L : id;
    }

    private void givenProductionNode(String status) {
        jdbc.update("INSERT INTO pipeline_node (instance_id, node_type, seq_no, status, started_at)"
            + " VALUES (?, 'PROD_DEPLOY', 3, ?, NULL)", instanceId, status);
    }

    /** The pre-generated production tasks: two DEs in batch 1, one in batch 2. */
    private void givenProductionTasks() {
        givenTask(instanceId, batchOneDe, 1, "PENDING");
        givenTask(instanceId, batchTwoDe, 2, "PENDING");
    }

    private void givenTask(long instance, long deId, int batchNo, String status) {
        jdbc.update("INSERT INTO node_task (instance_id, node_type, task_type, batch_no, target_id,"
            + " status, attempt) VALUES (?, 'PROD_DEPLOY', 'DEPLOY', ?, ?, ?, 0)",
            instance, batchNo, deId, status);
    }

    private void cleanDatabase() {
        jdbc.update("DELETE FROM gate_waiver");
        jdbc.update("DELETE FROM gate_check");
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
        jdbc.update("DELETE FROM user_scope");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user");
    }

    private void givenUser(String userId, String... roles) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, 'ACTIVE',"
            + " NOW(3))", userId, "用户" + userId);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
        }
    }

    private long givenSubsystem(String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, OPS);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private long givenUnit(long subsystem, String name) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id, status,"
                + " created_at, updated_at) VALUES (?, ?, 'UNIT', ?, 'ACTIVE', NOW(3), NOW(3))",
            subsystem, name, OPS);
        Long id = jdbc.queryForObject("SELECT id FROM deploy_unit WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private long givenTask(long subsystem, String name) {
        jdbc.update("INSERT INTO release_task (subsystem_id, name, creator, status, created_at,"
                + " updated_at) VALUES (?, ?, ?, 'ACTIVE', NOW(3), NOW(3))", subsystem, name, OPS);
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

    private long givenItem(long taskId, long unitId) {
        jdbc.update("INSERT INTO release_item (task_id, crid, type, unit_id, build_plan_id,"
                + " source_branch, version, plan_release_date, status, gate_status, test_status,"
                + " last_test_pass_by, last_test_pass_at, row_version, created_at, updated_at)"
                + " VALUES (?, 'CR-EX-1', 'UNIT', ?, 'P-BUILD', 'release/ex', 'v1', '2026-11-25',"
                + " 'READY', 'CHECK_PASS', 'TEST_PASS', ?, NOW(3), 0, NOW(3), NOW(3))",
            taskId, unitId, OPS);
        Long id = jdbc.queryForObject("SELECT id FROM release_item WHERE crid = 'CR-EX-1'",
            Long.class);
        return id == null ? 0L : id;
    }

    private long givenOrder(long itemId, String status) {
        jdbc.update("INSERT INTO release_order (publish_no, item_id, planned_release_time,"
                + " window_result, deadline_at, status, row_version, created_at)"
                + " VALUES (?, ?, ?, 'OUT', ?, ?, 0, NOW(3))", PUBLISH_NO, itemId,
            Timestamp.from(Instant.now().plusSeconds(3600)),
            Timestamp.from(Instant.now().plusSeconds(7200)), status);
        Long id = jdbc.queryForObject("SELECT id FROM release_order WHERE publish_no = ?",
            Long.class, PUBLISH_NO);
        return id == null ? 0L : id;
    }

    private long givenInstance(long order, String status, String node) {
        jdbc.update("INSERT INTO pipeline_instance (order_id, pipeline_no, exec_type, trigger_type,"
                + " current_node, status, status_reason, image_digest, commit_sha, start_at,"
                + " created_at) VALUES (?, 'PL-EX-1', 'ONLINE', 'INITIAL', ?, ?,"
                + " '节点 PROD_DEPLOY 没有注册 NodeHandler', ?,"
                + " '0123456789abcdef0123456789abcdef01234567', NOW(3), NOW(3))",
            order, node, status, DIGEST);
        Long id = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = 'PL-EX-1'", Long.class);
        return id == null ? 0L : id;
    }

    private void givenCheck(long itemId, long orderId) {
        jdbc.update("INSERT INTO gate_check (item_id, order_id, check_kind, stage, status, severity,"
                + " issues, attempt, created_at, updated_at)"
                + " VALUES (?, ?, 'SONAR', 'IMAGE', 'PASS', 'S3', 0, 1, NOW(3), NOW(3))",
            itemId, orderId);
    }

    /** Stub engine: records each production deploy trigger; builds are not used here. */
    static final class StubEngine implements CicdEngine {

        private final List<DeployCall> deployCalls = new ArrayList<>();

        @Override
        public String name() {
            return "STUB";
        }

        @Override
        public TriggerResult triggerBuild(BuildCmd cmd) {
            throw new UnsupportedOperationException("no build in the execute test");
        }

        @Override
        public TriggerResult triggerDeploy(DeployCmd cmd) {
            deployCalls.add(new DeployCall(cmd));
            return new TriggerResult("ex-dep-" + deployCalls.size(),
                "https://coding.example/deploy/" + deployCalls.size());
        }

        @Override
        public EngineResult query(String externalId, JobKind kind) {
            throw new UnsupportedOperationException("no query in the execute test");
        }

        @Override
        public String logs(String externalId, JobKind kind, int offset, int limit) {
            throw new UnsupportedOperationException("no logs in the execute test");
        }

        @Override
        public void cancel(String externalId) {
            throw new UnsupportedOperationException("no cancel in the execute test");
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
        StubEngine executeStubEngine() {
            return new StubEngine();
        }
    }
}