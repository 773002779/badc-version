package com.sf.release.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.approval.ApprovalChain;
import com.sf.release.domain.approval.ApprovalRole;
import com.sf.release.domain.freeze.FreezeEvent;
import com.sf.release.domain.freeze.FreezeScope;
import com.sf.release.domain.freeze.FreezeStatus;
import com.sf.release.domain.task.ItemEvent;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.infra.repo.AdmissionSnapshotMapper;
import com.sf.release.infra.repo.FreezeMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.SubsystemMapper;
import com.sf.release.infra.repo.po.AdmissionSnapshotPO;
import com.sf.release.infra.repo.po.FreezeEventPO;
import com.sf.release.infra.repo.po.InFlightOrderRow;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The 生产封板 use cases (tech design 4.2): declare a freeze and handle the releases already in
 * flight, lift one, list the frozen windows, and close the expired ones.
 *
 * <p><strong>Declaring is the governance act.</strong> {@link #create} writes the event with status
 * {@code ACTIVE} immediately (so a scheduled freeze is already recorded and visible) and, in the
 * same transaction, raises the requirement of every release already in the production flow: the
 * chain of its latest admission snapshot gets a {@code DEPT_MANAGER} step, and a {@code READY} or
 * {@code SUBMITTED} 变更项 is pulled back into {@code PENDING_APPROVAL}. Adding the signature as
 * soon as the freeze is declared is fail-closed — it can only add an approval, never remove one —
 * and it is what makes the banner and the chain agree from the first second.
 *
 * <p><strong>Lifting recomputes, it does not guess.</strong> The chain of each affected snapshot is
 * recomputed from what the snapshot recorded: inside the window the release needs no signature
 * ({@code OPS_LEAD} and {@code DEPT_MANAGER} pending steps are dropped), outside it needs
 * {@code OPS_LEAD} (only the pending {@code DEPT_MANAGER} is dropped). Decided steps are never
 * deleted — the approval already given stays as evidence. When a <em>different</em> freeze still
 * covers the same system, the {@code DEPT_MANAGER} step stays. If the chain then needs nothing
 * more, {@code APPROVE_ALL} moves the 变更项 to {@code READY}.
 *
 * <p><strong>The release reason has no column.</strong> {@code freeze_event} records
 * {@code released_by}/{@code released_at} only (design 4.2), so the mandatory release reason is
 * validated and written to the audit log; it is not stored. Adding a column would be a schema
 * change the design does not ask for.
 *
 * <p><strong>Expiry.</strong> {@link #expireDue(Instant)} closes the {@code ACTIVE} freezes whose
 * {@code end_at} passed. It deliberately does not recompute chains: a pending {@code
 * DEPT_MANAGER} step left over from an expired freeze is fail-closed (somebody can still sign it),
 * while dropping it automatically would silently weaken a requirement nobody reviewed.
 */
@Service
public class FreezeService {

    private static final Logger log = LoggerFactory.getLogger(FreezeService.class);

    /** Upper bound of freezes the daily scan closes per round. */
    static final int EXPIRE_LIMIT = 200;

    /** Upper bound of rows the history list returns; the page never shows more. */
    static final int HISTORY_LIMIT = 200;

    private final FreezeMapper freezeMapper;
    private final SubsystemMapper subsystemMapper;
    private final ReleaseOrderMapper orderMapper;
    private final AdmissionSnapshotMapper snapshotMapper;
    private final ApprovalService approvalService;
    private final ItemTransitions transitions;

    public FreezeService(FreezeMapper freezeMapper,
                         SubsystemMapper subsystemMapper,
                         ReleaseOrderMapper orderMapper,
                         AdmissionSnapshotMapper snapshotMapper,
                         ApprovalService approvalService,
                         ItemTransitions transitions) {
        this.freezeMapper = freezeMapper;
        this.subsystemMapper = subsystemMapper;
        this.orderMapper = orderMapper;
        this.snapshotMapper = snapshotMapper;
        this.approvalService = approvalService;
        this.transitions = transitions;
    }

    /**
     * Declares a freeze and handles the releases already in flight.
     *
     * @param scope       {@code GLOBAL} or {@code SUBSYSTEM} (case-insensitive)
     * @param subsystemId required for {@code SUBSYSTEM}, ignored for {@code GLOBAL}
     * @param startAt     when the freeze starts; {@code null} means now, a past instant is allowed
     *                    (a freeze declared after the fact still covers the releases in flight)
     * @param initiator   the user who declared it
     * @throws BizException 40000 for a bad scope, a missing subsystem, a blank/oversized reason or
     *                      an end time that is not after the start (and after now)
     */
    @Transactional
    public FreezeView create(String scope, Long subsystemId, String reason, String impact,
                             Instant startAt, Instant endAt, String initiator) {
        FreezeScope parsedScope = parseScope(scope);
        Long target = requireSubsystem(parsedScope, subsystemId);
        String why = requireText(reason, "封板原因");
        String impactText = optionalText(impact, "影响评估");
        String who = requireUser(initiator);
        Instant start = startAt == null ? Instant.now() : startAt;
        Instant now = Instant.now();
        if (endAt == null) {
            throw new BizException(BizError.PARAM_INVALID, "封板结束时间不能为空",
                Map.of("endAt", "不能为空"));
        }
        if (!endAt.isAfter(start)) {
            throw new BizException(BizError.PARAM_INVALID, "封板结束时间必须晚于开始时间",
                Map.of("startAt", start.toString(), "endAt", endAt.toString()));
        }
        if (!endAt.isAfter(now)) {
            throw new BizException(BizError.PARAM_INVALID, "封板结束时间必须晚于当前时间",
                Map.of("endAt", endAt.toString()));
        }
        FreezeEvent event = new FreezeEvent(null, parsedScope, target, why, impactText, who, start,
            endAt, FreezeStatus.ACTIVE, null, null, now);
        FreezeEventPO po = FreezeEventPO.of(event);
        freezeMapper.insert(po);
        FreezeView view = toView(po.toDomain(), now);
        int pulled = applyInFlight(view.id(), parsedScope, target, now);
        log.info("freeze declared: id={}, scope={}, subsystemId={}, by={}, startAt={}, endAt={},"
                + " pulledOrders={}", view.id(), parsedScope, target, who, start, endAt, pulled);
        return view;
    }

    /**
     * Lifts an {@code ACTIVE} freeze and recomputes the chains of the releases it affected.
     *
     * @param actor  the user who lifts it
     * @param reason why it is lifted; required. {@code freeze_event} has no column for it, so it is
     *               validated and logged, not stored.
     * @throws BizException 40000 the freeze does not exist or the reason is missing; 40001 it is
     *                      already {@code RELEASED}/{@code EXPIRED} (or a concurrent call won)
     */
    @Transactional
    public FreezeView release(long id, String actor, String reason) {
        FreezeEventPO po = freezeMapper.selectById(id);
        if (po == null) {
            throw new BizException(BizError.PARAM_INVALID, "封板记录不存在", Map.of("id", id));
        }
        FreezeEvent freeze = po.toDomain();
        String who = requireUser(actor);
        String why = requireText(reason, "解除原因");
        if (freeze.status() != FreezeStatus.ACTIVE) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "该封板已解除或已到期：" + freeze.status().label(),
                Map.of("id", id, "status", freeze.status().name()));
        }
        Instant now = Instant.now();
        FreezeEvent released = freeze.released(who, now);
        int rows = freezeMapper.update(null, Wrappers.<FreezeEventPO>lambdaUpdate()
            .eq(FreezeEventPO::getId, id)
            .eq(FreezeEventPO::getStatus, FreezeStatus.ACTIVE.name())
            .set(FreezeEventPO::getStatus, FreezeStatus.RELEASED.name())
            .set(FreezeEventPO::getReleasedBy, who)
            .set(FreezeEventPO::getReleasedAt, now));
        if (rows != 1) {
            throw new BizException(BizError.STATE_NOT_ALLOWED, "封板状态已被并发修改，请刷新后重试",
                Map.of("id", id));
        }
        int recomputed = recomputeAffectedOrders(freeze, now);
        log.info("freeze released: id={}, by={}, reason={}, recomputedOrders={}", id, who, why,
            recomputed);
        return toView(released, now);
    }

    /**
     * The freezes in force at {@code at} that affect one system: a GLOBAL freeze always applies, a
     * SUBSYSTEM one only to its own. {@code subsystemId = 0} answers every freeze in force
     * (regardless of scope), which is what the platform-wide banner shows.
     *
     * @throws BizException 40000 a negative subsystem id
     */
    public List<FreezeView> active(long subsystemId, Instant at) {
        if (subsystemId < 0) {
            throw new BizException(BizError.PARAM_INVALID, "系统 ID 不能为负数",
                Map.of("subsystemId", subsystemId));
        }
        Instant when = at == null ? Instant.now() : at;
        List<FreezeEventPO> rows = subsystemId == 0
            ? freezeMapper.selectActive(when)
            : freezeMapper.selectActiveForSubsystem(subsystemId, when);
        return rows.stream().map(po -> toView(po.toDomain(), when)).toList();
    }

    /**
     * The 封板 list of {@code GET /api/v1/freezes}.
     *
     * @param active      {@code null}/true: only the freezes in force now; false: every recorded
     *                    freeze, newest first (history)
     * @param subsystemId 0/null: every scope; positive: GLOBAL plus that system
     * @throws BizException 40000 a negative subsystem id
     */
    public List<FreezeView> list(Boolean active, Long subsystemId) {
        long target = subsystemId == null ? 0L : subsystemId;
        if (target < 0) {
            throw new BizException(BizError.PARAM_INVALID, "系统 ID 不能为负数",
                Map.of("subsystemId", target));
        }
        if (active == null || active) {
            return active(target, Instant.now());
        }
        List<FreezeEventPO> rows = freezeMapper.selectList(historyQuery(target));
        Instant now = Instant.now();
        return rows.stream().map(po -> toView(po.toDomain(), now)).toList();
    }

    /**
     * Closes the {@code ACTIVE} freezes whose {@code end_at} passed. Called by the daily scan.
     *
     * @return the number of freezes that became {@code EXPIRED}
     */
    public int expireDue(Instant now) {
        Instant when = now == null ? Instant.now() : now;
        List<FreezeEventPO> due = freezeMapper.selectDueForExpiry(when, EXPIRE_LIMIT);
        int expired = 0;
        for (FreezeEventPO po : due) {
            int rows = freezeMapper.update(null, Wrappers.<FreezeEventPO>lambdaUpdate()
                .eq(FreezeEventPO::getId, po.getId())
                .eq(FreezeEventPO::getStatus, FreezeStatus.ACTIVE.name())
                .set(FreezeEventPO::getStatus, FreezeStatus.EXPIRED.name()));
            if (rows == 1) {
                expired++;
                log.info("freeze expired: id={}, endAt={}", po.getId(), po.getEndAt());
            }
        }
        return expired;
    }

    // ------------------------------------------------------------------ in-flight handling

    /**
     * Raises the requirement of every release already in the production flow (design 4.2):
     * "保证审批链里有一个 DEPT_MANAGER 步骤，并把 READY 拉回 PENDING_APPROVAL".
     *
     * @return the number of 变更项 pulled back into approval
     */
    private int applyInFlight(long freezeId, FreezeScope scope, Long subsystemId, Instant now) {
        long filter = scope == FreezeScope.GLOBAL ? 0L : subsystemId;
        int pulled = 0;
        for (InFlightOrderRow row : orderMapper.selectInFlight(filter)) {
            AdmissionSnapshotPO snapshot = snapshotMapper.selectLatestByOrder(row.getOrderId());
            if (snapshot == null) {
                // Defensive: a SUBMITTED/PENDING_APPROVAL/READY item always has a snapshot (the
                // submit use case writes one). Without it there is no chain to extend and no window
                // fact to recompute later, so the release is left as it is rather than pulled into
                // an approval it could never leave.
                log.warn("freeze in-flight handling skipped: orderId={} itemId={} has no admission"
                        + " snapshot (freezeId={})", row.getOrderId(), row.getItemId(), freezeId);
                continue;
            }
            approvalService.appendIfMissing(row.getOrderId(), snapshot.getId(),
                ApprovalRole.DEPT_MANAGER);
            ItemEvent event = freezeEventFor(row.getItemStatus());
            if (event == null) {
                continue;
            }
            ItemTransitions.Outcome outcome = transitions.apply(row.getItemId(), event);
            if (outcome == ItemTransitions.Outcome.APPLIED) {
                syncOrderStatus(row, ItemStatus.PENDING_APPROVAL.name());
                pulled++;
            } else {
                log.info("freeze could not pull item {} back into approval: {}",
                    row.getItemId(), outcome);
            }
        }
        return pulled;
    }

    /** {@code READY} is pulled back by {@code FREEZE_HIT}, {@code SUBMITTED} by {@code NEED_APPROVAL}. */
    private static ItemEvent freezeEventFor(String itemStatus) {
        if (ItemStatus.READY.name().equals(itemStatus)) {
            return ItemEvent.FREEZE_HIT;
        }
        if (ItemStatus.SUBMITTED.name().equals(itemStatus)) {
            return ItemEvent.NEED_APPROVAL;
        }
        return null;
    }

    /**
     * Recomputes the chain of every release affected by the lifted freeze (design 4.2, see the class
     * javadoc). Only {@code PENDING_APPROVAL} items are recomputed: a {@code READY} one no longer
     * needs a signature, and a {@code SUBMITTED} one has no snapshot yet.
     *
     * @return the number of 变更项 moved to {@code READY} by the recompute
     */
    private int recomputeAffectedOrders(FreezeEvent lifted, Instant now) {
        long filter = lifted.scope() == FreezeScope.GLOBAL ? 0L : lifted.subsystemId();
        int ready = 0;
        for (InFlightOrderRow row : orderMapper.selectInFlight(filter)) {
            if (!ItemStatus.PENDING_APPROVAL.name().equals(row.getItemStatus())) {
                continue;
            }
            AdmissionSnapshotPO snapshot = snapshotMapper.selectLatestByOrder(row.getOrderId());
            if (snapshot == null) {
                log.warn("unfreeze recompute skipped: orderId={} itemId={} has no admission snapshot",
                    row.getOrderId(), row.getItemId());
                continue;
            }
            boolean inWindow = Boolean.TRUE.equals(snapshot.getInWindow());
            // Another freeze of the same system may still be in force: then the second signature
            // stays required and only the roles the snapshot no longer needs are dropped.
            long subsystemId = row.getSubsystemId() == null ? 0L : row.getSubsystemId();
            boolean stillFrozen =
                !freezeMapper.selectActiveForSubsystem(subsystemId, now).isEmpty();
            if (inWindow) {
                approvalService.dropPending(row.getOrderId(), snapshot.getId(),
                    ApprovalRole.OPS_LEAD);
            }
            if (!stillFrozen) {
                approvalService.dropPending(row.getOrderId(), snapshot.getId(),
                    ApprovalRole.DEPT_MANAGER);
            }
            ApprovalChain chain = ApprovalChain.of(approvalService.steps(snapshot.getId()));
            if (!chain.complete()) {
                continue;
            }
            ItemTransitions.Outcome outcome = transitions.apply(row.getItemId(),
                ItemEvent.APPROVE_ALL);
            if (outcome == ItemTransitions.Outcome.APPLIED) {
                syncOrderStatus(row, ItemStatus.READY.name());
                ready++;
                log.info("unfreeze completed the chain: orderId={}, snapshotId={}, itemId={}",
                    row.getOrderId(), snapshot.getId(), row.getItemId());
            } else {
                log.info("unfreeze could not advance item {} to READY: {}", row.getItemId(),
                    outcome);
            }
        }
        return ready;
    }

    /**
     * Mirrors the item's new main status on the order row with the {@code row_version} CAS the rest
     * of the flow uses. A concurrent change is logged instead of failing the freeze: the item state
     * is the authority and the order status is its projection.
     */
    private void syncOrderStatus(InFlightOrderRow row, String status) {
        int version = row.getOrderRowVersion() == null ? 0 : row.getOrderRowVersion().intValue();
        int rows = orderMapper.update(null, Wrappers.<ReleaseOrderPO>lambdaUpdate()
            .eq(ReleaseOrderPO::getId, row.getOrderId())
            .eq(ReleaseOrderPO::getRowVersion, version)
            .set(ReleaseOrderPO::getStatus, status)
            .set(ReleaseOrderPO::getRowVersion, version + 1));
        if (rows != 1) {
            log.info("order status {} not applied to order {}: it changed concurrently",
                status, row.getOrderId());
        }
    }

    // ------------------------------------------------------------------ validation and mapping

    private static FreezeScope parseScope(String raw) {
        FreezeScope scope = FreezeScope.parse(raw);
        if (scope == null) {
            throw new BizException(BizError.PARAM_INVALID, "封板范围不合法：" + raw,
                Map.of("scope", String.valueOf(raw)));
        }
        return scope;
    }

    private Long requireSubsystem(FreezeScope scope, Long subsystemId) {
        if (scope == FreezeScope.GLOBAL) {
            return null;
        }
        if (subsystemId == null || subsystemId <= 0) {
            throw new BizException(BizError.PARAM_INVALID, "系统封板必须指定系统",
                Map.of("subsystemId", String.valueOf(subsystemId)));
        }
        if (subsystemMapper.selectById(subsystemId) == null) {
            throw new BizException(BizError.PARAM_INVALID, "所属系统不存在",
                Map.of("subsystemId", subsystemId));
        }
        return subsystemId;
    }

    private static String requireText(String raw, String label) {
        String text = trimToNull(raw);
        if (text == null) {
            throw new BizException(BizError.PARAM_INVALID, label + "不能为空",
                Map.of("reason", "不能为空"));
        }
        if (text.length() > FreezeEvent.MAX_TEXT) {
            throw new BizException(BizError.PARAM_INVALID,
                label + "长度不能超过 " + FreezeEvent.MAX_TEXT, Map.of("reason", "长度超限"));
        }
        return text;
    }

    private static String optionalText(String raw, String label) {
        String text = trimToNull(raw);
        if (text != null && text.length() > FreezeEvent.MAX_TEXT) {
            throw new BizException(BizError.PARAM_INVALID,
                label + "长度不能超过 " + FreezeEvent.MAX_TEXT, Map.of("impact", "长度超限"));
        }
        return text;
    }

    private static String requireUser(String raw) {
        String user = trimToNull(raw);
        if (user == null) {
            throw new BizException(BizError.PARAM_INVALID, "操作人不能为空",
                Map.of("initiator", "不能为空"));
        }
        if (user.length() > FreezeEvent.MAX_USER_ID) {
            throw new BizException(BizError.PARAM_INVALID,
                "操作人长度不能超过 " + FreezeEvent.MAX_USER_ID,
                Map.of("initiator", "长度超限"));
        }
        return user;
    }

    private LambdaQueryWrapper<FreezeEventPO> historyQuery(long subsystemId) {
        var query = Wrappers.<FreezeEventPO>lambdaQuery()
            .orderByDesc(FreezeEventPO::getId)
            .last("LIMIT " + HISTORY_LIMIT);
        if (subsystemId > 0) {
            query.and(nested -> nested
                .eq(FreezeEventPO::getScope, FreezeScope.GLOBAL.name())
                .or(own -> own
                    .eq(FreezeEventPO::getScope, FreezeScope.SUBSYSTEM.name())
                    .eq(FreezeEventPO::getSubsystemId, subsystemId)));
        }
        return query;
    }

    private static FreezeView toView(FreezeEvent event, Instant now) {
        return new FreezeView(event.id(), event.scope().name(), event.scope().label(),
            event.subsystemId(), event.reason(), event.impact(), event.initiatorId(),
            event.startAt(), event.endAt(), event.status().name(), event.status().label(),
            event.releasedBy(), event.releasedAt(), event.inForceAt(now), event.createdAt());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}