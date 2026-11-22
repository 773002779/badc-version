package com.sf.release.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.approval.ApprovalChain;
import com.sf.release.domain.approval.ApprovalRole;
import com.sf.release.domain.approval.ApprovalStep;
import com.sf.release.infra.repo.AdmissionSnapshotMapper;
import com.sf.release.infra.repo.ApprovalMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.po.AdmissionSnapshotPO;
import com.sf.release.infra.repo.po.ApprovalPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The 分级审批 use cases of one admission snapshot (tech design 4.4): keep the chain in sync with
 * the current admission requirements, read it, and decide one step.
 *
 * <p><strong>The chain belongs to a snapshot, not to an order.</strong> V22 adds
 * {@code approval.snapshot_id} and makes {@code (snapshot_id, step_no)} unique: one admission
 * snapshot owns one chain. A resubmission produces a new snapshot, whose chain starts at step 1
 * again while the rejected generation keeps its rows — that is what "the old approval stays as
 * history, the new one starts clean" (design 4.4) means in the data. Every method therefore takes
 * the snapshot id; there is no order-wide chain read.
 *
 * <p><strong>Chain construction is append-only and idempotent.</strong> {@link #ensureChain} adds a
 * step only for a role that has no pending or approved step; decided steps are never touched and
 * never deleted. A frozen order gets {@code DEPT_MANAGER} appended when it is missing, an unfrozen
 * one drops only the <em>unsigned</em> {@code DEPT_MANAGER} again — the signed ones stay as
 * evidence (design 4.2).
 *
 * <p><strong>Two mutating calls of one order never interleave.</strong> {@code ensureChain} and
 * {@code dropPending} lock the {@code release_order} row as their <em>first</em> statement, so a
 * freeze hook and an unfreeze recompute (or two submits) serialize on the order. The lock is taken
 * broadly on purpose: the freeze path appends a step while the release path drops one, and both
 * must see the same chain. See the method javadoc for why a plain re-read cannot do that under
 * MySQL's {@code REPEATABLE READ}.
 *
 * <p><strong>Sequential signatures.</strong> {@link #decide} refuses to decide anything but the
 * lowest pending step, requires a reason to reject, and lets {@link ApprovalChain} flip the later
 * pending steps to {@code CANCELED} when a step is rejected. Self-approval and the signature role
 * are <em>not</em> checked here: the flow that owns the 发布单 has the request identity and the
 * snapshot's submitter, so those checks live in {@link ReleaseApprovalService}.
 *
 * <p>The returned {@link DecideResult} tells the caller what the chain now is — {@code complete}
 * maps to the {@code APPROVE_ALL} item event, {@code rejected} to {@code REJECT} — without making
 * this service depend on the item state machine.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private static final int COMMENT_MAX_LENGTH = 512;

    /** {@code approval.decided_by} is a user id column of 32 characters. */
    private static final int ACTOR_MAX_LENGTH = 32;

    /** Bounded retries when a concurrent writer keeps winning the step number (never in practice). */
    private static final int MAX_APPEND_ATTEMPTS = 3;

    private final ReleaseOrderMapper releaseOrderMapper;
    private final ApprovalMapper approvalMapper;
    private final AdmissionSnapshotMapper snapshotMapper;

    public ApprovalService(ReleaseOrderMapper releaseOrderMapper, ApprovalMapper approvalMapper,
                           AdmissionSnapshotMapper snapshotMapper) {
        this.releaseOrderMapper = releaseOrderMapper;
        this.approvalMapper = approvalMapper;
        this.snapshotMapper = snapshotMapper;
    }

    /**
     * The outcome of one {@link #decide}: the decided step plus whether the chain now needs no
     * further signature ({@code complete}) or ended in a rejection ({@code rejected}).
     */
    public record DecideResult(long snapshotId, int stepNo, String role, boolean approved,
                               boolean complete, boolean rejected, List<ApprovalStepView> steps) {
    }

    /**
     * Makes sure the chain of {@code snapshotId} contains an active step for every required role,
     * appending only what is missing. Appends always go to the <em>tail</em> with the next free step
     * numbers; callers pass the full required set at submission time, and a later, larger
     * requirement (a freeze adding {@code DEPT_MANAGER}) is appended in the order it is passed.
     *
     * <p><strong>Concurrency.</strong> The first statement is a locking read of the order row
     * ({@code ReleaseOrderMapper.selectByIdForUpdate}), which serializes every chain mutation of
     * that order — two concurrent submits, and a freeze hook racing an unfreeze recompute. The
     * second caller waits at the lock and then reads the chain as a <em>fresh</em> snapshot, so it
     * sees what the first one committed. A plain read alone would not help: MySQL's default
     * {@code REPEATABLE READ} keeps the transaction's first snapshot. If the unique key still
     * rejects an append (a writer bypassing that lock), the method re-reads the chain with a locking
     * read and continues with the remaining roles instead of returning early.
     *
     * <p>Idempotent: calling it twice with the same roles changes nothing the second time, and
     * calling it with fewer roles never removes a step (that is {@link #dropPending}'s job).
     *
     * @return the chain after the append, in signature order
     * @throws IllegalStateException the order row does not exist (the chain always belongs to an
     *                               existing order; fail loudly instead of locking nothing)
     */
    @Transactional
    public List<ApprovalStep> ensureChain(long orderId, long snapshotId, List<ApprovalRole> roles) {
        List<ApprovalRole> required = new ArrayList<>();
        for (ApprovalRole role : roles == null ? List.<ApprovalRole>of() : roles) {
            required.add(requireRole(role));
        }
        requireSnapshotId(snapshotId);
        // FIRST statement of the transaction: serialize concurrent chain builders of this order.
        // A locking read is a current read, so the plain chain read below starts its snapshot after
        // the competing transaction committed (a plain read on its own would not).
        if (releaseOrderMapper.selectByIdForUpdate(orderId) == null) {
            throw new IllegalStateException(
                "approval chain requires an existing release_order: " + orderId);
        }
        ApprovalChain chain = chainOf(approvalMapper.selectBySnapshot(snapshotId));
        Instant now = Instant.now();
        int appended = 0;
        for (ApprovalRole role : required) {
            int attempts = 0;
            while (!chain.hasActive(role) && attempts < MAX_APPEND_ATTEMPTS) {
                attempts++;
                ApprovalChain extended = chain.appendIfMissing(role);
                ApprovalStep step = extended.steps().get(extended.steps().size() - 1);
                try {
                    approvalMapper.insert(ApprovalPO.of(orderId, snapshotId, step, now));
                    chain = extended;
                    appended++;
                } catch (DuplicateKeyException ex) {
                    // uk_approval_snapshot_step kept the chain single. Refresh with a locking read
                    // (a plain re-read would still see the pre-collision snapshot) and try the role
                    // again, then continue with the remaining roles rather than returning early.
                    chain = chainOf(approvalMapper.selectBySnapshotForUpdate(snapshotId));
                    log.info("approval chain append raced: orderId={}, snapshotId={}, role={},"
                        + " attempt={}", orderId, snapshotId, role, attempts);
                }
            }
            if (!chain.hasActive(role)) {
                throw new BizException(BizError.STATE_NOT_ALLOWED,
                    "审批链并发更新失败，请重试",
                    Map.of("orderId", orderId, "snapshotId", snapshotId, "role", role.name()));
            }
        }
        if (appended > 0) {
            log.info("approval chain extended: orderId={}, snapshotId={}, appended={}, total={}",
                orderId, snapshotId, appended, chain.steps().size());
        }
        return chain.steps();
    }

    /**
     * Appends one role at the end of the snapshot's chain when it has no active step. This is the
     * "封板生效 → 补一个 DEPT_MANAGER" hook of design 4.2.
     */
    @Transactional
    public List<ApprovalStep> appendIfMissing(long orderId, long snapshotId, ApprovalRole role) {
        return ensureChain(orderId, snapshotId, List.of(requireRole(role)));
    }

    /**
     * Removes the still-pending steps of one role from the snapshot's chain (design 4.2: "解除封板
     * 后移除尚未签的 DEPT_MANAGER"). Decided steps are kept, so an approval that already happened
     * stays visible.
     *
     * <p>The order row is locked first for the same reason as {@link #ensureChain}: the unfreeze
     * recompute and a freeze hook must not interleave on one order's chain.
     *
     * @return how many pending steps were removed
     */
    @Transactional
    public int dropPending(long orderId, long snapshotId, ApprovalRole role) {
        ApprovalRole required = requireRole(role);
        requireSnapshotId(snapshotId);
        // FIRST statement of the transaction: serialize with ensureChain (see its javadoc).
        if (releaseOrderMapper.selectByIdForUpdate(orderId) == null) {
            throw new IllegalStateException(
                "approval chain requires an existing release_order: " + orderId);
        }
        int removed = approvalMapper.deletePendingBySnapshotRole(snapshotId, required.name());
        if (removed > 0) {
            log.info("approval pending steps dropped: orderId={}, snapshotId={}, role={},"
                + " removed={}", orderId, snapshotId, required, removed);
        }
        return removed;
    }

    /** The whole chain of one snapshot, in signature order (empty when nothing was required). */
    public List<ApprovalStep> steps(long snapshotId) {
        requireSnapshotId(snapshotId);
        return chainOf(approvalMapper.selectBySnapshot(snapshotId)).steps();
    }

    /** The chain flattened for the web layer, so {@code web} never sees a domain enum. */
    public List<ApprovalStepView> stepViews(long snapshotId) {
        return viewsOf(chainOf(approvalMapper.selectBySnapshot(requireSnapshotId(snapshotId))));
    }

    /**
     * Decides one approval step of one snapshot's chain.
     *
     * <p><strong>Two signatures of one order never interleave.</strong> The order row is locked
     * (current read) before the chain is read, and the chain itself is read with a locking read, so
     * a decision always starts from the committed chain: the second concurrent signer waits at the
     * order lock and then sees the first one's decision (a plain first read would keep the
     * transaction's {@code REPEATABLE READ} snapshot and could decide the same step twice).
     *
     * <p>The signature <em>role</em> and the prohibition of self-approval (40008) are enforced by
     * the caller ({@link ReleaseApprovalService}), which has the request identity and the snapshot's
     * submitter; this method owns the transition mechanics and the sequential-signature rule.
     *
     * @param approved {@code true} to approve, {@code false} to reject (a reason is then required)
     * @param actor    the user id that signs
     * @throws BizException 40000 when the snapshot does not exist, the step does not exist, the actor
     *                      is blank or a rejection carries no reason; 40001 when the step is not the
     *                      lowest pending one or was already decided
     */
    @Transactional
    public DecideResult decide(long snapshotId, int stepNo, boolean approved, String actor,
                               String comment) {
        requireSnapshotId(snapshotId);
        String signer = trimToNull(actor);
        if (signer == null) {
            throw new BizException(BizError.PARAM_INVALID, "审批人不能为空",
                Map.of("actor", "不能为空"));
        }
        if (signer.length() > ACTOR_MAX_LENGTH) {
            throw new BizException(BizError.PARAM_INVALID,
                "审批人长度不能超过 " + ACTOR_MAX_LENGTH,
                Map.of("actor", "长度超限：" + signer.length()));
        }
        // snapshot → orderId → lock: the order row is locked before the chain is read, so every
        // signature of this order is serialized here. The snapshot row is immutable, so reading it
        // first cannot go stale in a way that matters.
        AdmissionSnapshotPO snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null || snapshot.getOrderId() == null || snapshot.getOrderId() <= 0) {
            throw new BizException(BizError.PARAM_INVALID, "准入快照不存在",
                Map.of("snapshotId", snapshotId));
        }
        long orderId = snapshot.getOrderId();
        if (releaseOrderMapper.selectByIdForUpdate(orderId) == null) {
            throw new IllegalStateException(
                "approval decision requires an existing release_order: " + orderId);
        }
        // A locking read is a current read: it sees the committed chain even after the plain read
        // above established the transaction's REPEATABLE READ snapshot.
        List<ApprovalPO> stored = approvalMapper.selectBySnapshotForUpdate(snapshotId);
        ApprovalChain chain = chainOf(stored);
        ApprovalStep target = chain.step(stepNo).orElseThrow(() -> new BizException(
            BizError.PARAM_INVALID, "审批步骤不存在", Map.of("stepNo", stepNo)));
        if (!target.pending()) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "该审批步骤已处理：" + target.status().name(),
                Map.of("stepNo", stepNo, "status", target.status().name()));
        }
        ApprovalStep lowest = chain.nextPending().orElse(null);
        if (lowest == null || lowest.stepNo() != stepNo) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "请先处理第 " + (lowest == null ? 1 : lowest.stepNo()) + " 步审批",
                Map.of("stepNo", stepNo, "nextPending", lowest == null ? 0 : lowest.stepNo()));
        }
        String note = trimToNull(comment);
        if (!approved && note == null) {
            throw new BizException(BizError.PARAM_INVALID, "驳回必须填写意见",
                Map.of("comment", "不能为空"));
        }
        if (note != null && note.length() > COMMENT_MAX_LENGTH) {
            throw new BizException(BizError.PARAM_INVALID,
                "审批意见长度不能超过 " + COMMENT_MAX_LENGTH, Map.of("comment", "长度超限"));
        }
        Instant now = Instant.now();
        ApprovalChain updated = approved
            ? chain.approve(stepNo, signer, now, note)
            : chain.reject(stepNo, signer, now, note);
        persistDecisions(chain, updated, stored);
        log.info("approval step decided: snapshotId={}, stepNo={}, role={}, approved={}, by={},"
                + " complete={}, rejected={}",
            snapshotId, stepNo, target.role(), approved, signer, updated.complete(),
            updated.rejected());
        return new DecideResult(snapshotId, stepNo, target.role().name(), approved,
            updated.complete(), updated.rejected(), viewsOf(updated));
    }

    // ------------------------------------------------------------------ helpers

    private static ApprovalChain chainOf(List<ApprovalPO> stored) {
        return ApprovalChain.of(stored.stream().map(ApprovalPO::toDomain).toList());
    }

    private static List<ApprovalStepView> viewsOf(ApprovalChain chain) {
        return chain.steps().stream()
            .map(step -> new ApprovalStepView(step.stepNo(), step.role().name(),
                step.role().label(), step.status().name(), step.status().label(),
                step.decidedBy(), step.decidedAt(), step.comment()))
            .toList();
    }

    /**
     * Writes only the steps whose status or decision fields changed, with an explicit column
     * update. It deliberately does not send a whole {@code ApprovalPO} through
     * {@code updateById}: that would rely on the global null-field strategy to leave
     * {@code created_at} untouched, and a strategy change elsewhere would silently rewrite the
     * history timestamps. Only {@code status}, {@code decided_by}, {@code decided_at} and
     * {@code comment} are written.
     */
    private void persistDecisions(ApprovalChain before, ApprovalChain after,
                                  List<ApprovalPO> stored) {
        Map<Integer, Long> ids = new HashMap<>();
        for (ApprovalPO po : stored) {
            ids.put(po.getStepNo(), po.getId());
        }
        for (ApprovalStep step : after.steps()) {
            ApprovalStep previous = before.step(step.stepNo()).orElse(null);
            if (step.equals(previous)) {
                continue;
            }
            Long id = ids.get(step.stepNo());
            if (id == null) {
                throw new IllegalStateException("cannot persist a decision for a new step "
                    + step.stepNo());
            }
            approvalMapper.update(null, Wrappers.<ApprovalPO>lambdaUpdate()
                .eq(ApprovalPO::getId, id)
                .set(ApprovalPO::getStatus, step.status().name())
                .set(ApprovalPO::getDecidedBy, step.decidedBy())
                .set(ApprovalPO::getDecidedAt, step.decidedAt())
                .set(ApprovalPO::getComment, step.comment()));
        }
    }

    private static ApprovalRole requireRole(ApprovalRole role) {
        if (role == null) {
            throw new BizException(BizError.PARAM_INVALID, "审批角色不能为空",
                Map.of("role", "不能为空"));
        }
        return role;
    }

    /**
     * A chain is always read, appended and dropped per admission snapshot; snapshot id 0 is the
     * legacy backfill value of V22 and never a real chain, so a missing id is an internal error
     * instead of a query that silently reads the wrong rows.
     */
    private static long requireSnapshotId(long snapshotId) {
        if (snapshotId <= 0) {
            throw new IllegalArgumentException(
                "an approval chain requires an admission snapshot id: " + snapshotId);
        }
        return snapshotId;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}