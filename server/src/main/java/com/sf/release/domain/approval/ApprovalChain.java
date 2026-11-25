package com.sf.release.domain.approval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The 分级审批 chain of one 发布单 (tech design 4.4): an ordered list of role steps that must be
 * signed one after another.
 *
 * <p><strong>Sequential 会签.</strong> Only the lowest {@link ApprovalStatus#PENDING} step may be
 * decided. Approving it keeps the following step pending; the last approval makes the chain
 * {@link #complete() complete}. Rejecting any step terminates the chain: that step becomes
 * {@link ApprovalStatus#REJECTED} and every later pending step becomes
 * {@link ApprovalStatus#CANCELED} — nobody keeps waiting for a signature that cannot matter. No
 * step is ever deleted: the chain is the evidence of who was expected to sign.
 *
 * <p><strong>Append-only.</strong> {@link #appendIfMissing(ApprovalRole)} adds a step only when the
 * role has no <em>active</em> step (pending or approved). A rejected/canceled step is history and
 * does not prevent a new step from being appended: a resubmission after a rejection starts a new
 * generation of steps at the end of the same chain, and the earlier steps stay visible. The chain
 * consequently judges its <em>latest</em> generation — {@link #complete()} and {@link #rejected()}
 * look at the pending steps and at the last decided step, not at every historic row.
 *
 * <p><strong>Who may sign</strong> is not this class's business: the application service decides
 * that the actor holds the step's role and is not the submitter (self-approval is forbidden). The
 * chain refuses only a decision on a step that is missing or not pending.
 *
 * <p>Instances are immutable; every transition returns a new chain.
 */
public final class ApprovalChain {

    private final List<ApprovalStep> steps;

    private ApprovalChain(List<ApprovalStep> steps) {
        List<ApprovalStep> sorted = new ArrayList<>(steps);
        sorted.sort(Comparator.comparingInt(ApprovalStep::stepNo));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).stepNo() == sorted.get(i - 1).stepNo()) {
                throw new IllegalArgumentException("duplicate stepNo: " + sorted.get(i).stepNo());
            }
        }
        this.steps = List.copyOf(sorted);
    }

    public static ApprovalChain of(List<ApprovalStep> steps) {
        return new ApprovalChain(steps == null ? List.of() : steps);
    }

    /** The 免审 chain: no step at all, already complete. */
    public static ApprovalChain empty() {
        return new ApprovalChain(List.of());
    }

    /** Every step in ascending order. */
    public List<ApprovalStep> steps() {
        return steps;
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    public Optional<ApprovalStep> step(int stepNo) {
        return steps.stream().filter(step -> step.stepNo() == stepNo).findFirst();
    }

    /** The lowest pending step, which is the only one that may be decided. */
    public Optional<ApprovalStep> nextPending() {
        return steps.stream().filter(ApprovalStep::pending).findFirst();
    }

    public boolean pending() {
        return nextPending().isPresent();
    }

    /** The highest-numbered step that was actually decided (approved or rejected). */
    public Optional<ApprovalStep> lastDecided() {
        return steps.stream()
            .filter(step -> step.status() == ApprovalStatus.APPROVED
                || step.status() == ApprovalStatus.REJECTED)
            .max(Comparator.comparingInt(ApprovalStep::stepNo));
    }

    /**
     * True when the latest generation needs no further signature: an empty chain (免审) or a chain
     * whose last decided step was approved and that has nothing pending.
     */
    public boolean complete() {
        if (steps.isEmpty()) {
            return true;
        }
        return !pending() && lastDecided()
            .filter(step -> step.status() == ApprovalStatus.APPROVED)
            .isPresent();
    }

    /** True when the latest generation ended in a rejection and nothing is pending any more. */
    public boolean rejected() {
        return !pending() && lastDecided()
            .filter(step -> step.status() == ApprovalStatus.REJECTED)
            .isPresent();
    }

    /**
     * Appends a pending step for the role at the end of the chain unless the role already has an
     * active (pending or approved) step. The step number continues the sequence, so a resubmitted
     * generation never reuses a number that history still holds.
     */
    public ApprovalChain appendIfMissing(ApprovalRole role) {
        Objects.requireNonNull(role, "role must not be null");
        if (hasActive(role)) {
            return this;
        }
        return append(role);
    }

    /** Appends the role unconditionally; used by {@link #appendIfMissing(ApprovalRole)}. */
    private ApprovalChain append(ApprovalRole role) {
        Objects.requireNonNull(role, "role must not be null");
        List<ApprovalStep> next = new ArrayList<>(steps);
        next.add(ApprovalStep.pending(nextStepNo(), role));
        return new ApprovalChain(next);
    }

    /** True when the role has a pending or approved step (i.e. it still asks somebody to sign). */
    public boolean hasActive(ApprovalRole role) {
        return steps.stream().anyMatch(step -> step.role() == role
            && (step.status() == ApprovalStatus.PENDING
                || step.status() == ApprovalStatus.APPROVED));
    }

    /** The step number a new appended step would get. */
    public int nextStepNo() {
        return steps.isEmpty() ? 1 : steps.get(steps.size() - 1).stepNo() + 1;
    }

    /**
     * Approves one step.
     *
     * @throws IllegalArgumentException the step does not exist or is not pending
     */
    public ApprovalChain approve(int stepNo, String actor, Instant at, String comment) {
        ApprovalStep target = requirePending(stepNo);
        List<ApprovalStep> next = new ArrayList<>(steps);
        next.set(next.indexOf(target), target.decided(ApprovalStatus.APPROVED, actor, at, comment));
        return new ApprovalChain(next);
    }

    /**
     * Rejects one step and cancels every later pending step.
     *
     * @throws IllegalArgumentException the step does not exist or is not pending
     */
    public ApprovalChain reject(int stepNo, String actor, Instant at, String comment) {
        ApprovalStep target = requirePending(stepNo);
        List<ApprovalStep> next = new ArrayList<>(steps.size());
        for (ApprovalStep step : steps) {
            if (step.stepNo() == stepNo) {
                next.add(target.decided(ApprovalStatus.REJECTED, actor, at, comment));
            } else if (step.stepNo() > stepNo && step.pending()) {
                next.add(step.decided(ApprovalStatus.CANCELED, null, null, null));
            } else {
                next.add(step);
            }
        }
        return new ApprovalChain(next);
    }

    private ApprovalStep requirePending(int stepNo) {
        ApprovalStep target = step(stepNo).orElseThrow(
            () -> new IllegalArgumentException("no approval step " + stepNo));
        if (!target.pending()) {
            throw new IllegalArgumentException(
                "approval step " + stepNo + " is not pending: " + target.status());
        }
        return target;
    }

    /**
     * Cancels every still-{@link ApprovalStatus#PENDING} step of the chain, keeping the decided
     * history untouched (P5-B design §3: "审批链作废").
     *
     * <p>The chain had no "cancel all" operation before: {@link #reject} cancels only the steps
     * <em>after</em> the rejected one, which is the wrong shape for a stop/exit/close — nobody is
     * rejecting anything there, the release itself is gone, so every expected signature becomes
     * meaningless at once. The steps are cancelled instead of deleted on purpose: the chain is the
     * evidence of who was still expected to sign.
     *
     * <p>The cancellation records when and why so the page can explain it; {@code decidedBy} stays
     * {@code null}, because nobody signed. Idempotent: a chain with nothing pending is returned
     * unchanged.
     *
     * @param reason why the pending signatures are void (stored on every cancelled step)
     * @param at     the moment of the cancellation
     * @return the same chain instance when nothing was pending, otherwise the updated chain
     */
    public ApprovalChain cancelPending(String reason, Instant at) {
        List<ApprovalStep> next = new ArrayList<>(steps.size());
        boolean changed = false;
        for (ApprovalStep step : steps) {
            if (step.pending()) {
                next.add(step.decided(ApprovalStatus.CANCELED, null, at, reason));
                changed = true;
            } else {
                next.add(step);
            }
        }
        return changed ? new ApprovalChain(next) : this;
    }
}