package com.sf.release.approval;

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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The 审批 endpoints over HTTP with the real interceptor, the real schema and the real approval chain
 * (tech design 4.4/4.8): 运维组长 then 部门经理, the role and self-approval refusals, the rejection that
 * cancels the rest, and the release statuses the decision must produce.
 *
 * <p>The chain is built by a real {@code submit-prod}: no window row means "outside the window"
 * (运维组长), and a freeze in force adds 部门经理. The users hold their roles through
 * {@code user_role}, so the permissions come from the seeded {@code role_permission} mapping — an
 * {@code ADMIN} has {@code control:approve} but not the step's role, which is exactly the case the
 * design refuses.
 *
 * <p>The package is {@code com.sf.release.approval}, not a {@code ..web..} package: the architecture
 * rule forbids {@code web} depending on {@code domain}/{@code infra}, and these tests need both.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApprovalFlowTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String DEV = "ap-dev";
    private static final String DEV_LEAD = "ap-dev-lead";
    private static final String LEAD = "ap-lead";
    private static final String MANAGER = "ap-manager";
    private static final String ADMIN = "ap-admin";
    private static final String TESTER = "ap-tester";
    private static final String PUBLISH_NO = "PO-AP-1";
    private static final String OA = "OA-AP-0001";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private long subsystemId;
    private long unitId;
    private long itemId;
    private long orderId;
    private long freezeId;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        givenUser(DEV, "DEV");
        givenUser(DEV_LEAD, "DEV", "OPS_LEAD");
        givenUser(LEAD, "OPS_LEAD");
        givenUser(MANAGER, "DEPT_MANAGER");
        givenUser(ADMIN, "ADMIN");
        givenUser(TESTER, "TEST");

        subsystemId = givenSubsystem("AP-SYS");
        unitId = givenUnit(subsystemId, "ap-unit");
        long taskId = givenTask(subsystemId, "AP-TASK");
        itemId = givenItem(taskId, unitId);
        orderId = givenOrder(itemId);
        givenInstance(orderId);
        givenCheck(itemId, orderId, "PASS", "S3");
        freezeId = givenFreezeInForce();

        // Every approver may act on this unit; the submitter needs the same scope.
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'UNIT', ?)",
            DEV, unitId);
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'UNIT', ?)",
            DEV_LEAD, unitId);
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'UNIT', ?)",
            LEAD, unitId);
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'UNIT', ?)",
            MANAGER, unitId);
    }

    // ------------------------------------------------------------------ the chain

    @Test
    void the_operating_lead_signs_first_and_the_department_manager_completes_the_chain()
            throws Exception {
        submitOk(DEV);

        approveOk(LEAD, "同意");

        assertStep(1, "OPS_LEAD", "APPROVED", LEAD);
        assertStep(2, "DEPT_MANAGER", "PENDING", null);
        assertThatStatuses("PENDING_APPROVAL", "PENDING_APPROVAL");
        mvc.perform(get("/api/v1/releases/{publishNo}/admission", PUBLISH_NO)
                .header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.data.nextAction").value(containsString("部门经理")))
            .andExpect(jsonPath("$.data.snapshot.submittedBy").value(DEV))
            .andExpect(jsonPath("$.data.review.frozen").value(true))
            .andExpect(jsonPath("$.data.review.freezeIds[0]").value(freezeId))
            .andExpect(jsonPath("$.data.review.blocked").value(true));

        approveOk(MANAGER, null);

        assertStep(1, "OPS_LEAD", "APPROVED", LEAD);
        assertStep(2, "DEPT_MANAGER", "APPROVED", MANAGER);
        assertThatStatuses("READY", "READY");
        mvc.perform(get("/api/v1/releases/{publishNo}/admission", PUBLISH_NO)
                .header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.data.nextAction").value(containsString("无需")));
    }

    @Test
    void a_single_signature_release_needs_only_the_operating_lead() throws Exception {
        // No freeze: outside the window means one 运维组长 signature (design 4.4).
        jdbc.update("DELETE FROM freeze_event WHERE id = ?", freezeId);

        submitOk(DEV);

        approveOk(LEAD, null);

        assertThatStatuses("READY", "READY");
    }

    // ------------------------------------------------------------------ who may sign

    @Test
    void a_step_may_only_be_signed_by_its_own_role() throws Exception {
        submitOk(DEV);

        mvc.perform(post("/api/v1/releases/{publishNo}/approve", PUBLISH_NO)
                .header(USER_HEADER, MANAGER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"越权\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.requiredRole").value("OPS_LEAD"))
            .andExpect(jsonPath("$.data.role").value("OPS_LEAD"));

        // ADMIN has control:approve but no 运维组长 role: a platform admin is not a signature.
        mvc.perform(post("/api/v1/releases/{publishNo}/approve", PUBLISH_NO)
                .header(USER_HEADER, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.requiredRole").value("OPS_LEAD"));

        assertStep(1, "OPS_LEAD", "PENDING", null);
        assertThatStatuses("PENDING_APPROVAL", "PENDING_APPROVAL");
    }

    @Test
    void approving_needs_the_permission_not_just_the_role() throws Exception {
        submitOk(DEV);

        mvc.perform(post("/api/v1/releases/{publishNo}/approve", PUBLISH_NO)
                .header(USER_HEADER, TESTER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.perm").value("control:approve"));
    }

    @Test
    void the_submitter_cannot_approve_their_own_release() throws Exception {
        submitOk(DEV_LEAD);

        mvc.perform(post("/api/v1/releases/{publishNo}/approve", PUBLISH_NO)
                .header(USER_HEADER, DEV_LEAD)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"自己签\"}"))
            .andExpect(jsonPath("$.code").value(40008))
            .andExpect(jsonPath("$.data.submittedBy").value(DEV_LEAD));

        assertStep(1, "OPS_LEAD", "PENDING", null);
    }

    // ------------------------------------------------------------------ rejection

    @Test
    void rejecting_terminates_the_release_and_cancels_the_remaining_steps() throws Exception {
        submitOk(DEV);

        mvc.perform(post("/api/v1/releases/{publishNo}/reject", PUBLISH_NO)
                .header(USER_HEADER, LEAD)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"窗口外风险过大\"}"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.itemStatus").value("REJECTED"))
            .andExpect(jsonPath("$.data.orderStatus").value("REJECTED"))
            .andExpect(jsonPath("$.data.chain[0].status").value("REJECTED"))
            .andExpect(jsonPath("$.data.chain[0].comment").value("窗口外风险过大"))
            .andExpect(jsonPath("$.data.chain[1].status").value("CANCELED"))
            .andExpect(jsonPath("$.data.chain[1].decidedBy").doesNotExist());

        assertStep(2, "DEPT_MANAGER", "CANCELED", null);
    }

    @Test
    void a_rejection_requires_a_reason() throws Exception {
        submitOk(DEV);

        mvc.perform(post("/api/v1/releases/{publishNo}/reject", PUBLISH_NO)
                .header(USER_HEADER, LEAD)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.comment").value("不能为空"));

        mvc.perform(post("/api/v1/releases/{publishNo}/reject", PUBLISH_NO)
                .header(USER_HEADER, LEAD)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"   \"}"))
            .andExpect(jsonPath("$.code").value(40000));

        assertStep(1, "OPS_LEAD", "PENDING", null);
    }

    // ------------------------------------------------------------------ wrong state

    @Test
    void after_a_signature_the_chain_moves_to_the_next_role_and_a_decided_step_is_40001()
            throws Exception {
        submitOk(DEV);
        approveOk(LEAD, null);

        // The second step is now the only decidable one, and it is the 部门经理's.
        mvc.perform(post("/api/v1/releases/{publishNo}/approve", PUBLISH_NO)
                .header(USER_HEADER, LEAD)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.requiredRole").value("DEPT_MANAGER"));

        approveOk(MANAGER, null);

        // A completed chain is not decidable again: the item is READY, which is 40001.
        mvc.perform(post("/api/v1/releases/{publishNo}/approve", PUBLISH_NO)
                .header(USER_HEADER, MANAGER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(jsonPath("$.code").value(40001))
            .andExpect(jsonPath("$.data.status").value("READY"));

        assertStep(1, "OPS_LEAD", "APPROVED", LEAD);
        assertStep(2, "DEPT_MANAGER", "APPROVED", MANAGER);
    }

    @Test
    void approving_a_release_that_no_longer_waits_for_approval_is_40001() throws Exception {
        // The window constraint is switched off: the release needs no signature at all.
        jdbc.update("DELETE FROM freeze_event WHERE id = ?", freezeId);
        jdbc.update("INSERT INTO release_window (scope, subsystem_id, enabled, timezone, created_at,"
            + " updated_at) VALUES ('GLOBAL', 0, 0, 'Asia/Shanghai', NOW(3), NOW(3))");

        submitOk(DEV);

        mvc.perform(post("/api/v1/releases/{publishNo}/approve", PUBLISH_NO)
                .header(USER_HEADER, LEAD)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(jsonPath("$.code").value(40001))
            .andExpect(jsonPath("$.data.status").value("READY"))
            .andExpect(jsonPath("$.message").value(containsString("全部通过")));
    }

    @Test
    void a_chain_with_no_pending_step_is_40001_with_its_reason() throws Exception {
        submitOk(DEV);
        approveOk(LEAD, null);
        approveOk(MANAGER, null);
        // Force the impossible-but-possible projection: the chain is complete while the item still
        // says PENDING_APPROVAL (for example a replication of the item write failed).
        jdbc.update("UPDATE release_item SET status = 'PENDING_APPROVAL' WHERE id = ?", itemId);

        mvc.perform(post("/api/v1/releases/{publishNo}/approve", PUBLISH_NO)
                .header(USER_HEADER, LEAD)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(jsonPath("$.code").value(40001))
            .andExpect(jsonPath("$.message").value(containsString("待签")));
    }

    // ------------------------------------------------------------------ fixtures

    private void submitOk(String user) throws Exception {
        mvc.perform(post("/api/v1/releases/{publishNo}/submit-prod", PUBLISH_NO)
                .header(USER_HEADER, user)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plannedReleaseTime\":\""
                    + Instant.now().plusSeconds(3600) + "\",\"oaTicketNo\":\"" + OA + "\"}"))
            .andExpect(jsonPath("$.code").value(0));
    }

    private void approveOk(String user, String comment) throws Exception {
        String body = comment == null ? "{}" : "{\"comment\":\"" + comment + "\"}";
        mvc.perform(post("/api/v1/releases/{publishNo}/approve", PUBLISH_NO)
                .header(USER_HEADER, user)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(jsonPath("$.code").value(0));
    }

    private void assertStep(int stepNo, String role, String status, String decidedBy) {
        String actualStatus = jdbc.queryForObject(
            "SELECT status FROM approval WHERE snapshot_id = (SELECT MAX(id) FROM admission_snapshot"
                + " WHERE order_id = ?) AND step_no = ?", String.class, orderId, stepNo);
        String actualRole = jdbc.queryForObject(
            "SELECT role FROM approval WHERE snapshot_id = (SELECT MAX(id) FROM admission_snapshot"
                + " WHERE order_id = ?) AND step_no = ?", String.class, orderId, stepNo);
        String actualDecidedBy = jdbc.queryForObject(
            "SELECT decided_by FROM approval WHERE snapshot_id = (SELECT MAX(id) FROM"
                + " admission_snapshot WHERE order_id = ?) AND step_no = ?", String.class, orderId,
            stepNo);
        org.assertj.core.api.Assertions.assertThat(actualRole).isEqualTo(role);
        org.assertj.core.api.Assertions.assertThat(actualStatus).isEqualTo(status);
        org.assertj.core.api.Assertions.assertThat(actualDecidedBy).isEqualTo(decidedBy);
    }

    private void assertThatStatuses(String itemStatus, String orderStatus) {
        String actualItem = jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?",
            String.class, itemId);
        String actualOrder = jdbc.queryForObject("SELECT status FROM release_order WHERE id = ?",
            String.class, orderId);
        org.assertj.core.api.Assertions.assertThat(actualItem).isEqualTo(itemStatus);
        org.assertj.core.api.Assertions.assertThat(actualOrder).isEqualTo(orderStatus);
    }

    private void cleanDatabase() {
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM admission_snapshot");
        jdbc.update("DELETE FROM freeze_event");
        jdbc.update("DELETE FROM gate_waiver");
        jdbc.update("DELETE FROM gate_check");
        jdbc.update("DELETE FROM pipeline_instance");
        jdbc.update("DELETE FROM release_item");
        jdbc.update("DELETE FROM release_task_member");
        jdbc.update("DELETE FROM release_task");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM deploy_unit");
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM window_exception");
        jdbc.update("DELETE FROM window_rule");
        jdbc.update("DELETE FROM release_window");
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
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, ADMIN);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private long givenUnit(long subsystem, String name) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id, status,"
                + " created_at, updated_at) VALUES (?, ?, 'UNIT', ?, 'ACTIVE', NOW(3), NOW(3))",
            subsystem, name, ADMIN);
        Long id = jdbc.queryForObject("SELECT id FROM deploy_unit WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private long givenTask(long subsystem, String name) {
        jdbc.update("INSERT INTO release_task (subsystem_id, name, creator, status, created_at,"
                + " updated_at) VALUES (?, ?, ?, 'ACTIVE', NOW(3), NOW(3))", subsystem, name,
            ADMIN);
        Long id = jdbc.queryForObject("SELECT id FROM release_task WHERE name = ?", Long.class,
            name);
        return id == null ? 0L : id;
    }

    private long givenItem(long taskId, long unitId) {
        jdbc.update("INSERT INTO release_item (task_id, crid, type, unit_id, build_plan_id,"
                + " source_branch, version, plan_release_date, status, gate_status, test_status,"
                + " last_test_pass_by, last_test_pass_at, row_version, created_at, updated_at)"
                + " VALUES (?, 'CR-AP-1', 'UNIT', ?, 'P-BUILD', 'release/ap', 'v1', '2026-11-25',"
                + " 'GATE_PENDING', 'CHECK_PASS', 'TEST_PASS', ?, NOW(3), 0, NOW(3), NOW(3))",
            taskId, unitId, TESTER);
        Long id = jdbc.queryForObject("SELECT id FROM release_item WHERE crid = 'CR-AP-1'",
            Long.class);
        return id == null ? 0L : id;
    }

    private long givenOrder(long itemId) {
        jdbc.update("INSERT INTO release_order (publish_no, item_id, status, row_version,"
            + " created_at) VALUES (?, ?, 'INIT', 0, NOW(3))", PUBLISH_NO, itemId);
        Long id = jdbc.queryForObject("SELECT id FROM release_order WHERE publish_no = ?",
            Long.class, PUBLISH_NO);
        return id == null ? 0L : id;
    }

    private void givenInstance(long orderId) {
        jdbc.update("INSERT INTO pipeline_instance (order_id, pipeline_no, exec_type, trigger_type,"
                + " current_node, status, image_digest, commit_sha, start_at, created_at)"
                + " VALUES (?, 'PL-AP-1', 'ONLINE', 'INITIAL', 'VERIFY', 'RUNNING',"
                + " 'sha256:ap-digest', '0123456789abcdef0123456789abcdef01234567', NOW(3),"
                + " NOW(3))", orderId);
    }

    private void givenCheck(long itemId, long orderId, String status, String severity) {
        jdbc.update("DELETE FROM gate_check WHERE item_id = ?", itemId);
        jdbc.update("INSERT INTO gate_check (item_id, order_id, check_kind, stage, status, severity,"
                + " issues, attempt, created_at, updated_at)"
                + " VALUES (?, ?, 'SONAR', 'IMAGE', ?, ?, 0, 1, NOW(3), NOW(3))",
            itemId, orderId, status, severity);
    }

    private long givenFreezeInForce() {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO freeze_event (scope, subsystem_id, reason, impact, initiator_id,"
                + " start_at, end_at, status, created_at)"
                + " VALUES ('SUBSYSTEM', ?, '大促封板', NULL, ?, ?, ?, 'ACTIVE', NOW(3))",
            subsystemId, MANAGER, Timestamp.from(now.minusSeconds(60)),
            Timestamp.from(now.plusSeconds(6 * 3600)));
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM freeze_event", Long.class);
        return id == null ? 0L : id;
    }
}