package com.sf.release.approval;

import com.sf.release.application.ApprovalService;
import com.sf.release.application.ApprovalService.DecideResult;
import com.sf.release.application.ApprovalStepView;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.approval.ApprovalRole;
import com.sf.release.domain.approval.ApprovalStatus;
import com.sf.release.domain.approval.ApprovalStep;
import com.sf.release.infra.repo.ApprovalMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.po.ApprovalPO;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The 分级审批 service against the real test schema: chain construction, idempotency, append and
 * drop, sequential signatures and the rejection that cancels the rest.
 *
 * <p><strong>Snapshot scope (V22).</strong> A chain belongs to one {@code admission_snapshot}, not to
 * the order: every call names the snapshot, and a resubmission appends a <em>second</em> chain under
 * a new snapshot id while the rejected generation keeps its rows. {@code ensureChain} and
 * {@code dropPending} lock the {@code release_order} row first, so every test seeds the order it
 * works on. The approval table itself has no foreign key to the order or the snapshot.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApprovalServiceTest {

    private static final long ORDER = 9101L;
    private static final long SNAPSHOT = 9201L;
    private static final long NEXT_SNAPSHOT = 9202L;
    private static final String ACTOR = "t21-lead";

    /**
     * How long the helper transaction holds its uncommitted row. A timing assumption: it gives the
     * test thread time to reach the unique-key check and block on the row. See the race test.
     */
    private static final long LOCK_HOLD_MS = 300;

    @Autowired ApprovalService approvals;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @BeforeEach
    void cleanChain() {
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM release_order WHERE id = ?", ORDER);
        jdbc.update("INSERT INTO release_order (id, publish_no, item_id, status, row_version,"
                + " created_at) VALUES (?, ?, ?, 'SUBMITTED', 0, NOW(3))",
            ORDER, "PO-T21-9101", 1L);
    }

    // ------------------------------------------------------------------ ensureChain

    @Test
    void ensure_chain_builds_the_signature_order() {
        List<ApprovalStep> chain = approvals.ensureChain(ORDER, SNAPSHOT,
            List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));

        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).stepNo()).isEqualTo(1);
        assertThat(chain.get(0).role()).isEqualTo(ApprovalRole.OPS_LEAD);
        assertThat(chain.get(0).status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(chain.get(0).decidedBy()).isNull();
        assertThat(chain.get(1).stepNo()).isEqualTo(2);
        assertThat(chain.get(1).role()).isEqualTo(ApprovalRole.DEPT_MANAGER);
    }

    @Test
    void ensure_chain_is_idempotent() {
        approvals.ensureChain(ORDER, SNAPSHOT,
            List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));
        List<ApprovalStep> second = approvals.ensureChain(ORDER, SNAPSHOT,
            List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));

        assertThat(second).hasSize(2);
        assertThat(second).extracting(ApprovalStep::stepNo).containsExactly(1, 2);
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void ensure_chain_can_add_the_second_role_later() {
        approvals.ensureChain(ORDER, SNAPSHOT, List.of(ApprovalRole.OPS_LEAD));
        List<ApprovalStep> chain = approvals.ensureChain(ORDER, SNAPSHOT,
            List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));

        assertThat(chain).extracting(ApprovalStep::role)
            .containsExactly(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER);
    }

    @Test
    void ensure_chain_preserves_a_decided_step() {
        approvals.ensureChain(ORDER, SNAPSHOT,
            List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));
        Timestamp createdAt = stepCreatedAt(1);

        approvals.decide(SNAPSHOT, 1, true, ACTOR, "同意");

        List<ApprovalStep> chain = approvals.ensureChain(ORDER, SNAPSHOT,
            List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));

        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(chain.get(0).decidedBy()).isEqualTo(ACTOR);
        assertThat(chain.get(0).comment()).isEqualTo("同意");
        assertThat(chain.get(1).status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(stepCreatedAt(1))
            .as("a decision is an explicit column update, not a whole-row rewrite")
            .isEqualTo(createdAt);
    }

    @Test
    void an_empty_requirement_leaves_the_chain_empty() {
        assertThat(approvals.ensureChain(ORDER, SNAPSHOT, List.of())).isEmpty();
        assertThat(approvals.stepViews(SNAPSHOT)).isEmpty();
        assertThat(count()).isZero();
    }

    @Test
    void a_chain_without_a_snapshot_id_is_an_internal_error() {
        assertThatThrownBy(() -> approvals.ensureChain(ORDER, 0L, List.of(ApprovalRole.OPS_LEAD)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(count()).isZero();
    }

    // ------------------------------------------------------------------ append / drop

    @Test
    void append_if_missing_adds_only_a_missing_role() {
        approvals.ensureChain(ORDER, SNAPSHOT, List.of(ApprovalRole.OPS_LEAD));

        assertThat(approvals.appendIfMissing(ORDER, SNAPSHOT, ApprovalRole.DEPT_MANAGER))
            .hasSize(2);
        assertThat(approvals.appendIfMissing(ORDER, SNAPSHOT, ApprovalRole.OPS_LEAD))
            .as("the present role is not appended twice")
            .hasSize(2);
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void drop_pending_removes_only_the_unsigned_step() {
        approvals.ensureChain(ORDER, SNAPSHOT,
            List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));
        approvals.decide(SNAPSHOT, 1, true, ACTOR, "同意");

        assertThat(approvals.dropPending(ORDER, SNAPSHOT, ApprovalRole.DEPT_MANAGER)).isEqualTo(1);
        assertThat(approvals.dropPending(ORDER, SNAPSHOT, ApprovalRole.DEPT_MANAGER)).isZero();
        assertThat(approvals.steps(SNAPSHOT)).hasSize(1);
        assertThat(approvals.steps(SNAPSHOT).get(0).status()).isEqualTo(ApprovalStatus.APPROVED);

        assertThat(approvals.dropPending(ORDER, SNAPSHOT, ApprovalRole.OPS_LEAD))
            .as("a decided step is history and is never dropped")
            .isZero();
        assertThat(approvals.steps(SNAPSHOT)).hasSize(1);
    }

    @Test
    void drop_then_ensure_reuses_the_freed_step_number_without_duplicating_a_role() {
        approvals.ensureChain(ORDER, SNAPSHOT,
            List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));
        approvals.decide(SNAPSHOT, 1, true, ACTOR, null);

        assertThat(approvals.dropPending(ORDER, SNAPSHOT, ApprovalRole.DEPT_MANAGER)).isEqualTo(1);
        List<ApprovalStep> chain = approvals.ensureChain(ORDER, SNAPSHOT,
            List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));

        assertThat(chain).extracting(ApprovalStep::stepNo).containsExactly(1, 2);
        assertThat(chain).extracting(ApprovalStep::role)
            .containsExactly(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER);
        assertThat(chain.get(1).status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void a_gap_left_by_dropping_the_first_step_does_not_break_the_next_append() {
        approvals.ensureChain(ORDER, SNAPSHOT,
            List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));
        assertThat(approvals.dropPending(ORDER, SNAPSHOT, ApprovalRole.OPS_LEAD)).isEqualTo(1);

        List<ApprovalStep> chain = approvals.ensureChain(ORDER, SNAPSHOT,
            List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));

        assertThat(chain).extracting(ApprovalStep::stepNo)
            .as("the append continues after the highest stored step, leaving the hole")
            .containsExactly(2, 3);
        assertThat(chain.stream().map(ApprovalStep::role).distinct().count())
            .as("no role is duplicated")
            .isEqualTo(2);
    }

    // ------------------------------------------------------------------ snapshot generations

    @Test
    void a_resubmission_starts_a_clean_chain_under_a_new_snapshot_and_keeps_the_old_one() {
        approvals.ensureChain(ORDER, SNAPSHOT,
            List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));
        approvals.decide(SNAPSHOT, 1, false, ACTOR, "窗口外风险过大");

        List<ApprovalStep> fresh = approvals.ensureChain(ORDER, NEXT_SNAPSHOT,
            List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));

        assertThat(fresh).extracting(ApprovalStep::stepNo)
            .as("the new snapshot's chain restarts at step 1 (V22 unique key is per snapshot)")
            .containsExactly(1, 2);
        assertThat(fresh).extracting(ApprovalStep::status)
            .containsExactly(ApprovalStatus.PENDING, ApprovalStatus.PENDING);

        assertThat(approvals.steps(SNAPSHOT)).extracting(ApprovalStep::status)
            .as("the rejected generation is history and is untouched")
            .containsExactly(ApprovalStatus.REJECTED, ApprovalStatus.CANCELED);

        assertThat(approvals.decide(NEXT_SNAPSHOT, 1, true, ACTOR, "重提后同意").complete())
            .isFalse();
        assertThat(approvals.decide(NEXT_SNAPSHOT, 2, true, "t21-manager", null).complete())
            .isTrue();
        assertThat(approvals.steps(SNAPSHOT).get(0).status())
            .as("deciding the new snapshot does not touch the old chain")
            .isEqualTo(ApprovalStatus.REJECTED);
    }

    @Test
    void the_two_snapshots_of_one_order_keep_separate_chain_reads() {
        approvals.ensureChain(ORDER, SNAPSHOT, List.of(ApprovalRole.OPS_LEAD));
        approvals.ensureChain(ORDER, NEXT_SNAPSHOT,
            List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));

        assertThat(approvals.stepViews(SNAPSHOT)).hasSize(1);
        assertThat(approvals.stepViews(NEXT_SNAPSHOT)).hasSize(2);
        assertThat(count())
            .as("both generations live in the table")
            .isEqualTo(3);
    }

    // ------------------------------------------------------------------ decide

    @Test
    void only_the_lowest_pending_step_can_be_decided() {
        approvals.ensureChain(ORDER, SNAPSHOT,
            List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));

        assertBizError(() -> approvals.decide(SNAPSHOT, 2, true, ACTOR, "同意"),
            BizError.STATE_NOT_ALLOWED, "nextPending");
        assertThat(approvals.steps(SNAPSHOT).get(1).status()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void a_decided_step_cannot_be_decided_again() {
        approvals.ensureChain(ORDER, SNAPSHOT,
            List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));
        approvals.decide(SNAPSHOT, 1, true, ACTOR, null);

        assertBizError(() -> approvals.decide(SNAPSHOT, 1, true, ACTOR, null),
            BizError.STATE_NOT_ALLOWED, "status");
    }

    @Test
    void a_missing_step_is_a_parameter_error() {
        approvals.ensureChain(ORDER, SNAPSHOT, List.of(ApprovalRole.OPS_LEAD));

        assertBizError(() -> approvals.decide(SNAPSHOT, 9, true, ACTOR, null),
            BizError.PARAM_INVALID, "stepNo");
    }

    @Test
    void the_actor_is_required() {
        approvals.ensureChain(ORDER, SNAPSHOT, List.of(ApprovalRole.OPS_LEAD));

        assertBizError(() -> approvals.decide(SNAPSHOT, 1, true, "  ", null),
            BizError.PARAM_INVALID, "actor");
    }

    @Test
    void an_actor_longer_than_the_column_is_rejected_before_writing() {
        approvals.ensureChain(ORDER, SNAPSHOT, List.of(ApprovalRole.OPS_LEAD));

        assertBizError(() -> approvals.decide(SNAPSHOT, 1, true, "u".repeat(33), null),
            BizError.PARAM_INVALID, "actor");
        assertThat(approvals.steps(SNAPSHOT).get(0).status()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void a_racing_append_is_absorbed_and_leaves_one_chain() {
        ApprovalMapper mapper = mock(ApprovalMapper.class);
        ReleaseOrderMapper orderMapper = mock(ReleaseOrderMapper.class);
        List<ApprovalPO> rows = new ArrayList<>();
        when(orderMapper.selectByIdForUpdate(ORDER)).thenReturn(givenLockedOrder());
        when(mapper.selectBySnapshot(SNAPSHOT)).thenAnswer(invocation -> List.copyOf(rows));
        when(mapper.selectBySnapshotForUpdate(SNAPSHOT))
            .thenAnswer(invocation -> List.copyOf(rows));
        when(mapper.insert(any(ApprovalPO.class))).thenAnswer(invocation -> {
            // The other transaction's insert wins, then our insert hits uk_approval_snapshot_step.
            rows.add(invocation.getArgument(0));
            throw new DuplicateKeyException("simulated concurrent append");
        });

        List<ApprovalStep> chain = new ApprovalService(orderMapper, mapper)
            .ensureChain(ORDER, SNAPSHOT, List.of(ApprovalRole.OPS_LEAD));

        assertThat(chain).hasSize(1);
        assertThat(chain.get(0).role()).isEqualTo(ApprovalRole.OPS_LEAD);
        assertThat(chain.get(0).status()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void a_collision_on_one_role_does_not_skip_the_remaining_roles() {
        ApprovalMapper mapper = mock(ApprovalMapper.class);
        ReleaseOrderMapper orderMapper = mock(ReleaseOrderMapper.class);
        List<ApprovalPO> rows = new ArrayList<>();
        when(orderMapper.selectByIdForUpdate(ORDER)).thenReturn(givenLockedOrder());
        when(mapper.selectBySnapshot(SNAPSHOT)).thenAnswer(invocation -> List.copyOf(rows));
        when(mapper.selectBySnapshotForUpdate(SNAPSHOT))
            .thenAnswer(invocation -> List.copyOf(rows));
        when(mapper.insert(any(ApprovalPO.class))).thenAnswer(invocation -> {
            ApprovalPO added = invocation.getArgument(0);
            rows.add(added);
            if (added.getStepNo() == 1) {
                throw new DuplicateKeyException("simulated concurrent append");
            }
            return 1;
        });

        List<ApprovalStep> chain = new ApprovalService(orderMapper, mapper)
            .ensureChain(ORDER, SNAPSHOT,
                List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));

        assertThat(chain).extracting(ApprovalStep::stepNo).containsExactly(1, 2);
        assertThat(chain).extracting(ApprovalStep::role)
            .containsExactly(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER);
    }

    @Test
    @Timeout(10)
    void a_step_committed_by_another_transaction_is_not_duplicated() throws Exception {
        // A second connection inserts step 1 and holds it uncommitted. This is the state in which
        // a plain re-read inside our transaction still sees the old snapshot (REPEATABLE READ), so
        // the unique key rejects our insert and only a LOCKING re-read can recover.
        CountDownLatch inserted = new CountDownLatch(1);
        AtomicReference<Throwable> holderFailure = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO approval (order_id, snapshot_id, step_no, role, status, created_at)"
                        + " VALUES (?, ?, 1, 'OPS_LEAD', 'PENDING', NOW(3))")) {
                    statement.setLong(1, ORDER);
                    statement.setLong(2, SNAPSHOT);
                    statement.executeUpdate();
                }
                inserted.countDown();
                Thread.sleep(LOCK_HOLD_MS);
                connection.commit();
            } catch (Throwable failure) {
                holderFailure.set(failure);
                inserted.countDown();
            }
        }, "approval-race-holder");
        holder.start();
        try {
            assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();

            List<ApprovalStep> chain = approvals.ensureChain(ORDER, SNAPSHOT,
                List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));

            assertThat(chain).extracting(ApprovalStep::stepNo).containsExactly(1, 2);
            assertThat(chain).extracting(ApprovalStep::role)
                .containsExactly(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER);
            assertThat(count()).isEqualTo(2);
        } finally {
            holder.join(5_000);
        }
        assertThat(holderFailure.get()).isNull();
    }

    @Test
    void approving_every_step_completes_the_chain() {
        approvals.ensureChain(ORDER, SNAPSHOT,
            List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));

        DecideResult first = approvals.decide(SNAPSHOT, 1, true, ACTOR, null);
        assertThat(first.approved()).isTrue();
        assertThat(first.complete()).isFalse();
        assertThat(first.rejected()).isFalse();

        DecideResult second = approvals.decide(SNAPSHOT, 2, true, "t21-manager", "同意");
        assertThat(second.complete()).isTrue();
        assertThat(second.rejected()).isFalse();
        assertThat(second.steps()).extracting(ApprovalStepView::status)
            .containsExactly("APPROVED", "APPROVED");
        assertThat(second.steps().get(1).decidedBy()).isEqualTo("t21-manager");
        assertThat(second.steps().get(1).decidedAt()).isNotNull();
    }

    @Test
    void rejecting_a_step_cancels_the_later_steps() {
        approvals.ensureChain(ORDER, SNAPSHOT,
            List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));

        DecideResult result = approvals.decide(SNAPSHOT, 1, false, ACTOR, "窗口外风险过大");

        assertThat(result.approved()).isFalse();
        assertThat(result.rejected()).isTrue();
        assertThat(result.complete()).isFalse();
        assertThat(result.steps()).extracting(ApprovalStepView::status)
            .containsExactly("REJECTED", "CANCELED");
        assertThat(result.steps().get(0).comment()).isEqualTo("窗口外风险过大");
        assertThat(result.steps().get(1).decidedBy())
            .as("a canceled step was never decided")
            .isNull();

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT step_no, status FROM approval WHERE snapshot_id = ? ORDER BY step_no", SNAPSHOT);
        assertThat(rows).extracting(row -> row.get("status"))
            .containsExactly("REJECTED", "CANCELED");
    }

    @Test
    void a_rejection_requires_a_reason() {
        approvals.ensureChain(ORDER, SNAPSHOT, List.of(ApprovalRole.OPS_LEAD));

        assertBizError(() -> approvals.decide(SNAPSHOT, 1, false, ACTOR, "   "),
            BizError.PARAM_INVALID, "comment");
        assertThat(approvals.steps(SNAPSHOT).get(0).status()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void step_views_flatten_the_domain_enums() {
        approvals.ensureChain(ORDER, SNAPSHOT, List.of(ApprovalRole.OPS_LEAD));

        List<ApprovalStepView> views = approvals.stepViews(SNAPSHOT);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).role()).isEqualTo("OPS_LEAD");
        assertThat(views.get(0).roleLabel()).isEqualTo("运维组长");
        assertThat(views.get(0).status()).isEqualTo("PENDING");
        assertThat(views.get(0).statusLabel()).isEqualTo("待审批");
        assertThat(views.get(0).stepNo()).isEqualTo(1);
        assertThat(views.get(0).decidedBy()).isNull();
    }

    // ------------------------------------------------------------------ fixtures

    private long count() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM approval", Long.class);
        return value == null ? 0L : value;
    }

    private Timestamp stepCreatedAt(int stepNo) {
        return jdbc.queryForObject(
            "SELECT created_at FROM approval WHERE snapshot_id = ? AND step_no = ?",
            Timestamp.class, SNAPSHOT, stepNo);
    }

    /** The order row the mocked mapper returns for the locking read; only the id is used. */
    private static ReleaseOrderPO givenLockedOrder() {
        ReleaseOrderPO order = new ReleaseOrderPO();
        order.setId(ORDER);
        return order;
    }

    private static void assertBizError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                       BizError error, String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getError()).isEqualTo(error);
            assertThat(ex.getPayload()).isInstanceOf(Map.class);
            assertThat((Map<String, Object>) ex.getPayload()).containsKey(field);
        });
    }
}