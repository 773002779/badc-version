package com.sf.release.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.security.Permissions;
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
import com.sf.release.infra.security.CurrentUser;
import com.sf.release.infra.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The 生产封板 use cases (tech design 4.2): declare a freeze and handle the releases already in
 * flight, lift one, close the expired ones, and answer the two different questions the platform
 * asks about freezes.
 *
 * <p><strong>Two predicates, one rule each.</strong>
 * <ul>
 *   <li>{@link #active(long, Instant)} — <em>in force</em>: {@code ACTIVE AND start_at <= at AND
 *       end_at > at}. This is the display question ("is production frozen right now"), used by the
 *       banner.</li>
 *   <li>{@link #governing(long, Instant, Instant)} — <em>governing</em>: {@code ACTIVE AND end_at >
 *       now AND start_at <= max(now, plannedAt)}. This is the decision question used by the
 *       admission step and the in-flight handling: a freeze
 *       declared for next week already governs a release planned for next week <em>today</em>, so
 *       the second signature is raised now instead of being discovered at execution time
 *       (fail-closed). With no planned time the predicate degenerates to the in-force one; that is
 *       what the banner passes. Both are documented on {@link FreezeEvent#governsAt(Instant,
 *       Instant)}.</li>
 * </ul>
 *
 * <p><strong>The pre-execution review uses the in-force predicate, not {@code governing}.</strong>
 * A freeze declared for later already raised the second signature at admission; blocking execution
 * for it today would stop releases planned before it starts. The review therefore asks
 * {@link #active(long, Instant)} ("is production frozen right now", design 4.6) — the same question
 * the batch-boundary probe asks. Every other freeze consumer (admission, in-flight raise, unfreeze
 * recompute) uses {@code governing}.
 *
 * <p><strong>Declaring.</strong> {@link #create} writes the event with status {@code ACTIVE}
 * immediately (a scheduled freeze is recorded and visible at once) and, in the same transaction,
 * raises the requirement of every release already in the production flow that the new freeze
 * <em>governs</em>: the chain of its latest admission snapshot gets the two signatures of design
 * 4.4 ({@code OPS_LEAD} first, {@code DEPT_MANAGER} second — inside the window or not) and a
 * {@code READY} or {@code SUBMITTED} 变更项 is pulled back into {@code PENDING_APPROVAL}.
 *
 * <p><strong>Lifting and expiry recompute, they do not guess.</strong> The chain of each affected
 * snapshot is recomputed from what the snapshot recorded: when no freeze governs the release any
 * more, inside the window it needs no signature ({@code OPS_LEAD} and {@code DEPT_MANAGER} pending
 * steps are dropped) and outside it needs {@code OPS_LEAD} (only the pending {@code DEPT_MANAGER}
 * is dropped). Decided steps are never deleted — the approval already given stays as evidence.
 * Whether the freeze signatures are still required is decided by the <em>same</em> governing
 * predicate that raised them, evaluated per order at its own planned time: an unrelated freeze
 * expiring must not drop a step that another, still-governing freeze requires. If the chain then
 * needs nothing more, {@code APPROVE_ALL} moves the 变更项 to {@code READY}.
 * {@link #expireDue(Instant)} closes all overdue freezes first and then applies exactly that
 * recompute once, so their pending signatures can go away.
 *
 * <p><strong>The release reason has no column.</strong> {@code freeze_event} records
 * {@code released_by}/{@code released_at} only (design 4.2), so the mandatory release reason is
 * validated and written to the audit log; it is not stored. Adding a column would be a schema
 * change the design does not ask for.
 *
 * <p><strong>Bounded, paged in-flight scans.</strong> Both in-flight passes walk the affected
 * orders with keyset pagination ({@code o.id > lastId}) in pages of
 * {@link #IN_FLIGHT_PAGE_SIZE}, until the last page, capped at
 * {@link #IN_FLIGHT_PAGE_SIZE} x {@link #IN_FLIGHT_MAX_PAGES} orders per pass. A pass that reaches
 * the cap logs a WARN and stops: the orders beyond it keep their current chain (they are still
 * governed by the freeze for admission and the pre-execution review — those read the freeze table,
 * not the scan — but their chains were not extended in this pass), and the next declaration or
 * unfreeze starts the scan from the lowest id again. The cap is a blast-radius guard for a
 * platform-wide freeze on an unusually large backlog, not the normal operating point.
 */
@Service
public class FreezeService {

    private static final Logger log = LoggerFactory.getLogger(FreezeService.class);

    /** Upper bound of freezes the daily scan closes per round. */
    static final int EXPIRE_LIMIT = 200;

    /** Rows read per in-flight page. */
    static final int IN_FLIGHT_PAGE_SIZE = 200;

    /** Hard cap of one in-flight pass: {@code PAGE_SIZE x MAX_PAGES} orders (2000). */
    static final int IN_FLIGHT_MAX_PAGES = 10;

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
     * @param subsystemId required for {@code SUBSYSTEM}, must be absent for {@code GLOBAL}
     * @param startAt     when the freeze starts; {@code null} means now, a past instant is allowed
     *                    (a freeze declared after the fact still covers the releases in flight)
     * @param initiator   the user who declared it
     * @throws BizException 40000 for a bad scope, a missing subsystem, a subsystem on a GLOBAL
     *                      freeze, a blank/oversized reason or an end time that is not after the
     *                      start (and after now); 40009 the caller lacks {@code control:freeze}
     */
    @Transactional
    public FreezeView create(String scope, Long subsystemId, String reason, String impact,
                             Instant startAt, Instant endAt, String initiator) {
        requirePerm(Permissions.CONTROL_FREEZE);
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
        FreezeEvent stored = po.toDomain();
        int pulled = applyInFlight(stored, now);
        log.info("freeze declared: id={}, scope={}, subsystemId={}, by={}, startAt={}, endAt={},"
            + " pulledOrders={}", stored.id(), parsedScope, target, who, start, endAt, pulled);
        return toView(stored, now);
    }

    /**
     * Lifts an {@code ACTIVE} freeze and recomputes the chains of the releases it affected.
     *
     * @param actor  the user who lifts it
     * @param reason why it is lifted; required. {@code freeze_event} has no column for it, so it is
     *               validated and logged, not stored.
     * @throws BizException 40000 the freeze does not exist or the reason is missing; 40001 it is
     *                      already {@code RELEASED}/{@code EXPIRED} (or a concurrent call won);
     *                      40009 the caller lacks {@code control:unfreeze}
     */
    @Transactional
    public FreezeView release(long id, String actor, String reason) {
        requirePerm(Permissions.CONTROL_UNFREEZE);
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
            .set(FreezeEventPO::getStatus, released.status().name())
            .set(FreezeEventPO::getReleasedBy, who)
            .set(FreezeEventPO::getReleasedAt, now));
        if (rows != 1) {
            throw new BizException(BizError.STATE_NOT_ALLOWED, "封板状态已被并发修改，请刷新后重试",
                Map.of("id", id));
        }
        int ready = recomputeAffectedOrders(List.of(released), now);
        log.info("freeze released: id={}, by={}, reason={}, recomputedToReady={}", id, who, why,
            ready);
        return toView(released, now);
    }

    /**
     * The freezes <em>in force</em> at {@code at} that affect one system: a GLOBAL freeze always
     * applies, a SUBSYSTEM one only to its own. {@code subsystemId = 0} answers every freeze in
     * force (regardless of scope), which is what the platform-wide banner shows.
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
     * The freezes that <strong>govern</strong> a release of one system decided at {@code now} and
     * planned for {@code plannedAt} (see the class javadoc; the predicate is
     * {@link FreezeEvent#governsAt(Instant, Instant)}). {@code subsystemId = 0} answers every
     * governing freeze regardless of scope.
     *
     * <p>The admission step, the in-flight handling and the pre-execution review all use this one
     * question, so a freeze declared for later cannot be missed by one of them and honoured by
     * another.
     *
     * @param plannedAt the planned release time; {@code null} means "no plan known", which
     *                  degenerates to "in force now"
     * @throws BizException 40000 a negative subsystem id
     */
    public List<FreezeView> governing(long subsystemId, Instant now, Instant plannedAt) {
        if (subsystemId < 0) {
            throw new BizException(BizError.PARAM_INVALID, "系统 ID 不能为负数",
                Map.of("subsystemId", subsystemId));
        }
        Instant when = now == null ? Instant.now() : now;
        Instant threshold = plannedAt == null || plannedAt.isBefore(when) ? when : plannedAt;
        List<FreezeEventPO> rows = subsystemId == 0
            ? freezeMapper.selectGoverning(when, threshold)
            : freezeMapper.selectGoverningForSubsystem(subsystemId, when, threshold);
        return rows.stream().map(po -> toView(po.toDomain(), when)).toList();
    }

    /**
     * The 封板 list of {@code GET /api/v1/freezes}.
     *
     * @param active      {@code null}/true: only the freezes governing at {@code plannedAt} (or, with
     *                    no planned time, the ones in force now); false: every recorded freeze,
     *                    newest first (history)
     * @param subsystemId 0/null: every scope; positive: GLOBAL plus that system
     * @param plannedAt   optional: the release time the page is asking about, so a freeze declared
     *                    for that time is listed as governing
     * @throws BizException 40000 a negative subsystem id
     */
    public List<FreezeView> list(Boolean active, Long subsystemId, Instant plannedAt) {
        long target = subsystemId == null ? 0L : subsystemId;
        if (target < 0) {
            throw new BizException(BizError.PARAM_INVALID, "系统 ID 不能为负数",
                Map.of("subsystemId", target));
        }
        Instant now = Instant.now();
        if (active == null || active) {
            return governing(target, now, plannedAt);
        }
        List<FreezeEventPO> rows = freezeMapper.selectList(historyQuery(target));
        return rows.stream().map(po -> toView(po.toDomain(), now)).toList();
    }

    /**
     * Closes the {@code ACTIVE} freezes whose {@code end_at} passed and recomputes the chains they
     * no longer require. Called by the daily scan.
     *
     * @return the number of freezes that became {@code EXPIRED}
     */
    @Transactional
    public int expireDue(Instant now) {
        Instant when = now == null ? Instant.now() : now;
        List<FreezeEventPO> due = freezeMapper.selectDueForExpiry(when, EXPIRE_LIMIT);
        List<FreezeEvent> closed = new ArrayList<>(due.size());
        for (FreezeEventPO po : due) {
            // The aggregate owns the status rule: an ACTIVE freeze may expire, anything else throws.
            FreezeEvent event = po.toDomain().expired(when);
            int rows = freezeMapper.update(null, Wrappers.<FreezeEventPO>lambdaUpdate()
                .eq(FreezeEventPO::getId, event.id())
                .eq(FreezeEventPO::getStatus, FreezeStatus.ACTIVE.name())
                .set(FreezeEventPO::getStatus, event.status().name()));
            if (rows != 1) {
                continue;
            }
            closed.add(event);
            log.info("freeze expired: id={}, endAt={}", event.id(), event.endAt());
        }
        if (closed.isEmpty()) {
            return 0;
        }
        // One recompute pass for the whole round: all rows are already EXPIRED above, so the
        // "still governed" checks see the post-expiry state.
        recomputeAffectedOrders(closed, when);
        return closed.size();
    }

    // ------------------------------------------------------------------ in-flight handling

    /**
     * Raises the requirement of every release already in the production flow that the new freeze
     * <em>governs</em> (design 4.2 and 4.4): the chain is extended to the freeze's two signatures,
     * {@code OPS_LEAD} and then {@code DEPT_MANAGER} (appended in that order, so the step numbers
     * keep the signature order), and a {@code READY}/{@code SUBMITTED} item is pulled back into
     * {@code PENDING_APPROVAL}. The window does not matter here: a freeze inside the window needs
     * both signatures too, which is why the submit path asks
     * {@code AdmissionEvaluator} the same question.
     *
     * @return the number of 变更项 pulled back into approval
     */
    private int applyInFlight(FreezeEvent freeze, Instant now) {
        long filter = freeze.scope() == FreezeScope.GLOBAL ? 0L : freeze.subsystemId();
        List<InFlightOrderRow> rows = loadInFlight(Set.of(filter));
        Map<Long, AdmissionSnapshotPO> snapshots = latestSnapshots(rows);
        int pulled = 0;
        for (InFlightOrderRow row : rows) {
            if (!freeze.governsAt(now, row.getPlannedReleaseTime())) {
                // The release is planned before the freeze starts and the freeze has not begun:
                // it will be gone by then, so it is not pulled into a second signature.
                continue;
            }
            AdmissionSnapshotPO snapshot = snapshots.get(row.getOrderId());
            if (snapshot == null) {
                // Defensive: a SUBMITTED/PENDING_APPROVAL/READY item always has a snapshot (the
                // submit use case writes one). Without it there is no chain to extend and no window
                // fact to recompute later, so the release is left as it is rather than pulled into
                // an approval it could never leave.
                log.warn("freeze in-flight handling skipped: orderId={} itemId={} has no admission"
                    + " snapshot (freezeId={})", row.getOrderId(), row.getItemId(), freeze.id());
                continue;
            }
            approvalService.ensureChain(row.getOrderId(), snapshot.getId(),
                List.of(ApprovalRole.OPS_LEAD, ApprovalRole.DEPT_MANAGER));
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
     * Recomputes the chain of every release affected by a freeze that no longer governs (lifted or
     * expired): design 4.2, see the class javadoc. Only {@code PENDING_APPROVAL} items are
     * recomputed: a {@code READY} one no longer needs a signature, and a {@code SUBMITTED} one has
     * no snapshot yet.
     *
     * <p><strong>Symmetry with the raise.</strong> The freeze signatures are dropped only when no
     * freeze <em>governs</em> the order any more, evaluated at that order's own planned time with the
     * same predicate that raised them ({@link #governing(long, Instant, Instant)}). An unrelated freeze
     * expiring therefore cannot drop a step that another freeze, declared for the order's planned
     * time, still requires.
     *
     * @param closed the freezes that just stopped governing (one from a release, several from the
     *               expiry scan); all of them are already non-ACTIVE in the database
     * @return the number of 变更项 moved to {@code READY} by the recompute
     */
    private int recomputeAffectedOrders(Collection<FreezeEvent> closed, Instant now) {
        List<InFlightOrderRow> rows = loadInFlight(filtersOf(closed));
        Map<Long, AdmissionSnapshotPO> snapshots = latestSnapshots(rows);
        Map<GovernorKey, Boolean> governed = new HashMap<>();
        int ready = 0;
        for (InFlightOrderRow row : rows) {
            if (!ItemStatus.PENDING_APPROVAL.name().equals(row.getItemStatus())) {
                continue;
            }
            AdmissionSnapshotPO snapshot = snapshots.get(row.getOrderId());
            if (snapshot == null) {
                log.warn("unfreeze recompute skipped: orderId={} itemId={} has no admission snapshot",
                    row.getOrderId(), row.getItemId());
                continue;
            }
            boolean inWindow = Boolean.TRUE.equals(snapshot.getInWindow());
            // Another freeze may still govern this release (possibly one declared for later and
            // matching the planned time): then both freeze signatures stay, including the
            // OPS_LEAD step that the window alone would not have asked for.
            if (!stillGoverned(row, now, governed)) {
                if (inWindow) {
                    approvalService.dropPending(row.getOrderId(), snapshot.getId(),
                        ApprovalRole.OPS_LEAD);
                }
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

    /** The scan filters of a closed set: any GLOBAL freeze means "every system". */
    private static Set<Long> filtersOf(Collection<FreezeEvent> closed) {
        Set<Long> filters = new HashSet<>();
        for (FreezeEvent freeze : closed) {
            if (freeze.scope() == FreezeScope.GLOBAL || freeze.subsystemId() == null) {
                return Set.of(0L);
            }
            filters.add(freeze.subsystemId());
        }
        return filters.isEmpty() ? Set.of(0L) : filters;
    }

    /**
     * Whether a freeze still governs {@code row} at its planned time. One query per distinct
     * {@code (subsystemId, plannedAt)} pair, not per order: the {@code cache} is shared by the loop.
     */
    private boolean stillGoverned(InFlightOrderRow row, Instant now,
                                  Map<GovernorKey, Boolean> cache) {
        long subsystemId = row.getSubsystemId() == null ? 0L : row.getSubsystemId();
        Instant planned = row.getPlannedReleaseTime();
        GovernorKey key = new GovernorKey(subsystemId, planned);
        Boolean cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Instant threshold = planned == null || planned.isBefore(now) ? now : planned;
        boolean frozen = subsystemId > 0
            ? !freezeMapper.selectGoverningForSubsystem(subsystemId, now, threshold).isEmpty()
            : !freezeMapper.selectGoverning(now, threshold).isEmpty();
        cache.put(key, frozen);
        return frozen;
    }

    /** Cache key of the "still governed" question: one system, one planned time. */
    private record GovernorKey(long subsystemId, Instant plannedAt) {
    }

    /**
     * The in-flight orders of one pass, walked page by page with keyset pagination
     * ({@code o.id > lastId}) so no order is skipped by re-reading the same first page. A pass stops
     * after {@link #IN_FLIGHT_MAX_PAGES} pages and logs a WARN; the orders beyond the cap keep their
     * current chain (they are still governed by the freeze for admission and the pre-execution
     * review) and the next pass starts again from the lowest order id.
     */
    private List<InFlightOrderRow> loadInFlight(Set<Long> filters) {
        List<InFlightOrderRow> rows = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (long filter : filters) {
            long afterId = 0;
            int pages = 0;
            boolean exhausted = false;
            while (pages < IN_FLIGHT_MAX_PAGES) {
                List<InFlightOrderRow> page = orderMapper.selectInFlight(filter, afterId,
                    IN_FLIGHT_PAGE_SIZE);
                for (InFlightOrderRow row : page) {
                    if (seen.add(row.getOrderId())) {
                        rows.add(row);
                    }
                }
                pages++;
                if (page.size() < IN_FLIGHT_PAGE_SIZE) {
                    exhausted = true;
                    break;
                }
                afterId = page.get(page.size() - 1).getOrderId();
            }
            if (!exhausted) {
                log.warn("in-flight scan stopped at the {} page cap ({} orders, filter={}); the"
                        + " remaining orders keep their chain — they are still governed by the"
                        + " freeze for admission and the pre-execution review", IN_FLIGHT_MAX_PAGES,
                    IN_FLIGHT_PAGE_SIZE * IN_FLIGHT_MAX_PAGES, filter);
            }
        }
        return rows;
    }

    /** The newest snapshot of every order in {@code rows}, loaded with one statement. */
    private Map<Long, AdmissionSnapshotPO> latestSnapshots(List<InFlightOrderRow> rows) {
        List<Long> orderIds = rows.stream()
            .map(InFlightOrderRow::getOrderId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, AdmissionSnapshotPO> byOrder = new HashMap<>();
        for (AdmissionSnapshotPO po : snapshotMapper.selectLatestByOrderIds(orderIds)) {
            byOrder.put(po.getOrderId(), po);
        }
        return byOrder;
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

    private static void requirePerm(String perm) {
        CurrentUser user = UserContext.current();
        if (!user.hasPerm(perm)) {
            throw new BizException(BizError.FORBIDDEN, "无权执行该操作：" + perm,
                Map.of("perm", perm));
        }
    }

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
            if (subsystemId != null && subsystemId != 0) {
                throw new BizException(BizError.PARAM_INVALID,
                    "全局封板不能指定系统（scope=GLOBAL 会作用于所有系统）",
                    Map.of("subsystemId", subsystemId));
            }
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