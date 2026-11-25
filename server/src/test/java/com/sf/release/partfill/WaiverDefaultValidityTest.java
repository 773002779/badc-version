package com.sf.release.partfill;

import com.sf.release.application.GateWaiverService;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.task.TestStatus;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6-F design §3/§5.2: the default validity of the check waiver.
 *
 * <p>Pinned here: an empty {@code expireAt} becomes {@code now + WAIVER.defaultDays} (the seeded
 * seven days, and a configured ten); an explicit expiry keeps its pre-P6-F behaviour (stored as
 * given); an explicit expiry in the past or beyond the unchanged 90-day maximum is still 40000;
 * and a blank reason stays 40000 no matter which expiry form is used.
 *
 * <p>The item/gate fixture mirrors {@code GateWaiverRulesTest}: a task member requests a waiver for
 * one medium finding, without a pipeline. Package {@code com.sf.release.partfill}, not
 * {@code ..web..}.
 */
@SpringBootTest
@ActiveProfiles("test")
class WaiverDefaultValidityTest {

    private static final String DEV = "p6f-wv-dev";
    private static final String REVIEWER = "p6f-wv-reviewer";
    private static final String SUBSYSTEM = "SCC-P6F-WV";
    private static final String TASK = "P6F-WV-TASK";
    private static final String UNIT = "p6f-wv-unit";
    private static final String CRID = "CR-P6F-WV-1";

    @Autowired GateWaiverService waiverService;
    @Autowired JdbcTemplate jdbc;

    private long itemId;

    @BeforeEach
    void givenAnItemBlockedByAMediumFinding() {
        jdbc.update("DELETE FROM gate_waiver");
        jdbc.update("DELETE FROM gate_check");
        jdbc.update("DELETE FROM release_item WHERE crid = ?", CRID);
        jdbc.update("DELETE FROM release_task_member WHERE task_id IN"
            + " (SELECT id FROM release_task WHERE name = ?)", TASK);
        jdbc.update("DELETE FROM release_task WHERE name = ?", TASK);
        jdbc.update("DELETE FROM deploy_unit WHERE name = ?", UNIT);
        jdbc.update("DELETE FROM subsystem WHERE name = ?", SUBSYSTEM);
        jdbc.update("DELETE FROM user_scope WHERE user_id LIKE 'p6f-wv-%'");
        jdbc.update("DELETE FROM user_role WHERE user_id LIKE 'p6f-wv-%'");
        jdbc.update("DELETE FROM app_user WHERE id LIKE 'p6f-wv-%'");
        setWaiverDays(7);

        givenUser(DEV, "开发", "DEV");
        givenUser(REVIEWER, "审核人", "OPS_LEAD");

        long subsystemId = givenSubsystem();
        long taskId = givenTask(subsystemId);
        long unitId = givenUnit(subsystemId);
        jdbc.update("INSERT INTO release_task_member (task_id, user_id, role, created_at,"
            + " updated_at) VALUES (?, ?, 'DEV', NOW(3), NOW(3))", taskId, DEV);
        itemId = givenItem(taskId, unitId);
        givenMediumCheck();
    }

    @AfterEach
    void clearUserAndRestoreTheDefault() {
        TestUserContext.clear();
        setWaiverDays(7);
    }

    // ------------------------------------------------------------------ default validity

    @Test
    void an_empty_expiry_uses_the_seeded_seven_day_default() {
        asDev();
        Instant before = Instant.now();

        waiverService.request(itemId, "5 个失败都是已知用例", "影响可控", null);

        assertExpiryNear(before.plus(Duration.ofDays(7)));
        assertThat(waiverStatus()).isEqualTo("PENDING");
    }

    @Test
    void a_configured_default_of_ten_days_is_used_when_the_expiry_is_empty() {
        setWaiverDays(10);
        asDev();
        Instant before = Instant.now();

        waiverService.request(itemId, "按配置默认有效期", null, null);

        assertExpiryNear(before.plus(Duration.ofDays(10)));
    }

    // ------------------------------------------------------------------ explicit expiry regression

    @Test
    void an_explicit_expiry_is_still_stored_as_sent() {
        asDev();
        Instant explicit = Instant.now().plus(Duration.ofDays(30));

        waiverService.request(itemId, "显式 30 天", null, explicit);

        assertExpiryNear(explicit);
        assertThat(expireAt().isBefore(Instant.now().plus(Duration.ofDays(10))))
            .as("the explicit value must not be replaced by the seven-day default")
            .isFalse();
    }

    @Test
    void an_explicit_expiry_beyond_ninety_days_is_still_rejected() {
        asDev();

        assertThatThrownBy(() -> waiverService.request(itemId, "一千年",
            null, Instant.now().plus(Duration.ofDays(91))))
            .isInstanceOf(BizException.class)
            .extracting(error -> ((BizException) error).getError())
            .isEqualTo(BizError.PARAM_INVALID);
        assertThat(waiverCount()).isZero();
    }

    @Test
    void an_explicit_expiry_in_the_past_is_still_rejected() {
        asDev();

        assertThatThrownBy(() -> waiverService.request(itemId, "过期时间在过去",
            null, Instant.now().minusSeconds(60)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("必须晚于当前时间");
        assertThat(waiverCount()).isZero();
    }

    @Test
    void a_blank_reason_is_rejected_with_the_default_expiry_too() {
        asDev();

        assertThatThrownBy(() -> waiverService.request(itemId, "   ", null, null))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("豁免原因不能为空");
        assertThat(waiverCount()).isZero();
    }

    // ------------------------------------------------------------------ fixtures

    private void asDev() {
        TestUserContext.as(DEV, java.util.Set.of("DEV"), java.util.Set.of());
    }

    private void setWaiverDays(int days) {
        jdbc.update("INSERT INTO app_setting (setting_key, value_json, updated_at)"
            + " VALUES ('WAIVER', ?, NOW(3))"
            + " ON DUPLICATE KEY UPDATE value_json = VALUES(value_json),"
            + " updated_at = NOW(3)", "{\"defaultDays\":" + days + "}");
    }

    private void givenUser(String userId, String name, String role) {
        jdbc.update("INSERT INTO app_user (id, name, status, created_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3))", userId, name);
        jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
    }

    private long givenSubsystem() {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", SUBSYSTEM, REVIEWER);
        return idOf("SELECT id FROM subsystem WHERE name = ?", SUBSYSTEM);
    }

    private long givenTask(long subsystemId) {
        jdbc.update("INSERT INTO release_task (subsystem_id, name, creator, status, created_at,"
                + " updated_at) VALUES (?, ?, ?, 'ACTIVE', NOW(3), NOW(3))",
            subsystemId, TASK, REVIEWER);
        return idOf("SELECT id FROM release_task WHERE name = ?", TASK);
    }

    private long givenUnit(long subsystemId) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id, status,"
                + " created_at, updated_at) VALUES (?, ?, 'UNIT', ?, 'ACTIVE', NOW(3), NOW(3))",
            subsystemId, UNIT, REVIEWER);
        return idOf("SELECT id FROM deploy_unit WHERE name = ?", UNIT);
    }

    private long givenItem(long taskId, long unitId) {
        jdbc.update("INSERT INTO release_item (task_id, crid, type, unit_id, build_plan_id,"
                + " source_branch, version, plan_release_date, status, gate_status, test_status,"
                + " row_version, created_at, updated_at)"
                + " VALUES (?, ?, 'UNIT', ?, 'P-BUILD-P6F', 'release/p6f', 'v1', ?, 'TESTING',"
                + " 'CHECK_FAIL', ?, 0, NOW(3), NOW(3))",
            taskId, CRID, unitId, LocalDate.of(2026, 11, 26), TestStatus.TEST_PENDING.name());
        return idOf("SELECT id FROM release_item WHERE crid = ?", CRID);
    }

    private void givenMediumCheck() {
        jdbc.update("INSERT INTO gate_check (item_id, check_kind, stage, status, severity, issues,"
                + " attempt, created_at, updated_at)"
                + " VALUES (?, 'SONAR', 'IMAGE', 'FAIL', 'S2', 3, 1, NOW(3), NOW(3))", itemId);
    }

    private void assertExpiryNear(Instant expected) {
        Instant actual = expireAt();
        assertThat(actual).isBetween(expected.minusSeconds(10), expected.plusSeconds(10));
    }

    private Instant expireAt() {
        Timestamp value = jdbc.queryForObject(
            "SELECT expire_at FROM gate_waiver WHERE item_id = ? ORDER BY id DESC LIMIT 1",
            Timestamp.class, itemId);
        return value == null ? null : value.toInstant();
    }

    private String waiverStatus() {
        return jdbc.queryForObject("SELECT status FROM gate_waiver WHERE item_id = ?"
            + " ORDER BY id DESC LIMIT 1", String.class, itemId);
    }

    private int waiverCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM gate_waiver WHERE item_id = ?",
            Integer.class, itemId);
        return count == null ? 0 : count;
    }

    private long idOf(String sql, Object arg) {
        Long id = jdbc.queryForObject(sql, Long.class, arg);
        return id == null ? 0L : id;
    }
}