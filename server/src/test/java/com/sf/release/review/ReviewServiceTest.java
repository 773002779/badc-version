package com.sf.release.review;

import com.sf.release.application.AdmissionView;
import com.sf.release.application.ReviewService;
import com.sf.release.application.ReviewService.ReviewView;
import com.sf.release.application.SubmitProdService;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The 执行前复核 against the real test schema (tech design 4.6): the three facts the executor compares
 * with the admission snapshot, and the important distinction between a freeze <em>in force now</em>
 * and one merely declared for later.
 *
 * <p>The fixture is written by hand (item + order + snapshot + instance + waiver/freeze rows) because
 * the review is a comparison, not a workflow: {@code AdmissionServiceTest} owns the admission side.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReviewServiceTest {

    private static final String USER = "rv-user";
    private static final String PUBLISH_NO = "PO-RV-1";
    private static final String DIGEST = "sha256:review";

    @Autowired ReviewService reviews;
    @Autowired SubmitProdService submitProdService;
    @Autowired JdbcTemplate jdbc;

    private long subsystemId;
    private long unitId;
    private long itemId;
    private long orderId;
    private long instanceId;

    @BeforeEach
    void setUp() {
        TestUserContext.allUnits(USER);
        cleanDatabase();
        subsystemId = givenSubsystem("RV-SYS");
        unitId = givenUnit(subsystemId, "rv-unit");
        itemId = givenItem(unitId);
        orderId = givenOrder(itemId);
        instanceId = givenInstance(orderId, DIGEST);
    }

    @AfterEach
    void tearDown() {
        TestUserContext.clear();
    }

    // ------------------------------------------------------------------ the three checks

    @Test
    void a_clean_release_is_not_blocked_and_every_check_passes() {
        givenSnapshot("[]", DIGEST);

        ReviewView review = reviews.review(PUBLISH_NO);

        assertThat(review.blocked()).isFalse();
        assertThat(review.reasons()).isEmpty();
        assertThat(review.frozen()).isFalse();
        assertThat(review.freezeIds()).isEmpty();
        assertThat(review.waiverInvalid()).isFalse();
        assertThat(review.digestMatched()).isTrue();
        assertThat(review.checkedAt()).isNotNull();
    }

    @Test
    void a_freeze_in_force_now_blocks_the_execution() {
        givenSnapshot("[]", DIGEST);
        long freezeId = givenFreeze("ACTIVE", Instant.now().minusSeconds(60),
            Instant.now().plusSeconds(3600), "大促封板");

        ReviewView review = reviews.review(PUBLISH_NO);

        assertThat(review.frozen()).isTrue();
        assertThat(review.freezeIds()).containsExactly(freezeId);
        assertThat(review.blocked()).isTrue();
        assertThat(review.reasons()).anyMatch(reason -> reason.contains("封板"));
    }

    @Test
    void a_freeze_declared_for_later_does_not_block_today() {
        // It governed admission (the second signature) but is not in force now, and the executor
        // must not stop a release before the freeze has started (design 4.6: only "now" freezes).
        givenSnapshot("[]", DIGEST);
        givenFreeze("ACTIVE", Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200),
            "下周大促");

        ReviewView review = reviews.review(PUBLISH_NO);

        assertThat(review.frozen()).isFalse();
        assertThat(review.freezeIds()).isEmpty();
        assertThat(review.blocked()).isFalse();
    }

    @Test
    void a_released_freeze_does_not_block_any_more() {
        givenSnapshot("[]", DIGEST);
        givenFreeze("RELEASED", Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600),
            "已解除");

        ReviewView review = reviews.review(PUBLISH_NO);

        assertThat(review.frozen()).isFalse();
        assertThat(review.blocked()).isFalse();
    }

    @Test
    void an_expired_waiver_of_the_snapshot_blocks_the_execution() {
        long waiverId = givenWaiver("APPROVED", Instant.now().minusSeconds(60));
        givenSnapshot("[" + waiverId + "]", DIGEST);

        ReviewView review = reviews.review(PUBLISH_NO);

        assertThat(review.waiverInvalid()).isTrue();
        assertThat(review.blocked()).isTrue();
        assertThat(review.reasons()).anyMatch(reason -> reason.contains("豁免"));
    }

    @Test
    void a_revoked_or_canceled_waiver_of_the_snapshot_blocks_the_execution() {
        long waiverId = givenWaiver("CANCELED", Instant.now().plusSeconds(86_400));
        givenSnapshot("[" + waiverId + "]", DIGEST);

        ReviewView review = reviews.review(PUBLISH_NO);

        assertThat(review.waiverInvalid()).isTrue();
        assertThat(review.blocked()).isTrue();
    }

    @Test
    void a_valid_approved_waiver_of_the_snapshot_does_not_block() {
        long waiverId = givenWaiver("APPROVED", Instant.now().plusSeconds(86_400));
        givenSnapshot("[" + waiverId + "]", DIGEST);

        ReviewView review = reviews.review(PUBLISH_NO);

        assertThat(review.waiverInvalid()).isFalse();
        assertThat(review.blocked()).isFalse();
    }

    @Test
    void a_missing_waiver_row_counts_as_invalid_too() {
        givenSnapshot("[999999]", DIGEST);

        ReviewView review = reviews.review(PUBLISH_NO);

        assertThat(review.waiverInvalid()).isTrue();
        assertThat(review.blocked()).isTrue();
    }

    @Test
    void an_image_digest_that_differs_from_the_snapshot_is_a_hard_block() {
        givenSnapshot("[]", DIGEST);
        jdbc.update("UPDATE pipeline_instance SET image_digest = 'sha256:rebuilt' WHERE id = ?",
            instanceId);

        ReviewView review = reviews.review(PUBLISH_NO);

        assertThat(review.digestMatched()).isFalse();
        assertThat(review.blocked()).isTrue();
        assertThat(review.reasons()).anyMatch(reason -> reason.contains("digest"));
    }

    @Test
    void a_release_without_a_snapshot_cannot_be_reviewed() {
        assertThatThrownBy(() -> reviews.review(PUBLISH_NO))
            .isInstanceOfSatisfying(BizException.class,
                ex -> assertThat(ex.getError()).isEqualTo(BizError.STATE_NOT_ALLOWED));
    }

    // ------------------------------------------------------------------ admission preview

    @Test
    void the_admission_read_carries_the_review_preview_of_the_stored_snapshot() {
        givenSnapshot("[]", DIGEST);

        AdmissionView view = submitProdService.admission(PUBLISH_NO);

        assertThat(view.review()).isInstanceOf(ReviewView.class);
        ReviewView review = (ReviewView) view.review();
        assertThat(review.blocked()).isFalse();
        assertThat(review.digestMatched()).isTrue();
    }

    // ------------------------------------------------------------------ fixtures

    private void givenSnapshot(String waiverIds, String digest) {
        jdbc.update("INSERT INTO admission_snapshot (order_id, item_id, submitted_by,"
                + " planned_release_time, in_window, window_id, frozen, freeze_ids, max_severity,"
                + " check_conclusion, waiver_ids, test_conclusion, test_pass_by, oa_ticket_no,"
                + " image_digest, chain_json, created_at)"
                + " VALUES (?, ?, ?, NOW(3), 0, NULL, 0, '[]', 'S3', 'PASS', ?, 'TEST_PASS', ?,"
                + " 'OA-RV-1', ?, '[]', NOW(3))",
            orderId, itemId, USER, waiverIds, USER, digest);
    }

    private long givenFreeze(String status, Instant startAt, Instant endAt, String reason) {
        jdbc.update("INSERT INTO freeze_event (scope, subsystem_id, reason, impact, initiator_id,"
                + " start_at, end_at, status, created_at)"
                + " VALUES ('SUBSYSTEM', ?, ?, NULL, ?, ?, ?, ?, NOW(3))",
            subsystemId, reason, USER, Timestamp.from(startAt), Timestamp.from(endAt), status);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM freeze_event", Long.class);
        return id == null ? 0L : id;
    }

    private long givenWaiver(String status, Instant expireAt) {
        jdbc.update("INSERT INTO gate_waiver (item_id, order_id, status, reason, impact,"
                + " requested_by, requested_at, approved_by, approved_at, decide_reason, expire_at,"
                + " commit_sha, image_digest, created_at, updated_at)"
                + " VALUES (?, ?, ?, '已知问题', '影响可控', ?, NOW(3), ?, NOW(3), '同意', ?, NULL,"
                + " ?, NOW(3), NOW(3))",
            itemId, orderId, status, USER, USER, Timestamp.from(expireAt), DIGEST);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM gate_waiver", Long.class);
        return id == null ? 0L : id;
    }

    private long givenSubsystem(String name) {
        jdbc.update("INSERT INTO subsystem (name, owner_id, status, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', NOW(3), NOW(3))", name, USER);
        Long id = jdbc.queryForObject("SELECT id FROM subsystem WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private long givenUnit(long subsystem, String name) {
        jdbc.update("INSERT INTO deploy_unit (subsystem_id, name, type, owner_id, status,"
                + " created_at, updated_at) VALUES (?, ?, 'UNIT', ?, 'ACTIVE', NOW(3), NOW(3))",
            subsystem, name, USER);
        Long id = jdbc.queryForObject("SELECT id FROM deploy_unit WHERE name = ?", Long.class, name);
        return id == null ? 0L : id;
    }

    private long givenItem(long unit) {
        jdbc.update("INSERT INTO release_item (task_id, crid, type, unit_id, build_plan_id,"
                + " source_branch, version, plan_release_date, status, gate_status, test_status,"
                + " row_version, created_at, updated_at)"
                + " VALUES (1, 'CR-RV-1', 'UNIT', ?, 'P-BUILD', 'release/rv', 'v1', '2026-11-25',"
                + " 'READY', 'CHECK_PASS', 'TEST_PASS', 0, NOW(3), NOW(3))", unit);
        Long id = jdbc.queryForObject("SELECT id FROM release_item WHERE crid = 'CR-RV-1'",
            Long.class);
        return id == null ? 0L : id;
    }

    private long givenOrder(long item) {
        jdbc.update("INSERT INTO release_order (publish_no, item_id, planned_release_time,"
                + " window_result, deadline_at, status, row_version, created_at)"
                + " VALUES (?, ?, NOW(3), 'OUT', NOW(3), 'READY', 0, NOW(3))", PUBLISH_NO, item);
        Long id = jdbc.queryForObject("SELECT id FROM release_order WHERE publish_no = ?",
            Long.class, PUBLISH_NO);
        return id == null ? 0L : id;
    }

    private long givenInstance(long order, String digest) {
        jdbc.update("INSERT INTO pipeline_instance (order_id, pipeline_no, exec_type, trigger_type,"
                + " current_node, status, image_digest, commit_sha, start_at, created_at)"
                + " VALUES (?, 'PL-RV-1', 'ONLINE', 'INITIAL', 'PROD_DEPLOY', 'PAUSED', ?,"
                + " '0123456789abcdef0123456789abcdef01234567', NOW(3), NOW(3))", order, digest);
        Long id = jdbc.queryForObject("SELECT id FROM pipeline_instance WHERE pipeline_no = 'PL-RV-1'",
            Long.class);
        return id == null ? 0L : id;
    }

    private void cleanDatabase() {
        jdbc.update("DELETE FROM gate_waiver");
        jdbc.update("DELETE FROM gate_check");
        jdbc.update("DELETE FROM freeze_event");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM admission_snapshot");
        jdbc.update("DELETE FROM pipeline_instance");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM release_item");
        jdbc.update("DELETE FROM deploy_unit");
        jdbc.update("DELETE FROM subsystem");
        jdbc.update("DELETE FROM user_scope");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user");
    }
}