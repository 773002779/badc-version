package com.sf.release.gate;

import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.application.GateService;
import com.sf.release.application.GateWaiverService;
import com.sf.release.common.security.Permissions;
import com.sf.release.domain.gate.GateConclusion;
import com.sf.release.domain.task.TestStatus;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The waiver rules of the check gate (requirements FR-Q-03, decisions D2/D3), exercised at the
 * service level with a hand-built item: no pipeline, no engine.
 *
 * <p>What is pinned here:
 * <ul>
 *   <li>高危/严重 can never be waived — the request itself is refused;</li>
 *   <li>a pending or rejected waiver does not count, and only the approver's approval lifts the
 *       block;</li>
 *   <li>nobody approves their own request (40008), not even an administrator;</li>
 *   <li>requesting needs a task member (40009 for an outsider);</li>
 *   <li>an expiry invalidates the recorded results and the admission
 *       ({@code CHECK_INVALID}), which is requirements §4.6 "豁免到期 → 视为 CHECK_INVALID".</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class GateWaiverRulesTest {

    private static final String DEV = "w-dev";
    private static final String REVIEWER = "w-reviewer";
    private static final String OUTSIDER = "w-outsider";

    @Autowired GateWaiverService waiverService;
    @Autowired GateService gateService;
    @Autowired JdbcTemplate jdbc;

    private long unitId;
    private long itemId;

    @BeforeEach
    void givenAnItemBlockedByAMediumFinding() {
        jdbc.update("DELETE FROM gate_waiver");
        jdbc.update("DELETE FROM gate_check");
        jdbc.update("DELETE FROM release_item");
        jdbc.update("DELETE FROM release_task_member");
        jdbc.update("DELETE FROM release_task");
        jdbc.update("DELETE FROM deploy_unit");
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM user_scope");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user");

        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, 'ACTIVE',"
            + " NOW(3))", DEV, "开发");
        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, 'ACTIVE',"
            + " NOW(3))", REVIEWER, "审核人");
        jdbc.update("INSERT INTO app_user (id, name, status, created_at) VALUES (?, ?, 'ACTIVE',"
            + " NOW(3))", OUTSIDER, "路人");
        jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, 'DEV')", DEV);
        jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, 'OPS_LEAD')", REVIEWER);
        jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, 'TEST')", OUTSIDER);

        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES ('SCC-WAIVER', ?, 'ACTIVE', NOW(3), NOW(3))", REVIEWER);
        Long subsystemId = jdbc.queryForObject(
            "SELECT id FROM subsystem WHERE name = 'SCC-WAIVER'", Long.class);
        jdbc.update("INSERT INTO release_task (subsystem_id, name, creator, status, created_at,"
                + " updated_at) VALUES (?, 'WAIVER-TASK', ?, 'ACTIVE', NOW(3), NOW(3))",
            subsystemId, REVIEWER);
        Long taskId = jdbc.queryForObject(
            "SELECT id FROM release_task WHERE name = 'WAIVER-TASK'", Long.class);
        jdbc.update("INSERT INTO release_task_member (task_id, user_id, role, created_at,"
            + " updated_at) VALUES (?, ?, 'DEV', NOW(3), NOW(3))", taskId, DEV);

        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id, status,"
                + " created_at, updated_at) VALUES (?, 'waiver-api', 'UNIT', ?, 'ACTIVE',"
                + " NOW(3), NOW(3))", subsystemId, REVIEWER);
        unitId = jdbc.queryForObject(
            "SELECT id FROM deploy_unit WHERE name = 'waiver-api'", Long.class);

        jdbc.update("INSERT INTO release_item (task_id, crid, type, unit_id, build_plan_id,"
                + " source_branch, version, plan_release_date, status, gate_status, test_status,"
                + " row_version, created_at, updated_at)"
                + " VALUES (?, 'CR-WAIVER-1', 'UNIT', ?, 'P-BUILD', 'release/w', 'v1', ?,"
                + " 'TESTING', 'CHECK_FAIL', ?, 0, NOW(3), NOW(3))",
            taskId, unitId, LocalDate.of(2026, 11, 25), TestStatus.TEST_PENDING.name());
        itemId = jdbc.queryForObject(
            "SELECT id FROM release_item WHERE crid = 'CR-WAIVER-1'", Long.class);

        givenCheck("SONAR", "IMAGE", "FAIL", "S2", 3);
    }

    @AfterEach
    void clearUser() {
        TestUserContext.clear();
    }

    @Test
    void a_high_severity_finding_cannot_be_waived() {
        givenCheck("SONAR", "IMAGE", "FAIL", "S1", 1);
        asDev();

        assertThatThrownBy(() -> waiverService.request(itemId, "想豁免高危", null,
            Instant.now().plusSeconds(86_400)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("不可豁免");
        assertThat(waiverCount()).isZero();
    }

    @Test
    void a_medium_waiver_needs_a_second_person_and_then_lifts_the_block() {
        asDev();
        assertThat(gateService.decisionFor(itemId).conclusion()).isEqualTo(GateConclusion.BLOCK);

        waiverService.request(itemId, "5 个失败都是已知用例", "影响可控",
            Instant.now().plusSeconds(3 * 86_400));
        assertThat(waiverStatus()).isEqualTo("PENDING");
        assertThat(gateService.decisionFor(itemId).conclusion())
            .as("a pending waiver does not count")
            .isEqualTo(GateConclusion.BLOCK);
        long waiverId = latestWaiverId();

        assertThatThrownBy(() -> waiverService.approve(waiverId, "同意"))
            .as("a member may ask, but approving needs gate:waive")
            .isInstanceOf(BizException.class)
            .hasMessageContaining("gate:waive");

        asReviewer();
        waiverService.approve(waiverId, "同意");
        assertThat(waiverStatus()).isEqualTo("APPROVED");
        assertThat(gateService.decisionFor(itemId).conclusion())
            .isEqualTo(GateConclusion.WAIVED);
        assertThat(gateStatus()).isEqualTo("CHECK_WAIVED");
    }

    @Test
    void nobody_approves_their_own_waiver_not_even_an_administrator() {
        // The administrator is also the requester here: the request is accepted (an admin is
        // allowed to ask), but approving it must be refused.
        asAdmin();
        waiverService.request(itemId, "管理员自己申请", null, Instant.now().plusSeconds(86_400));
        long waiverId = latestWaiverId();

        assertThatThrownBy(() -> waiverService.approve(waiverId, "自己批自己"))
            .isInstanceOf(BizException.class)
            .extracting(error -> ((BizException) error).getError())
            .isEqualTo(BizError.SELF_APPROVAL_FORBIDDEN);

        asReviewer();
        waiverService.approve(waiverId, "另一个人同意");
        assertThat(waiverStatus()).isEqualTo("APPROVED");
    }

    @Test
    void a_rejected_waiver_leaves_the_gate_blocked() {
        asDev();
        waiverService.request(itemId, "申请一次", null, Instant.now().plusSeconds(86_400));
        long waiverId = latestWaiverId();

        TestUserContext.as(REVIEWER, Set.of("OPS_LEAD"), Set.of(Permissions.GATE_WAIVE));
        waiverService.reject(waiverId, "风险不可接受");

        assertThat(waiverStatus()).isEqualTo("REJECTED");
        assertThat(gateService.decisionFor(itemId).conclusion()).isEqualTo(GateConclusion.BLOCK);
        assertThat(gateStatus()).isEqualTo("CHECK_FAIL");
    }

    @Test
    void an_expired_waiver_invalidates_the_recorded_results() {
        asDev();
        waiverService.request(itemId, "有效期一天", null, Instant.now().plusSeconds(3_600));
        long waiverId = latestWaiverId();
        TestUserContext.as(REVIEWER, Set.of("OPS_LEAD"), Set.of(Permissions.GATE_WAIVE));
        waiverService.approve(waiverId, "同意");
        assertThat(gateStatus()).isEqualTo("CHECK_WAIVED");

        // Age the expiry into the past (the column stores UTC instants).
        jdbc.update("UPDATE gate_waiver SET expire_at = DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 1"
            + " MINUTE) WHERE id = ?", waiverId);

        assertThat(waiverService.expireDue(Instant.now(), 50)).isEqualTo(1);
        assertThat(waiverStatus()).isEqualTo("EXPIRED");
        assertThat(gateService.decisionFor(itemId).conclusion())
            .as("the results no longer apply: they must run again")
            .isEqualTo(GateConclusion.INVALID);
        assertThat(gateStatus()).isEqualTo("CHECK_INVALID");
        assertThat(checkStatuses()).containsOnly("INVALID");
    }

    @Test
    void only_a_member_of_the_release_task_may_request_a_waiver() {
        TestUserContext.as(OUTSIDER, Set.of("TEST"), Set.of());

        assertThatThrownBy(() -> waiverService.request(itemId, "不是成员", null,
            Instant.now().plusSeconds(86_400)))
            .isInstanceOf(BizException.class)
            .extracting(error -> ((BizException) error).getError())
            .isEqualTo(BizError.FORBIDDEN);
    }

    @Test
    void a_waiver_validity_must_be_in_the_future_and_bounded() {
        asDev();

        assertThatThrownBy(() -> waiverService.request(itemId, "过期时间在过去", null,
            Instant.now().minusSeconds(60)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("必须晚于当前时间");
        assertThatThrownBy(() -> waiverService.request(itemId, "一千年", null,
            Instant.now().plusSeconds(3650L * 86_400)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("不能超过 90 天");
    }

    // ------------------------------------------------------------------ helpers

    private void asDev() {
        TestUserContext.as(DEV, Set.of("DEV"), Set.of());
    }

    private void asReviewer() {
        TestUserContext.as(REVIEWER, Set.of("OPS_LEAD"), Set.of(Permissions.GATE_WAIVE));
    }

    private void asAdmin() {
        TestUserContext.as("w-admin", Set.of("ADMIN"), Set.of(Permissions.GATE_WAIVE));
    }

    private void givenCheck(String kind, String stage, String status, String severity, int issues) {
        jdbc.update("DELETE FROM gate_check WHERE item_id = ? AND check_kind = ?", itemId, kind);
        jdbc.update("INSERT INTO gate_check (item_id, check_kind, stage, status, severity, issues,"
                + " attempt, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 1, NOW(3), NOW(3))",
            itemId, kind, stage, status, severity, issues);
    }

    private int waiverCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM gate_waiver WHERE item_id = ?",
            Integer.class, itemId);
        return count == null ? 0 : count;
    }

    private String waiverStatus() {
        return jdbc.queryForObject("SELECT status FROM gate_waiver WHERE item_id = ?"
            + " ORDER BY id DESC LIMIT 1", String.class, itemId);
    }

    private long latestWaiverId() {
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM gate_waiver WHERE item_id = ?",
            Long.class, itemId);
        return id == null ? 0L : id;
    }

    private String gateStatus() {
        return jdbc.queryForObject("SELECT gate_status FROM release_item WHERE id = ?",
            String.class, itemId);
    }

    private java.util.List<String> checkStatuses() {
        return jdbc.queryForList("SELECT status FROM gate_check WHERE item_id = ?", String.class,
            itemId);
    }
}