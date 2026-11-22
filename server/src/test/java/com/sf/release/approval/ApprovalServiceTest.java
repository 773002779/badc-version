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
import com.sf.release.infra.repo.po.ApprovalPO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The 分级审批 service against the real test schema: chain construction, idempotency, append and
 * drop, sequential signatures and the rejection that cancels the rest.
 *
 * <p>The approval table has no foreign key to {@code release_order} (the chain is written inside
 * the admission transaction), so the test uses a fixed order id and seeds only {@code approval}.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApprovalServiceTest {

    private static final long ORDER = 9101L;
    private static final String ACTOR = "t21-lead";

    @Autowired ApprovalService approvals;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanChain() {
        jdbc.update("DELETE FROM approval");
    }

    // ------------------------------------------------------------------ ensureChain

    @Test
    void ensure_chain_builds_the_signature_order() {
        List<ApprovalStep> chain = approvals.ensureChain(ORDER,
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
        approvals.ensureChain(ORDER, List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));
        List<ApprovalStep> second = approvals.ensureChain(ORDER,
            List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));

        assertThat(second).hasSize(2);
        assertThat(second).extracting(ApprovalStep::stepNo).containsExactly(1, 2);
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void ensure_chain_can_add_the_second_role_later() {
        approvals.ensureChain(ORDER, List.of(ApprovalRole.OPS_LEAD));
        List<ApprovalStep> chain = approvals.ensureChain(ORDER,
            List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));

        assertThat(chain).extracting(ApprovalStep::role)
            .containsExactly(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER);
    }

    @Test
    void ensure_chain_preserves_a_decided_step() {
        approvals.ensureChain(ORDER, List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));
        Timestamp createdAt = stepCreatedAt(1);

        approvals.decide(ORDER, 1, true, ACTOR, "同意");

        List<ApprovalStep> chain = approvals.ensureChain(ORDER,
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
        assertThat(approvals.ensureChain(ORDER, List.of())).isEmpty();
        assertThat(approvals.stepViews(ORDER)).isEmpty();
        assertThat(count()).isZero();
    }

    // ------------------------------------------------------------------ append / drop

    @Test
    void append_if_missing_adds_only_a_missing_role() {
        approvals.ensureChain(ORDER, List.of(ApprovalRole.OPS_LEAD));

        assertThat(approvals.appendIfMissing(ORDER, ApprovalRole.DEPT_MANAGER)).hasSize(2);
        assertThat(approvals.appendIfMissing(ORDER, ApprovalRole.OPS_LEAD))
            .as("the present role is not appended twice")
            .hasSize(2);
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void drop_pending_removes_only_the_unsigned_step() {
        approvals.ensureChain(ORDER, List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));
        approvals.decide(ORDER, 1, true, ACTOR, "同意");

        assertThat(approvals.dropPending(ORDER, ApprovalRole.DEPT_MANAGER)).isEqualTo(1);
        assertThat(approvals.dropPending(ORDER, ApprovalRole.DEPT_MANAGER)).isZero();
        assertThat(approvals.steps(ORDER)).hasSize(1);
        assertThat(approvals.steps(ORDER).get(0).status()).isEqualTo(ApprovalStatus.APPROVED);

        assertThat(approvals.dropPending(ORDER, ApprovalRole.OPS_LEAD))
            .as("a decided step is history and is never dropped")
            .isZero();
        assertThat(approvals.steps(ORDER)).hasSize(1);
    }

    @Test
    void drop_then_ensure_reuses_the_freed_step_number_without_duplicating_a_role() {
        approvals.ensureChain(ORDER, List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));
        approvals.decide(ORDER, 1, true, ACTOR, null);

        assertThat(approvals.dropPending(ORDER, ApprovalRole.DEPT_MANAGER)).isEqualTo(1);
        List<ApprovalStep> chain = approvals.ensureChain(ORDER,
            List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));

        assertThat(chain).extracting(ApprovalStep::stepNo).containsExactly(1, 2);
        assertThat(chain).extracting(ApprovalStep::role)
            .containsExactly(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER);
        assertThat(chain.get(1).status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void a_gap_left_by_dropping_the_first_step_does_not_break_the_next_append() {
        approvals.ensureChain(ORDER, List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));
        assertThat(approvals.dropPending(ORDER, ApprovalRole.OPS_LEAD)).isEqualTo(1);

        List<ApprovalStep> chain = approvals.ensureChain(ORDER,
            List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));

        assertThat(chain).extracting(ApprovalStep::stepNo)
            .as("the append continues after the highest stored step, leaving the hole")
            .containsExactly(2, 3);
        assertThat(chain.stream().map(ApprovalStep::role).distinct().count())
            .as("no role is duplicated")
            .isEqualTo(2);
    }

    // ------------------------------------------------------------------ decide

    @Test
    void only_the_lowest_pending_step_can_be_decided() {
        approvals.ensureChain(ORDER, List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));

        assertBizError(() -> approvals.decide(ORDER, 2, true, ACTOR, "同意"),
            BizError.STATE_NOT_ALLOWED, "nextPending");
        assertThat(approvals.steps(ORDER).get(1).status()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void a_decided_step_cannot_be_decided_again() {
        approvals.ensureChain(ORDER, List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));
        approvals.decide(ORDER, 1, true, ACTOR, null);

        assertBizError(() -> approvals.decide(ORDER, 1, true, ACTOR, null),
            BizError.STATE_NOT_ALLOWED, "status");
    }

    @Test
    void a_missing_step_is_a_parameter_error() {
        approvals.ensureChain(ORDER, List.of(ApprovalRole.OPS_LEAD));

        assertBizError(() -> approvals.decide(ORDER, 9, true, ACTOR, null),
            BizError.PARAM_INVALID, "stepNo");
    }

    @Test
    void the_actor_is_required() {
        approvals.ensureChain(ORDER, List.of(ApprovalRole.OPS_LEAD));

        assertBizError(() -> approvals.decide(ORDER, 1, true, "  ", null),
            BizError.PARAM_INVALID, "actor");
    }

    @Test
    void an_actor_longer_than_the_column_is_rejected_before_writing() {
        approvals.ensureChain(ORDER, List.of(ApprovalRole.OPS_LEAD));

        assertBizError(() -> approvals.decide(ORDER, 1, true, "u".repeat(33), null),
            BizError.PARAM_INVALID, "actor");
        assertThat(approvals.steps(ORDER).get(0).status()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void a_racing_append_is_absorbed_and_leaves_one_chain() {
        ApprovalMapper mapper = mock(ApprovalMapper.class);
        List<ApprovalPO> rows = new ArrayList<>();
        when(mapper.selectByOrder(ORDER)).thenAnswer(invocation -> List.copyOf(rows));
        when(mapper.insert(any(ApprovalPO.class))).thenAnswer(invocation -> {
            // The other transaction's insert wins, then our insert hits uk_approval_step.
            rows.add(invocation.getArgument(0));
            throw new DuplicateKeyException("simulated concurrent append");
        });

        List<ApprovalStep> chain = new ApprovalService(mapper)
            .ensureChain(ORDER, List.of(ApprovalRole.OPS_LEAD));

        assertThat(chain).hasSize(1);
        assertThat(chain.get(0).role()).isEqualTo(ApprovalRole.OPS_LEAD);
        assertThat(chain.get(0).status()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void approving_every_step_completes_the_chain() {
        approvals.ensureChain(ORDER, List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));

        DecideResult first = approvals.decide(ORDER, 1, true, ACTOR, null);
        assertThat(first.approved()).isTrue();
        assertThat(first.complete()).isFalse();
        assertThat(first.rejected()).isFalse();

        DecideResult second = approvals.decide(ORDER, 2, true, "t21-manager", "同意");
        assertThat(second.complete()).isTrue();
        assertThat(second.rejected()).isFalse();
        assertThat(second.steps()).extracting(ApprovalStepView::status)
            .containsExactly("APPROVED", "APPROVED");
        assertThat(second.steps().get(1).decidedBy()).isEqualTo("t21-manager");
        assertThat(second.steps().get(1).decidedAt()).isNotNull();
    }

    @Test
    void rejecting_a_step_cancels_the_later_steps() {
        approvals.ensureChain(ORDER, List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));

        DecideResult result = approvals.decide(ORDER, 1, false, ACTOR, "窗口外风险过大");

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
            "SELECT step_no, status FROM approval WHERE order_id = ? ORDER BY step_no", ORDER);
        assertThat(rows).extracting(row -> row.get("status"))
            .containsExactly("REJECTED", "CANCELED");
    }

    @Test
    void a_rejection_requires_a_reason() {
        approvals.ensureChain(ORDER, List.of(ApprovalRole.OPS_LEAD));

        assertBizError(() -> approvals.decide(ORDER, 1, false, ACTOR, "   "),
            BizError.PARAM_INVALID, "comment");
        assertThat(approvals.steps(ORDER).get(0).status()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void a_resubmission_appends_a_new_generation_after_a_rejection() {
        approvals.ensureChain(ORDER, List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));
        approvals.decide(ORDER, 1, false, ACTOR, "驳回");

        List<ApprovalStep> chain = approvals.ensureChain(ORDER,
            List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));

        assertThat(chain).extracting(ApprovalStep::stepNo).containsExactly(1, 2, 3, 4);
        assertThat(chain).extracting(ApprovalStep::status)
            .containsExactly(ApprovalStatus.REJECTED, ApprovalStatus.CANCELED,
                ApprovalStatus.PENDING, ApprovalStatus.PENDING);

        DecideResult first = approvals.decide(ORDER, 3, true, ACTOR, "重提后同意");
        assertThat(first.complete()).isFalse();
        assertThat(approvals.decide(ORDER, 4, true, "t21-manager", null).complete()).isTrue();
    }

    @Test
    void step_views_flatten_the_domain_enums() {
        approvals.ensureChain(ORDER, List.of(ApprovalRole.OPS_LEAD));

        List<ApprovalStepView> views = approvals.stepViews(ORDER);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).role()).isEqualTo("OPS_LEAD");
        assertThat(views.get(0).status()).isEqualTo("PENDING");
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
            "SELECT created_at FROM approval WHERE order_id = ? AND step_no = ?",
            Timestamp.class, ORDER, stepNo);
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