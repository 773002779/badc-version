package com.sf.release.approval;

import com.sf.release.domain.approval.ApprovalChain;
import com.sf.release.domain.approval.ApprovalRole;
import com.sf.release.domain.approval.ApprovalStatus;
import com.sf.release.domain.approval.ApprovalStep;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pure approval chain (tech design 4.4): sequential signatures, append-only growth, and the
 * rejection that cancels the steps nobody will ever sign. No Spring, no database — the service
 * test covers persistence, this one pins the rules.
 */
class ApprovalChainTest {

    private static final Instant NOW = Instant.parse("2026-11-22T14:00:00Z");

    @Test
    void an_empty_chain_is_complete_and_not_rejected() {
        ApprovalChain chain = ApprovalChain.empty();

        assertThat(chain.isEmpty()).isTrue();
        assertThat(chain.complete()).isTrue();
        assertThat(chain.rejected()).isFalse();
        assertThat(chain.nextPending()).isEmpty();
    }

    @Test
    void append_if_missing_keeps_roles_unique_and_numbers_sequential() {
        ApprovalChain chain = ApprovalChain.empty()
            .appendIfMissing(ApprovalRole.OPS_LEAD)
            .appendIfMissing(ApprovalRole.OPS_LEAD)
            .appendIfMissing(ApprovalRole.DEPT_MANAGER);

        assertThat(chain.steps()).extracting(ApprovalStep::stepNo).containsExactly(1, 2);
        assertThat(chain.steps()).extracting(ApprovalStep::role)
            .containsExactly(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER);
        assertThat(chain.nextPending()).get()
            .extracting(ApprovalStep::stepNo).isEqualTo(1);
    }

    @Test
    void approving_the_lowest_step_moves_the_pending_pointer() {
        ApprovalChain chain = twoSteps();

        ApprovalChain afterFirst = chain.approve(1, "lead", NOW, "同意");

        assertThat(afterFirst.steps().get(0).status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(afterFirst.steps().get(0).decidedBy()).isEqualTo("lead");
        assertThat(afterFirst.steps().get(0).decidedAt()).isEqualTo(NOW);
        assertThat(afterFirst.nextPending()).get()
            .extracting(ApprovalStep::stepNo).isEqualTo(2);
        assertThat(afterFirst.complete()).isFalse();

        ApprovalChain afterSecond = afterFirst.approve(2, "manager", NOW, null);

        assertThat(afterSecond.complete()).isTrue();
        assertThat(afterSecond.rejected()).isFalse();
        assertThat(afterSecond.nextPending()).isEmpty();
    }

    @Test
    void rejecting_a_step_cancels_every_later_pending_step() {
        ApprovalChain chain = ApprovalChain.empty()
            .appendIfMissing(ApprovalRole.OPS_LEAD)
            .appendIfMissing(ApprovalRole.DEPT_MANAGER);

        ApprovalChain rejected = chain.reject(1, "lead", NOW, "驳回");

        assertThat(rejected.rejected()).isTrue();
        assertThat(rejected.complete()).isFalse();
        assertThat(rejected.steps()).extracting(ApprovalStep::status)
            .containsExactly(ApprovalStatus.REJECTED, ApprovalStatus.CANCELED);
        assertThat(rejected.nextPending()).isEmpty();
    }

    @Test
    void a_rejection_after_an_approval_keeps_the_approval() {
        ApprovalChain chain = twoSteps().approve(1, "lead", NOW, null);

        ApprovalChain rejected = chain.reject(2, "manager", NOW, "风险未闭环");

        assertThat(rejected.steps()).extracting(ApprovalStep::status)
            .containsExactly(ApprovalStatus.APPROVED, ApprovalStatus.REJECTED);
        assertThat(rejected.rejected()).isTrue();
        assertThat(rejected.complete()).isFalse();
    }

    @Test
    void a_new_generation_is_complete_only_when_its_last_step_is_approved() {
        ApprovalChain rejected = twoSteps().reject(1, "lead", NOW, "驳回");
        ApprovalChain nextGeneration = rejected
            .appendIfMissing(ApprovalRole.OPS_LEAD)
            .appendIfMissing(ApprovalRole.DEPT_MANAGER);

        assertThat(nextGeneration.steps()).extracting(ApprovalStep::stepNo)
            .containsExactly(1, 2, 3, 4);
        assertThat(nextGeneration.complete()).isFalse();
        assertThat(nextGeneration.nextPending()).get()
            .extracting(ApprovalStep::stepNo).isEqualTo(3);

        ApprovalChain done = nextGeneration
            .approve(3, "lead", NOW, null)
            .approve(4, "manager", NOW, null);

        assertThat(done.complete()).isTrue();
        assertThat(done.rejected())
            .as("the historic rejection does not keep the chain open")
            .isFalse();
    }

    @Test
    void deciding_a_step_that_is_not_pending_is_a_programming_error() {
        ApprovalChain chain = twoSteps().approve(1, "lead", NOW, null);

        assertThatThrownBy(() -> chain.approve(1, "lead", NOW, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chain.approve(9, "lead", NOW, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicate_step_numbers_are_rejected() {
        List<ApprovalStep> duplicated = List.of(
            ApprovalStep.pending(1, ApprovalRole.OPS_LEAD),
            ApprovalStep.pending(1, ApprovalRole.DEPT_MANAGER));

        assertThatThrownBy(() -> ApprovalChain.of(duplicated))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static ApprovalChain twoSteps() {
        return ApprovalChain.empty()
            .appendIfMissing(ApprovalRole.OPS_LEAD)
            .appendIfMissing(ApprovalRole.DEPT_MANAGER);
    }
}