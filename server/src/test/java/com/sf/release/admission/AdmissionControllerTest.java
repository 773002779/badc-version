package com.sf.release.admission;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP seam of 提交生产 / 准入 (tech design 4.8): the {@code /api/v1/releases/{publishNo}/…} contract
 * with the real interceptor and the real test schema.
 *
 * <p>Pinned here: submitting and resubmitting need {@code control:submit-prod} <em>and</em> the
 * deployment unit in the caller's data scope (40009 with the permission point or the unit id in the
 * payload), while reading the admission view needs only a logged-in caller inside the scope; a gate
 * refusal is 40002 with the failed steps and their reasons; the OA/time refusals are 40000; and a
 * repeat submit is 40001.
 *
 * <p>The test lives in {@code com.sf.release.admission}, not in a {@code ..web..} package: the
 * architecture rule forbids {@code web} depending on {@code domain}/{@code infra}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdmissionControllerTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String ADMIN = "ac-admin";
    private static final String DEV = "ac-dev";
    private static final String OUTSIDER = "ac-outsider";
    private static final String TESTER = "ac-tester";
    private static final String PUBLISH_NO = "PO-AC-1";
    private static final String OA = "OA-AC-0001";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private long subsystemId;
    private long unitId;
    private long itemId;

    @BeforeEach
    void givenGivenAnAdmittableRelease() {
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

        givenUser(ADMIN, "ADMIN");
        givenUser(DEV, "DEV");
        givenUser(OUTSIDER, "DEV");
        givenUser(TESTER, "TEST");

        subsystemId = givenSubsystem("AC-SYS");
        unitId = givenUnit(subsystemId, "ac-unit");
        long taskId = givenTask(subsystemId, "AC-TASK");
        itemId = givenItem(taskId, unitId);
        long orderId = givenOrder(itemId);
        givenInstance(orderId);
        givenCheck(itemId, orderId, "PASS", "S3");
        givenWindowConstraintOff();

        // DEV may submit for this unit; OUTSIDER has the permission but no scope.
        jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_id) VALUES (?, 'UNIT', ?)",
            DEV, unitId);
    }

    // ------------------------------------------------------------------ submit

    @Test
    void submitting_needs_control_submit_prod() throws Exception {
        mvc.perform(post("/api/v1/releases/{publishNo}/submit-prod", PUBLISH_NO)
                .header(USER_HEADER, TESTER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody(OA)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.perm").value("control:submit-prod"));
    }

    @Test
    void submitting_needs_the_unit_in_the_callers_scope() throws Exception {
        mvc.perform(post("/api/v1/releases/{publishNo}/submit-prod", PUBLISH_NO)
                .header(USER_HEADER, OUTSIDER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody(OA)))
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.unitId").value(unitId));
    }

    @Test
    void a_dev_in_scope_submits_an_in_window_release() throws Exception {
        mvc.perform(post("/api/v1/releases/{publishNo}/submit-prod", PUBLISH_NO)
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody(OA)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.publishNo").value(PUBLISH_NO))
            .andExpect(jsonPath("$.data.itemStatus").value("READY"))
            .andExpect(jsonPath("$.data.orderStatus").value("READY"))
            .andExpect(jsonPath("$.data.chain").isEmpty())
            .andExpect(jsonPath("$.data.snapshot.inWindow").value(true))
            .andExpect(jsonPath("$.data.snapshot.oaTicketNo").value(OA))
            .andExpect(jsonPath("$.data.snapshot.chain").isEmpty())
            .andExpect(jsonPath("$.data.nextAction").value(containsString("无需审批")))
            .andExpect(jsonPath("$.data.admitted").value(true))
            .andExpect(jsonPath("$.data.steps.length()").value(6))
            .andExpect(jsonPath("$.data.steps[0].key").value("GATE"))
            .andExpect(jsonPath("$.data.steps[0].label").value("检查门禁"))
            .andExpect(jsonPath("$.data.steps[0].passed").value(true))
            .andExpect(jsonPath("$.data.review").doesNotExist());
    }

    @Test
    void the_draft_check_answers_the_steps_and_writes_nothing() throws Exception {
        mvc.perform(post("/api/v1/releases/{publishNo}/admission-check", PUBLISH_NO)
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody("bad")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.admitted").value(false))
            .andExpect(jsonPath("$.data.steps.length()").value(6))
            .andExpect(jsonPath("$.data.steps[2].key").value("OA_TICKET"))
            .andExpect(jsonPath("$.data.steps[2].passed").value(false))
            .andExpect(jsonPath("$.data.steps[2].reason").value(containsString("OA 工单号")))
            .andExpect(jsonPath("$.data.nextAction").isNotEmpty())
            .andExpect(jsonPath("$.data.snapshot").doesNotExist())
            .andExpect(jsonPath("$.data.chain").isEmpty())
            .andExpect(jsonPath("$.data.itemStatus").value("GATE_PENDING"));

        mvc.perform(get("/api/v1/releases/{publishNo}/admission", PUBLISH_NO)
                .header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.data.snapshot").doesNotExist());
    }

    @Test
    void the_draft_check_is_scope_guarded() throws Exception {
        mvc.perform(post("/api/v1/releases/{publishNo}/admission-check", PUBLISH_NO)
                .header(USER_HEADER, OUTSIDER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody(OA)))
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.unitId").value(unitId));
    }

    @Test
    void a_blocked_gate_is_40002_with_the_reasons() throws Exception {
        givenCheck(itemId, orderIdOf(), "FAIL", "S1");

        mvc.perform(post("/api/v1/releases/{publishNo}/submit-prod", PUBLISH_NO)
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody(OA)))
            .andExpect(jsonPath("$.code").value(40002))
            .andExpect(jsonPath("$.data.reasons.length()").value(1))
            .andExpect(jsonPath("$.data.reasons[0]").value(containsString("检查门禁未通过")))
            .andExpect(jsonPath("$.data.failedSteps[0]").value("GATE"))
            .andExpect(jsonPath("$.data.steps[0].key").value("GATE"))
            .andExpect(jsonPath("$.data.steps[0].passed").value(false))
            .andExpect(jsonPath("$.data.steps.length()").value(6))
            .andExpect(jsonPath("$.data.nextAction").isNotEmpty());
    }

    @Test
    void a_bad_oa_ticket_is_40000() throws Exception {
        mvc.perform(post("/api/v1/releases/{publishNo}/submit-prod", PUBLISH_NO)
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody("bad")))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.failedSteps[0]").value("OA_TICKET"));
    }

    @Test
    void a_repeat_submit_is_40001() throws Exception {
        submitOk();

        mvc.perform(post("/api/v1/releases/{publishNo}/submit-prod", PUBLISH_NO)
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody(OA)))
            .andExpect(jsonPath("$.code").value(40001))
            .andExpect(jsonPath("$.data.status").value("READY"));
    }

    // ------------------------------------------------------------------ admission read

    @Test
    void reading_the_admission_view_is_open_to_a_logged_in_caller_inside_the_scope()
            throws Exception {
        submitOk();

        mvc.perform(get("/api/v1/releases/{publishNo}/admission", PUBLISH_NO)
                .header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.snapshot").isNotEmpty())
            .andExpect(jsonPath("$.data.itemStatus").value("READY"))
            .andExpect(jsonPath("$.data.chain").isEmpty());
    }

    @Test
    void reading_the_admission_view_is_scope_guarded() throws Exception {
        mvc.perform(get("/api/v1/releases/{publishNo}/admission", PUBLISH_NO)
                .header(USER_HEADER, TESTER))
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.unitId").value(unitId));
    }

    @Test
    void the_admission_view_of_a_never_submitted_release_has_no_snapshot() throws Exception {
        mvc.perform(get("/api/v1/releases/{publishNo}/admission", PUBLISH_NO)
                .header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.snapshot").doesNotExist())
            .andExpect(jsonPath("$.data.itemStatus").value("GATE_PENDING"))
            .andExpect(jsonPath("$.data.nextAction").value(containsString("尚未提交生产")));
    }

    @Test
    void an_unknown_publish_number_is_40000() throws Exception {
        mvc.perform(get("/api/v1/releases/{publishNo}/admission", "PO-NOPE")
                .header(USER_HEADER, ADMIN))
            .andExpect(jsonPath("$.code").value(40000))
            .andExpect(jsonPath("$.data.publishNo").value("PO-NOPE"));
    }

    // ------------------------------------------------------------------ resubmit

    @Test
    void resubmitting_needs_control_submit_prod() throws Exception {
        mvc.perform(post("/api/v1/releases/{publishNo}/resubmit", PUBLISH_NO)
                .header(USER_HEADER, TESTER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody(OA)))
            .andExpect(jsonPath("$.code").value(40009))
            .andExpect(jsonPath("$.data.perm").value("control:submit-prod"));
    }

    @Test
    void resubmitting_a_release_that_was_not_rejected_is_40001() throws Exception {
        mvc.perform(post("/api/v1/releases/{publishNo}/resubmit", PUBLISH_NO)
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody(OA)))
            .andExpect(jsonPath("$.code").value(40001))
            .andExpect(jsonPath("$.data.status").value("GATE_PENDING"));
    }

    @Test
    void a_missing_request_header_is_40009() throws Exception {
        mvc.perform(get("/api/v1/releases/{publishNo}/admission", PUBLISH_NO))
            .andExpect(jsonPath("$.code").value(40009));
    }

    // ------------------------------------------------------------------ fixtures

    private void submitOk() throws Exception {
        mvc.perform(post("/api/v1/releases/{publishNo}/submit-prod", PUBLISH_NO)
                .header(USER_HEADER, DEV)
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody(OA)))
            .andExpect(jsonPath("$.code").value(0));
    }

    private static String submitBody(String oa) {
        return "{\"plannedReleaseTime\":\"" + Instant.now().plusSeconds(3600)
            + "\",\"oaTicketNo\":\"" + oa + "\"}";
    }

    private long orderIdOf() {
        Long id = jdbc.queryForObject("SELECT id FROM release_order WHERE publish_no = ?",
            Long.class, PUBLISH_NO);
        return id == null ? 0L : id;
    }

    private void givenWindowConstraintOff() {
        jdbc.update("INSERT INTO release_window (scope, subsystem_id, enabled, timezone, created_at,"
            + " updated_at) VALUES ('GLOBAL', 0, 0, 'Asia/Shanghai', NOW(3), NOW(3))");
    }

    private long givenSubsystem(String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, ADMIN);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private long givenUnit(long subsystemId, String name) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id, status,"
                + " created_at, updated_at) VALUES (?, ?, 'UNIT', ?, 'ACTIVE', NOW(3), NOW(3))",
            subsystemId, name, ADMIN);
        Long id = jdbc.queryForObject("SELECT id FROM deploy_unit WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private long givenTask(long subsystemId, String name) {
        jdbc.update("INSERT INTO release_task (subsystem_id, name, creator, status, created_at,"
                + " updated_at) VALUES (?, ?, ?, 'ACTIVE', NOW(3), NOW(3))", subsystemId, name,
            ADMIN);
        Long id = jdbc.queryForObject("SELECT id FROM release_task WHERE name = ?", Long.class,
            name);
        return id == null ? 0L : id;
    }

    private long givenItem(long taskId, long unitId) {
        jdbc.update("INSERT INTO release_item (task_id, crid, type, unit_id, build_plan_id,"
                + " source_branch, version, plan_release_date, status, gate_status, test_status,"
                + " last_test_pass_by, last_test_pass_at, row_version, created_at, updated_at)"
                + " VALUES (?, 'CR-AC-1', 'UNIT', ?, 'P-BUILD', 'release/ac', 'v1', '2026-11-25',"
                + " 'GATE_PENDING', 'CHECK_PASS', 'TEST_PASS', 'ac-tester', NOW(3), 0, NOW(3),"
                + " NOW(3))", taskId, unitId);
        Long id = jdbc.queryForObject("SELECT id FROM release_item WHERE crid = 'CR-AC-1'",
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
                + " VALUES (?, 'PL-AC-1', 'ONLINE', 'INITIAL', 'VERIFY', 'RUNNING',"
                + " 'sha256:ac-digest', '0123456789abcdef0123456789abcdef01234567', NOW(3),"
                + " NOW(3))", orderId);
    }

    private void givenCheck(long itemId, long orderId, String status, String severity) {
        jdbc.update("DELETE FROM gate_check WHERE item_id = ?", itemId);
        jdbc.update("INSERT INTO gate_check (item_id, order_id, check_kind, stage, status, severity,"
                + " issues, attempt, created_at, updated_at)"
                + " VALUES (?, ?, 'SONAR', 'IMAGE', ?, ?, 0, 1, NOW(3), NOW(3))",
            itemId, orderId, status, severity);
    }

    private void givenUser(String userId, String... roles) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3))", userId, "用户" + userId);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
        }
    }
}