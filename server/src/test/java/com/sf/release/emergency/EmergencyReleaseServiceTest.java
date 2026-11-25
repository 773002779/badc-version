package com.sf.release.emergency;

import com.sf.release.application.EmergencyReleaseService;
import com.sf.release.application.EmergencyReleaseService.EmergencyResult;
import com.sf.release.application.ReleaseQueryService;
import com.sf.release.application.ReleaseRecordsService;
import com.sf.release.application.ReleaseRecordsService.ReleaseRecordView;
import com.sf.release.application.ReleaseRecordsService.ReleaseRecordsQuery;
import com.sf.release.application.ReviewService;
import com.sf.release.application.ReviewService.ReviewView;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.Roles;
import com.sf.release.infra.lock.InMemoryDistributedLock;
import com.sf.release.infra.security.TestUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The J11 紧急放行 use case against the real schema (P6-D design §1-§3, §5 items 1-3, 5-6):
 *
 * <ul>
 *   <li><strong>permission</strong>: only the ADMIN <em>role</em> succeeds; an OPS_LEAD holding
 *       {@code control:approve}, an OPS caller and an unauthenticated caller all answer 40009;</li>
 *   <li><strong>state matrix</strong>: {@code PENDING_APPROVAL → READY} voids every pending
 *       signature, {@code SUBMITTED → READY}, and {@code READY}/{@code DONE}/{@code GATE_PENDING}/
 *       {@code FAILED} answer 40001 with {@code currentStatus}; a repeat call is 40001;</li>
 *   <li><strong>reason and marker</strong>: blank/oversized is 40000, the four order columns and the
 *       {@code emergency_release} history row are written;</li>
 *   <li><strong>audit and notification</strong>: one {@code EMERGENCY_RELEASE} audit row (ORDER,
 *       before/after, reason) and the OPS/DEV/creator audience; a broken audit or notification table
 *       never reverts the release;</li>
 *   <li><strong>review</strong>: a freeze in force no longer blocks an emergency-released order, but
 *       the image and waiver checks still do;</li>
 *   <li><strong>read models</strong>: the release detail and the record row carry the four emergency
 *       fields.</li>
 * </ul>
 *
 * <p>Package {@code com.sf.release.emergency}, not {@code ..web..} (design §5 item 7).
 */
@SpringBootTest
@ActiveProfiles("test")
class EmergencyReleaseServiceTest {

    @Autowired EmergencyReleaseService emergencyRelease;
    @Autowired ReleaseQueryService queryService;
    @Autowired ReleaseRecordsService recordsService;
    @Autowired ReviewService reviews;
    @Autowired JdbcTemplate jdbc;

    private EmergencyFixtures fixtures;
    private EmergencyFixtures.Base base;

    @BeforeEach
    void setUp() {
        InMemoryDistributedLock.clearAllLeases();
        fixtures = new EmergencyFixtures(jdbc);
        fixtures.clean();
        base = fixtures.base();
        asAdmin();
    }

    @AfterEach
    void tearDown() {
        TestUserContext.clear();
    }

    // ------------------------------------------------------------------ permission (5.1)

    @Test
    void the_admin_role_releases_a_pending_approval_release_and_voids_the_signatures() {
        Released f = releaseFixture("CR-EM-ONE", "PO-EM-ONE", "PENDING_APPROVAL");

        EmergencyResult result = emergencyRelease.release(f.publishNo(), EmergencyFixtures.REASON);

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.itemStatus()).isEqualTo("READY");
        assertThat(result.emergency()).isTrue();
        assertThat(itemStatus(f.itemId())).isEqualTo("READY");
        assertThat(orderStatus(f.publishNo())).isEqualTo("READY");

        Map<String, Object> order = jdbc.queryForMap("SELECT emergency_release, emergency_reason,"
            + " emergency_by, emergency_at FROM release_order WHERE id = ?", f.orderId());
        assertThat(((Number) order.get("emergency_release")).intValue()).isEqualTo(1);
        assertThat(order.get("emergency_reason")).isEqualTo(EmergencyFixtures.REASON);
        assertThat(order.get("emergency_by")).isEqualTo(EmergencyFixtures.ADMIN);
        assertThat(order.get("emergency_at")).isNotNull();

        assertThat(jdbc.queryForList("SELECT status FROM approval WHERE snapshot_id = ?",
            String.class, f.snapshotId())).containsOnly("CANCELED");
        assertThat(jdbc.queryForObject("SELECT comment FROM approval WHERE snapshot_id = ?"
            + " AND step_no = 1", String.class, f.snapshotId()))
            .contains("紧急放行").contains(EmergencyFixtures.REASON);
        assertThat(jdbc.queryForObject("SELECT decided_by FROM approval WHERE snapshot_id = ?"
            + " AND step_no = 1", String.class, f.snapshotId()))
            .as("nobody signed a voided step").isNull();

        assertThat(historyRows()).isEqualTo(1);
        Map<String, Object> history = jdbc.queryForMap("SELECT order_id, publish_no, reason, actor,"
            + " created_at FROM emergency_release");
        assertThat(((Number) history.get("order_id")).longValue()).isEqualTo(f.orderId());
        assertThat(history.get("publish_no")).isEqualTo(f.publishNo());
        assertThat(history.get("reason")).isEqualTo(EmergencyFixtures.REASON);
        assertThat(history.get("actor")).isEqualTo(EmergencyFixtures.ADMIN);
        assertThat(history.get("created_at")).isNotNull();
    }

    @Test
    void a_non_admin_is_refused_even_when_holding_control_approve() {
        Released f = releaseFixture("CR-EM-PERM", "PO-EM-PERM", "PENDING_APPROVAL");

        TestUserContext.as(EmergencyFixtures.LEAD, Set.of(Roles.OPS_LEAD),
            Set.of(Permissions.CONTROL_APPROVE));
        assertBizError(BizError.FORBIDDEN,
            () -> emergencyRelease.release(f.publishNo(), EmergencyFixtures.REASON), null);

        TestUserContext.as(EmergencyFixtures.OPS, Set.of(Roles.OPS),
            Set.of(Permissions.CONTROL_APPROVE, Permissions.CONTROL_EXECUTE_PROD));
        assertBizError(BizError.FORBIDDEN,
            () -> emergencyRelease.release(f.publishNo(), EmergencyFixtures.REASON), null);

        TestUserContext.clear();
        assertBizError(BizError.FORBIDDEN,
            () -> emergencyRelease.release(f.publishNo(), EmergencyFixtures.REASON), null);

        assertThat(itemStatus(f.itemId())).as("a refused call changed nothing")
            .isEqualTo("PENDING_APPROVAL");
        assertThat(historyRows()).isZero();
    }

    @Test
    void an_admin_still_needs_an_existing_order_and_a_missing_item_is_40000() {
        asAdmin();
        assertBizError(BizError.PARAM_INVALID,
            () -> emergencyRelease.release("PO-EM-NOSUCH", EmergencyFixtures.REASON), null);

        // An order whose 变更项 row disappeared: the design answers 40000, not a marker without an
        // item (there would be no execution target).
        jdbc.update("INSERT INTO release_order (publish_no, item_id, status, row_version,"
            + " created_at) VALUES ('PO-EM-ORPHAN', 9999999, 'SUBMITTED', 0, NOW(3))");
        assertBizError(BizError.PARAM_INVALID,
            () -> emergencyRelease.release("PO-EM-ORPHAN", EmergencyFixtures.REASON), null);
    }

    // ------------------------------------------------------------------ state matrix (5.2)

    @Test
    void a_submitted_release_takes_the_no_approval_edge() {
        Released f = releaseFixture("CR-EM-SUBMITTED", "PO-EM-SUBMITTED", "SUBMITTED");

        EmergencyResult result = emergencyRelease.release(f.publishNo(), EmergencyFixtures.REASON);

        assertThat(result.itemStatus()).isEqualTo("READY");
        assertThat(itemStatus(f.itemId())).isEqualTo("READY");
        assertThat(orderStatus(f.publishNo())).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval", Integer.class)).isZero();
        assertThat(historyRows()).isEqualTo(1);
    }

    @Test
    void ready_done_gate_pending_and_failed_are_refused_with_the_current_status() {
        for (String status : List.of("READY", "DONE", "GATE_PENDING", "FAILED", "GATE_FAILED")) {
            Released f = releaseFixture("CR-EM-" + status, "PO-EM-" + status, status);
            assertBizErrorWithStatus(BizError.STATE_NOT_ALLOWED,
                () -> emergencyRelease.release(f.publishNo(), EmergencyFixtures.REASON), status);
        }

        Released ready = releaseFixture("CR-EM-READY-MSG", "PO-EM-READY-MSG", "READY");
        assertThatThrownBy(() -> emergencyRelease.release(ready.publishNo(),
            EmergencyFixtures.REASON))
            .isInstanceOfSatisfying(BizException.class, ex -> {
                assertThat(ex.getError()).isEqualTo(BizError.STATE_NOT_ALLOWED);
                assertThat(ex.getMessage()).contains("无需紧急放行");
                assertThat(String.valueOf(ex.getPayload())).contains("READY");
            });
        assertThat(historyRows()).isZero();
    }

    @Test
    void a_repeat_call_is_refused_and_writes_no_second_history_row() {
        Released f = releaseFixture("CR-EM-TWICE", "PO-EM-TWICE", "PENDING_APPROVAL");
        emergencyRelease.release(f.publishNo(), EmergencyFixtures.REASON);

        assertBizErrorWithStatus(BizError.STATE_NOT_ALLOWED,
            () -> emergencyRelease.release(f.publishNo(), EmergencyFixtures.REASON), "READY");
        assertThat(historyRows()).isEqualTo(1);
        assertThat(auditRows()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ reason and marker (5.3)

    @Test
    void the_reason_is_required_and_capped_at_two_hundred_characters() {
        Released f = releaseFixture("CR-EM-REASON", "PO-EM-REASON", "PENDING_APPROVAL");
        asAdmin();

        assertBizError(BizError.PARAM_INVALID,
            () -> emergencyRelease.release(f.publishNo(), null), null);
        assertBizError(BizError.PARAM_INVALID,
            () -> emergencyRelease.release(f.publishNo(), "   "), null);
        assertBizError(BizError.PARAM_INVALID,
            () -> emergencyRelease.release(f.publishNo(), "x".repeat(201)), null);
        assertThat(itemStatus(f.itemId())).isEqualTo("PENDING_APPROVAL");

        String longest = "x".repeat(200);
        assertThat(emergencyRelease.release(f.publishNo(), longest).itemStatus())
            .isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT emergency_reason FROM release_order WHERE id = ?",
            String.class, f.orderId())).hasSize(200).isEqualTo(longest);
    }

    // ------------------------------------------------------------------ audit / notification (5.5)

    @Test
    void the_audit_row_carries_the_before_after_and_reason_and_the_notification_reaches_ops_dev() {
        Released f = releaseFixture("CR-EM-AUDIT", "PO-EM-AUDIT", "PENDING_APPROVAL");
        emergencyRelease.release(f.publishNo(), EmergencyFixtures.REASON);

        Map<String, Object> audit = jdbc.queryForMap("SELECT actor, action, object_type, object_id,"
            + " object_label, reason, before_json, after_json, scope_unit_id, scope_subsystem_id"
            + " FROM audit_log WHERE action = 'EMERGENCY_RELEASE'");
        assertThat(audit.get("actor")).isEqualTo(EmergencyFixtures.ADMIN);
        assertThat(audit.get("object_type")).isEqualTo("ORDER");
        assertThat(audit.get("object_id")).isEqualTo(f.publishNo());
        assertThat(audit.get("object_label")).isEqualTo(f.publishNo());
        assertThat(audit.get("reason")).isEqualTo(EmergencyFixtures.REASON);
        assertThat(String.valueOf(audit.get("before_json")))
            .contains("PENDING_APPROVAL").contains("\"emergencyRelease\"").contains("false");
        assertThat(String.valueOf(audit.get("after_json")))
            .contains("READY").contains("\"emergencyRelease\"").contains("true")
            .contains(EmergencyFixtures.REASON);
        assertThat(((Number) audit.get("scope_unit_id")).longValue())
            .isEqualTo(base.unitId());
        assertThat(((Number) audit.get("scope_subsystem_id")).longValue())
            .isEqualTo(base.subsystemId());

        assertThat(recipientsOf(f.publishNo()))
            .as("the task OPS/DEV members plus the creator").contains(
                EmergencyFixtures.DEV, EmergencyFixtures.OPS, EmergencyFixtures.OWNER)
            .doesNotContain(EmergencyFixtures.TESTER, EmergencyFixtures.PRODUCT);
        assertThat(jdbc.queryForObject("SELECT object_type FROM notification WHERE event_key ="
            + " 'EMERGENCY_RELEASE' LIMIT 1", String.class)).isEqualTo("ORDER");
        assertThat(jdbc.queryForObject("SELECT body FROM notification WHERE event_key ="
            + " 'EMERGENCY_RELEASE' LIMIT 1", String.class))
            .contains(EmergencyFixtures.REASON)
            .contains("审批已作废")
            .contains("不可撤销");
    }

    @Test
    void a_broken_audit_table_never_reverts_the_release() {
        Released f = releaseFixture("CR-EM-AUDIT-BROKEN", "PO-EM-AUDIT-BROKEN",
            "PENDING_APPROVAL");
        jdbc.execute("RENAME TABLE audit_log TO audit_log_broken");
        try {
            EmergencyResult result = emergencyRelease.release(f.publishNo(),
                EmergencyFixtures.REASON);

            assertThat(result.itemStatus()).isEqualTo("READY");
            assertThat(itemStatus(f.itemId())).isEqualTo("READY");
            assertThat(historyRows()).isEqualTo(1);
        } finally {
            jdbc.execute("RENAME TABLE audit_log_broken TO audit_log");
        }
    }

    @Test
    void a_broken_notification_table_never_reverts_the_release() {
        Released f = releaseFixture("CR-EM-NOTIFY-BROKEN", "PO-EM-NOTIFY-BROKEN",
            "PENDING_APPROVAL");
        jdbc.execute("RENAME TABLE notification TO notification_broken");
        try {
            EmergencyResult result = emergencyRelease.release(f.publishNo(),
                EmergencyFixtures.REASON);

            assertThat(result.status()).isEqualTo("READY");
            assertThat(orderStatus(f.publishNo())).isEqualTo("READY");
            assertThat(historyRows()).isEqualTo(1);
        } finally {
            jdbc.execute("RENAME TABLE notification_broken TO notification");
        }
    }

    // ------------------------------------------------------------------ execution review (5.4)

    @Test
    void the_freeze_no_longer_blocks_an_emergency_released_order_but_image_and_waiver_do() {
        Released f = releaseFixture("CR-EM-REVIEW", "PO-EM-REVIEW", "PENDING_APPROVAL");
        long instanceId = fixtures.givenInstance(f.orderId(), "PL-EM-REVIEW", "PAUSED",
            EmergencyFixtures.DIGEST);
        long freezeId = fixtures.givenFreezeInForce(base.subsystemId());

        ReviewView before = reviews.review(f.publishNo());
        assertThat(before.frozen()).isTrue();
        assertThat(before.blocked()).as("an ordinary signing release waits for the freeze")
            .isTrue();
        assertThat(before.reasons()).anyMatch(reason -> reason.contains("封板"));

        emergencyRelease.release(f.publishNo(), EmergencyFixtures.REASON);

        ReviewView after = reviews.review(f.publishNo());
        assertThat(after.frozen()).as("the physical freeze is still reported").isTrue();
        assertThat(after.freezeIds()).containsExactly(freezeId);
        assertThat(after.blocked()).as("but it no longer blocks the emergency release").isFalse();
        assertThat(after.reasons()).isEmpty();

        // The image check is untouched: a rebuilt image still refuses the execution (40007).
        jdbc.update("UPDATE pipeline_instance SET image_digest = 'sha256:rebuilt' WHERE id = ?",
            instanceId);
        ReviewView rebuilt = reviews.review(f.publishNo());
        assertThat(rebuilt.digestMatched()).isFalse();
        assertThat(rebuilt.blocked()).isTrue();
        assertThat(rebuilt.reasons()).anyMatch(reason -> reason.contains("digest"));

        // The waiver check is untouched: an expired waiver of the snapshot still refuses (40002).
        jdbc.update("UPDATE pipeline_instance SET image_digest = ? WHERE id = ?",
            EmergencyFixtures.DIGEST, instanceId);
        long waiverId = fixtures.givenWaiver(f.itemId(), f.orderId(), "APPROVED",
            Instant.now().minusSeconds(60));
        jdbc.update("UPDATE admission_snapshot SET waiver_ids = ? WHERE id = ?",
            "[" + waiverId + "]", f.snapshotId());
        ReviewView waiver = reviews.review(f.publishNo());
        assertThat(waiver.waiverInvalid()).isTrue();
        assertThat(waiver.blocked()).isTrue();
        assertThat(waiver.reasons()).anyMatch(reason -> reason.contains("豁免"));
    }

    // ------------------------------------------------------------------ read models (5.6)

    @Test
    void the_release_detail_and_the_record_row_expose_the_four_emergency_fields() {
        Released released = releaseFixture("CR-EM-DETAIL", "PO-EM-DETAIL", "PENDING_APPROVAL");
        emergencyRelease.release(released.publishNo(), EmergencyFixtures.REASON);
        releaseFixture("CR-EM-NORMAL", "PO-EM-NORMAL", "READY");

        TestUserContext.as(EmergencyFixtures.ADMIN, Set.of(Roles.ADMIN), Set.of());
        ReleaseQueryService.ReleaseDetail detail = queryService.detail(released.publishNo());
        assertThat(detail.emergencyRelease()).isEqualTo("true");
        assertThat(detail.emergencyReason()).isEqualTo(EmergencyFixtures.REASON);
        assertThat(detail.emergencyBy()).isEqualTo(EmergencyFixtures.ADMIN);
        assertThat(detail.emergencyAt()).isNotBlank();

        ReleaseQueryService.ReleaseDetail normal = queryService.detail("PO-EM-NORMAL");
        assertThat(normal.emergencyRelease()).isEqualTo("false");
        assertThat(normal.emergencyReason()).isNull();
        assertThat(normal.emergencyBy()).isNull();
        assertThat(normal.emergencyAt()).isNull();

        List<ReleaseRecordView> rows = recordsService.page(
            new ReleaseRecordsQuery(1, 10, null, null, null, null)).records();
        ReleaseRecordView releasedRow = rowOf(rows, released.publishNo());
        assertThat(releasedRow.emergencyRelease()).isEqualTo("true");
        assertThat(releasedRow.emergencyReason()).isEqualTo(EmergencyFixtures.REASON);
        assertThat(releasedRow.emergencyBy()).isEqualTo(EmergencyFixtures.ADMIN);
        assertThat(releasedRow.emergencyAt()).isNotBlank();
        ReleaseRecordView normalRow = rowOf(rows, "PO-EM-NORMAL");
        assertThat(normalRow.emergencyRelease()).isEqualTo("false");
        assertThat(normalRow.emergencyReason()).isNull();
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private record Released(long itemId, long orderId, String publishNo, long snapshotId) {
    }

    /**
     * A 变更项 + 发布单 in the given state. {@code PENDING_APPROVAL} and {@code SUBMITTED} carry an
     * admission snapshot; the signing state also carries the two pending steps the freeze/lift flows
     * produce.
     */
    private Released releaseFixture(String crid, String publishNo, String status) {
        long itemId = fixtures.givenItem(base.taskId(), base.unitId(), crid,
            "release/em-" + publishNo, status, "CHECK_PASS");
        long orderId = fixtures.givenOrder(itemId, publishNo, status);
        long snapshotId = 0L;
        if ("PENDING_APPROVAL".equals(status) || "SUBMITTED".equals(status)) {
            snapshotId = fixtures.givenSnapshot(orderId, itemId, EmergencyFixtures.DEV,
                EmergencyFixtures.DIGEST);
        }
        if ("PENDING_APPROVAL".equals(status)) {
            fixtures.givenApproval(orderId, snapshotId, 1, "OPS_LEAD", "PENDING");
            fixtures.givenApproval(orderId, snapshotId, 2, "DEPT_MANAGER", "PENDING");
        }
        return new Released(itemId, orderId, publishNo, snapshotId);
    }

    private void asAdmin() {
        TestUserContext.as(EmergencyFixtures.ADMIN, Set.of(Roles.ADMIN), Set.of());
    }

    private String itemStatus(long itemId) {
        return jdbc.queryForObject("SELECT status FROM release_item WHERE id = ?", String.class,
            itemId);
    }

    private String orderStatus(String publishNo) {
        return jdbc.queryForObject("SELECT status FROM release_order WHERE publish_no = ?",
            String.class, publishNo);
    }

    private int historyRows() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM emergency_release",
            Integer.class);
        return count == null ? 0 : count;
    }

    private int auditRows() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log"
            + " WHERE action = 'EMERGENCY_RELEASE'", Integer.class);
        return count == null ? 0 : count;
    }

    private List<String> recipientsOf(String publishNo) {
        return jdbc.queryForList("SELECT recipient FROM notification WHERE event_key ="
            + " 'EMERGENCY_RELEASE' AND object_id = ? AND channel = 'INAPP'", String.class,
            publishNo);
    }

    private static ReleaseRecordView rowOf(List<ReleaseRecordView> rows, String publishNo) {
        return rows.stream().filter(row -> publishNo.equals(row.publishNo())).findFirst()
            .orElseThrow(() -> new AssertionError("no record row for " + publishNo));
    }

    private static void assertBizError(BizError expected, Runnable call, String contains) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getError()).isEqualTo(expected);
            if (contains != null) {
                assertThat(String.valueOf(ex.getPayload())).contains(contains);
            }
        });
    }

    private static void assertBizErrorWithStatus(BizError expected, Runnable call, String status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getError()).isEqualTo(expected);
            assertThat(ex.getPayload()).isInstanceOf(Map.class);
            assertThat((Map<String, Object>) ex.getPayload())
                .containsEntry("currentStatus", status);
        });
    }
}