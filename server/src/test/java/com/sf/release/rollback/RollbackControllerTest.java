package com.sf.release.rollback;

import com.sf.release.infra.lock.InMemoryDistributedLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP seam of 生产回滚 (rollback design §4): the four endpoints with the real interceptor and the
 * real test schema.
 *
 * <p>Pinned here: the version list needs only a login plus the unit data scope, executing needs
 * {@code rollback:execute} (OPS/administrator) and the unit scope, the page and the detail are
 * data-scoped, and the request body's target/reason are validated by the service so the controller
 * stays a thin translation layer.
 *
 * <p>Package {@code com.sf.release.rollback}, not {@code ..web..}: the architecture rule forbids the
 * web package depending on domain/infra, and this test needs the real schema and the lease.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RollbackControllerTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String OPS = "rb-ctrl-ops";
    private static final String DEV = "rb-ctrl-dev";
    private static final String NO_SCOPE = "rb-ctrl-noscope";
    private static final String CURRENT = "sha256:ctrl-current";
    private static final String TARGET = "sha256:ctrl-target";
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private final AtomicInteger seq = new AtomicInteger();

    private long subsystemId;
    private long unitId;
    private long otherUnitId;
    private long taskId;
    private long deId;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        InMemoryDistributedLock.clearAllLeases();

        givenUser(OPS, "OPS");
        givenUser(DEV, "DEV");
        givenUser(NO_SCOPE, "OPS");

        subsystemId = givenSubsystem("RBCTRL-SYS");
        unitId = givenUnit(subsystemId, "rbctrl-unit");
        otherUnitId = givenUnit(subsystemId, "rbctrl-other");
        taskId = givenTask(subsystemId, "RBCTRL-TASK");
        givenBinding(unitId, "PROD_DEPLOY", "P-PROD-RBCTRL");
        deId = givenDe(unitId, "PROD", "rbctrl-prod-1", 1);

        Instant now = Instant.now();
        addVersion(unitId, CURRENT, now.minusSeconds(7200), "PO-CTRL-CUR");
        addVersion(unitId, TARGET, now.minusSeconds(10800), "PO-CTRL-TGT");

        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'UNIT', ?)",
            OPS, unitId);
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'UNIT', ?)",
            DEV, unitId);
    }

    // ------------------------------------------------------------------ version list

    @Test
    void the_version_list_returns_the_current_and_the_history_with_warnings() throws Exception {
        mvc.perform(get("/api/v1/units/{id}/rollback-versions", unitId)
                .header(USER_HEADER, DEV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.current.digest").value(CURRENT))
            .andExpect(jsonPath("$.data.current.publishNo").value("PO-CTRL-CUR"))
            .andExpect(jsonPath("$.data.current.executedBy").value("ctrl-ops"))
            .andExpect(jsonPath("$.data.current.environments[0].kind").value("PROD"))
            .andExpect(jsonPath("$.data.history.length()").value(1))
            .andExpect(jsonPath("$.data.history[0].digest").value(TARGET))
            .andExpect(jsonPath("$.data.warnings.length()").value(3));
    }

    @Test
    void the_version_list_needs_a_login_and_a_known_unit() throws Exception {
        mvc.perform(get("/api/v1/units/{id}/rollback-versions", unitId))
            .andExpect(jsonPath("$.code").value(40009));

        mvc.perform(get("/api/v1/units/{id}/rollback-versions", 999_999L)
                .header(USER_HEADER, OPS))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.unitId").isNotEmpty());
    }

    @Test
    void a_unit_without_successful_production_deployments_is_40001() throws Exception {
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'UNIT', ?)",
            OPS, otherUnitId);

        mvc.perform(get("/api/v1/units/{id}/rollback-versions", otherUnitId)
                .header(USER_HEADER, OPS))
            .andExpect(jsonPath("$.code").value(40001));
    }

    // ------------------------------------------------------------------ execute

    @Test
    void executing_a_rollback_returns_the_record_and_the_instance_number() throws Exception {
        mvc.perform(post("/api/v1/rollbacks")
                .header(USER_HEADER, OPS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(executeBody(TARGET, "线上故障止损")))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.rollbackId").isNumber())
            .andExpect(jsonPath("$.data.pipelineNo").isNotEmpty())
            .andExpect(jsonPath("$.data.unitId").value(unitId))
            .andExpect(jsonPath("$.data.targetDigest").value(TARGET))
            .andExpect(jsonPath("$.data.freezeWarning").doesNotExist());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_record", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void executing_needs_rollback_execute_and_the_unit_scope() throws Exception {
        mvc.perform(post("/api/v1/rollbacks")
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(executeBody(TARGET, "线上故障止损")))
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.perm").value("rollback:execute"));

        mvc.perform(post("/api/v1/rollbacks")
                .header(USER_HEADER, NO_SCOPE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(executeBody(TARGET, "线上故障止损")))
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.unitId").value(unitId));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_record", Integer.class))
            .isZero();
    }

    @Test
    void an_invalid_target_or_reason_is_40000() throws Exception {
        mvc.perform(post("/api/v1/rollbacks")
                .header(USER_HEADER, OPS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(executeBody(CURRENT, "线上故障止损")))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.targetDigest").isNotEmpty());

        mvc.perform(post("/api/v1/rollbacks")
                .header(USER_HEADER, OPS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(executeBody(TARGET, "  ")))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.reason").isNotEmpty());

        mvc.perform(post("/api/v1/rollbacks")
                .header(USER_HEADER, OPS)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetDigest\":\"" + TARGET + "\",\"reason\":\"止损\"}"))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.unitId").isNotEmpty());
    }

    @Test
    void executing_without_a_request_body_is_40000() throws Exception {
        mvc.perform(post("/api/v1/rollbacks")
                .header(USER_HEADER, OPS)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value(40000));
    }

    // ------------------------------------------------------------------ page and detail

    @Test
    void the_page_lists_the_unit_records_newest_first_and_is_bounded() throws Exception {
        long first = givenRecord(unitId, "RUNNING", "PO-CTRL-L1", Instant.now().minusSeconds(300));
        long second = givenRecord(unitId, "SUCCESS", "PO-CTRL-L2", Instant.now().minusSeconds(200));
        long third = givenRecord(unitId, "FAILED", "PO-CTRL-L3", Instant.now().minusSeconds(100));
        long other = givenRecord(otherUnitId, "SUCCESS", "PO-CTRL-L4", Instant.now());

        mvc.perform(get("/api/v1/rollbacks").param("unitId", String.valueOf(unitId))
                .param("page", "1").param("size", "2")
                .header(USER_HEADER, OPS))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(3))
            .andExpect(jsonPath("$.data.pageNo").value(1))
            .andExpect(jsonPath("$.data.pageSize").value(2))
            .andExpect(jsonPath("$.data.records.length()").value(2))
            .andExpect(jsonPath("$.data.records[0].id").value(third))
            .andExpect(jsonPath("$.data.records[1].id").value(second));

        mvc.perform(get("/api/v1/rollbacks").param("unitId", String.valueOf(unitId))
                .param("page", "2").param("size", "2")
                .header(USER_HEADER, OPS))
            .andExpect(jsonPath("$.data.records.length()").value(1))
            .andExpect(jsonPath("$.data.records[0].id").value(first));

        mvc.perform(get("/api/v1/rollbacks")
                .param("page", "1").param("size", "10")
                .header(USER_HEADER, OPS))
            .andExpect(jsonPath("$.data.total").value(3))
            .andExpect(jsonPath("$.data.records[*].unitId").value(
                org.hamcrest.Matchers.everyItem(
                    org.hamcrest.Matchers.is((int) unitId))));
        assertThat(other).isPositive();
    }

    @Test
    void the_page_without_a_unit_filter_follows_the_data_scope() throws Exception {
        givenRecord(unitId, "SUCCESS", "PO-CTRL-S1", Instant.now());
        givenRecord(otherUnitId, "SUCCESS", "PO-CTRL-S2", Instant.now());

        mvc.perform(get("/api/v1/rollbacks").header(USER_HEADER, OPS))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(1));

        mvc.perform(get("/api/v1/rollbacks").header(USER_HEADER, NO_SCOPE))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.records").isEmpty());
    }

    @Test
    void the_detail_names_the_result_instance_reason_and_scope() throws Exception {
        long rollbackId = givenRecord(unitId, "SUCCESS", "PO-CTRL-D1", Instant.now());
        jdbc.update("UPDATE rollback_record SET pipeline_no = 'PL-CTRL-D1', instance_id = 4242,"
            + " source_publish_no = 'PO-CTRL-CUR', source_digest = ?, target_digest = ?,"
            + " reason = '线上故障止损', failure_reason = NULL, finished_at = NOW(3)"
            + " WHERE id = ?", CURRENT, TARGET, rollbackId);

        mvc.perform(get("/api/v1/rollbacks/{id}", rollbackId)
                .header(USER_HEADER, OPS))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.id").value(rollbackId))
            .andExpect(jsonPath("$.data.pipelineNo").value("PL-CTRL-D1"))
            .andExpect(jsonPath("$.data.instanceId").value(4242))
            .andExpect(jsonPath("$.data.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.statusLabel").value("成功"))
            .andExpect(jsonPath("$.data.sourceDigest").value(CURRENT))
            .andExpect(jsonPath("$.data.targetDigest").value(TARGET))
            .andExpect(jsonPath("$.data.reason").value("线上故障止损"));

        mvc.perform(get("/api/v1/rollbacks/{id}", 999_999L)
                .header(USER_HEADER, OPS))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.id").isNotEmpty());

        long otherRecord = givenRecord(otherUnitId, "SUCCESS", "PO-CTRL-D2", Instant.now());
        mvc.perform(get("/api/v1/rollbacks/{id}", otherRecord)
                .header(USER_HEADER, OPS))
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.unitId").value(otherUnitId));
    }

    @Test
    void an_invalid_page_request_is_40000() throws Exception {
        mvc.perform(get("/api/v1/rollbacks").param("page", "0").header(USER_HEADER, OPS))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.pageNo").isNotEmpty());

        mvc.perform(get("/api/v1/rollbacks").param("size", "101").header(USER_HEADER, OPS))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.pageSize").isNotEmpty());
    }

    // ------------------------------------------------------------------ fixtures

    private String executeBody(String target, String reason) {
        return "{\"unitId\":" + unitId + ",\"targetDigest\":\"" + target + "\",\"reason\":\""
            + reason + "\"}";
    }

    private long addVersion(long unit, String digest, Instant finishedAt, String publishNo) {
        int n = seq.incrementAndGet();
        long itemId = givenItem(taskId, unit, "CR-RBC-V" + n, "release/rbc-v" + n);
        long orderId = givenOrder(itemId, publishNo, "ctrl-ops");
        long instanceId = givenInstance(orderId, "PL-RBC-V" + n, digest);
        jdbc.update("INSERT INTO pipeline_node (instance_id, node_type, seq_no, status,"
                + " started_at, finished_at) VALUES (?, 'PROD_DEPLOY', 0, 'SUCCESS', ?, ?)",
            instanceId, Timestamp.from(finishedAt.minusSeconds(600)),
            Timestamp.from(finishedAt));
        jdbc.update("INSERT INTO node_task (instance_id, node_type, task_type, batch_no, target_id,"
                + " status, attempt, started_at, finished_at, updated_at)"
                + " VALUES (?, 'PROD_DEPLOY', 'DEPLOY', 1, ?, 'SUCCESS', 0, ?, ?, ?)",
            instanceId, deId, Timestamp.from(finishedAt.minusSeconds(600)),
            Timestamp.from(finishedAt), Timestamp.from(finishedAt));
        return instanceId;
    }

    private long givenRecord(long unit, String status, String publishNo, Instant createdAt) {
        jdbc.update("INSERT INTO rollback_record (unit_id, instance_id, pipeline_no,"
                + " source_order_id, source_item_id, source_publish_no, source_digest,"
                + " source_commit, target_digest, target_publish_no, reason, requested_by,"
                + " status, failure_reason, created_at, finished_at)"
                + " VALUES (?, NULL, NULL, NULL, NULL, ?, ?, ?, ?, ?, '线上故障止损', ?, ?, NULL,"
                + " ?, NULL)",
            unit, publishNo, CURRENT, COMMIT, TARGET, "PO-CTRL-TGT", OPS, status,
            Timestamp.from(createdAt));
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM rollback_record", Long.class);
        return id == null ? 0L : id;
    }

    private void givenUser(String userId, String... roles) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3))", userId, "用户" + userId);
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
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, owner_id, created_at, updated_at)"
            + " VALUES (?, ?, ?, NOW(3), NOW(3))", subsystem, name, OPS);
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

    private long givenOrder(long itemId, String publishNo, String executedBy) {
        jdbc.update("INSERT INTO release_order (publish_no, item_id, planned_release_time,"
                + " window_result, deadline_at, status, row_version, created_at, executed_by)"
                + " VALUES (?, ?, NOW(3), 'OUT', DATE_ADD(NOW(3), INTERVAL 2 HOUR), 'DONE', 0,"
                + " NOW(3), ?)",
            publishNo, itemId, executedBy);
        Long id = jdbc.queryForObject("SELECT id FROM release_order WHERE publish_no = ?",
            Long.class, publishNo);
        return id == null ? 0L : id;
    }

    private long givenInstance(long orderId, String pipelineNo, String digest) {
        jdbc.update("INSERT INTO pipeline_instance (order_id, pipeline_no, exec_type, trigger_type,"
                + " current_node, status, image_digest, commit_sha, start_at, end_at, created_at)"
                + " VALUES (?, ?, 'ONLINE', 'INITIAL', 'PROD_DEPLOY', 'DONE', ?, ?, NOW(3),"
                + " NOW(3), NOW(3))",
            orderId, pipelineNo, digest, COMMIT);
        Long id = jdbc.queryForObject(
            "SELECT id FROM pipeline_instance WHERE pipeline_no = ?", Long.class, pipelineNo);
        return id == null ? 0L : id;
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
}