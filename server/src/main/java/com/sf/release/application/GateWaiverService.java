package com.sf.release.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.application.audit.AuditCommand;
import com.sf.release.application.audit.AuditService;
import com.sf.release.application.notification.NotificationAudiences;
import com.sf.release.application.notification.NotificationCommand;
import com.sf.release.application.notification.NotificationService;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.common.security.Permissions;
import com.sf.release.common.security.Roles;
import com.sf.release.domain.audit.AuditAction;
import com.sf.release.domain.gate.GateConclusion;
import com.sf.release.domain.gate.GateDecision;
import com.sf.release.domain.gate.GateWaiver;
import com.sf.release.domain.gate.GateWaiverStatus;
import com.sf.release.domain.notification.NotificationEventKey;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.domain.task.ReleaseItem;
import com.sf.release.infra.repo.GateWaiverMapper;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.ReleaseTaskMemberMapper;
import com.sf.release.infra.repo.po.GateWaiverPO;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import com.sf.release.infra.repo.po.ReleaseTaskMemberPO;
import com.sf.release.infra.security.CurrentUser;
import com.sf.release.infra.security.ScopeGuard;
import com.sf.release.infra.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The 豁免单 use cases of the check gate (requirements FR-Q-03, tech design §7.2/§7.3).
 *
 * <p><strong>One waiver covers the item's medium findings for one snapshot.</strong> It is not one
 * waiver per check: the requester explains why the medium findings are acceptable, names an
 * expiry, and the waiver is bound to the commit and image digest in force at that moment. A new
 * image or commit retires it automatically ({@link GateWaiver#countsAt}), which is exactly the
 * "commit/镜像变化 → 自动失效" rule.
 *
 * <p><strong>Who may do what.</strong> Any member of the item's release task may <em>request</em>
 * a waiver (the developer usually owns the medium finding and its fixing plan); <em>approving</em>
 * needs the {@code gate:waive} permission and must be somebody else — self-approval is rejected
 * with 40008. A waiver never unlocks the test gate (FR-Q-03: 与测试门禁解耦).
 *
 * <p><strong>Expiry.</strong> {@link #expireDue(int)} is the daily scan: an expired waiver becomes
 * {@link GateWaiverStatus#EXPIRED}, the gate is re-evaluated, and an item that had already been
 * admitted falls back to {@code GATE_FAILED} through the invalidation path — production batches
 * that already ran are never rolled back (requirements §4.6).
 */
@Service
public class GateWaiverService {

    private static final Logger log = LoggerFactory.getLogger(GateWaiverService.class);

    /** Longest accepted validity of a waiver; an indefinitely open waiver is not a decision. */
    static final Duration MAX_VALIDITY = Duration.ofDays(90);

    private final ReleaseItemMapper itemMapper;
    private final GateWaiverMapper waiverMapper;
    private final ReleaseTaskMemberMapper memberMapper;
    private final PipelineRepository repository;
    private final GateService gateService;
    private final GateCheckService checkService;
    private final ReleaseOrderMapper orderMapper;
    private final NotificationService notifications;
    private final AuditService audit;

    public GateWaiverService(ReleaseItemMapper itemMapper,
                             GateWaiverMapper waiverMapper,
                             ReleaseTaskMemberMapper memberMapper,
                             PipelineRepository repository,
                             GateService gateService,
                             GateCheckService checkService,
                             ReleaseOrderMapper orderMapper,
                             NotificationService notifications,
                             AuditService audit) {
        this.itemMapper = Objects.requireNonNull(itemMapper, "itemMapper must not be null");
        this.waiverMapper = Objects.requireNonNull(waiverMapper, "waiverMapper must not be null");
        this.memberMapper = Objects.requireNonNull(memberMapper, "memberMapper must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.gateService = Objects.requireNonNull(gateService, "gateService must not be null");
        this.checkService = Objects.requireNonNull(checkService, "checkService must not be null");
        this.orderMapper = Objects.requireNonNull(orderMapper, "orderMapper must not be null");
        this.notifications = Objects.requireNonNull(notifications,
            "notifications must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
    }

    /**
     * Requests a waiver for the item's medium findings.
     *
     * @throws BizException 40001 the item is not in a waivable state (checks still running, or no
     *                      medium finding); 40002 the blocking findings are 高危/严重 and cannot be
     *                      waived; 40009 the caller is not a member of the release task
     */
    public GateService.GateView request(long itemId, String reason, String impact,
                                        Instant expireAt) {
        CurrentUser user = UserContext.current();
        ReleaseItem item = requireItem(itemId);
        ScopeGuard.assertUnitInScope(item.unitId());
        requireTaskMember(user, item);
        String why = requireText(reason, "豁免原因");
        String impactText = trimToNull(impact);
        if (expireAt == null) {
            throw new BizException(BizError.PARAM_INVALID, "豁免有效期不能为空",
                Map.of("expireAt", "不能为空"));
        }
        Instant now = Instant.now();
        if (!expireAt.isAfter(now)) {
            throw new BizException(BizError.PARAM_INVALID, "豁免有效期必须晚于当前时间",
                Map.of("expireAt", expireAt.toString()));
        }
        if (expireAt.isAfter(now.plus(MAX_VALIDITY))) {
            throw new BizException(BizError.PARAM_INVALID,
                "豁免有效期不能超过 " + MAX_VALIDITY.toDays() + " 天",
                Map.of("maxDays", MAX_VALIDITY.toDays()));
        }
        if (item.status() == ItemStatus.CLOSED || item.status() == ItemStatus.TERMINATED
                || item.status() == ItemStatus.ROLLED_BACK) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "变更项已结束，不能再申请豁免", Map.of("itemId", itemId));
        }
        GateDecision decision = gateService.decisionFor(itemId);
        if (decision.conclusion() == GateConclusion.RUNNING) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "检查尚未完成，现在不能申请豁免", Map.of("itemId", itemId));
        }
        if (decision.conclusion() == GateConclusion.INVALID) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "检查结果已失效，请先重跑检查", Map.of("itemId", itemId));
        }
        if (decision.conclusion() != GateConclusion.BLOCK) {
            throw new BizException(BizError.GATE_BLOCKED, "当前没有需要豁免的中危检查项",
                Map.of("itemId", itemId, "conclusion", decision.conclusion().name()));
        }
        if (!decision.waivable()) {
            throw new BizException(BizError.GATE_BLOCKED,
                "高危/严重检查项不可豁免，只能修复后重跑", Map.of("itemId", itemId,
                    "maxSeverity", decision.maxSeverity().name()));
        }
        GateWaiver active = activeOrPending(itemId);
        if (active != null) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "该变更项已有生效或待审批的豁免单", Map.of("itemId", itemId, "waiverId",
                    active.id(), "status", active.status().name()));
        }
        PipelineInstance instance = repository.latestInstanceOf(itemId);
        GateWaiver waiver = new GateWaiver(null, itemId,
            instance == null ? null : instance.orderId(), GateWaiverStatus.PENDING, why,
            impactText, user.userId(), now, null, null, null, expireAt,
            instance == null ? null : instance.commitSha(),
            instance == null ? null : instance.imageDigest(), now, now);
        GateWaiverPO waiverPo = GateWaiverPO.of(waiver);
        waiverMapper.insert(waiverPo);
        log.info("waiver requested: itemId={}, by={}, expireAt={}", itemId, user.userId(),
            expireAt);
        Map<String, Object> requested = new LinkedHashMap<>();
        requested.put("status", GateWaiverStatus.PENDING.name());
        requested.put("reason", why);
        requested.put("impact", impactText);
        requested.put("expireAt", expireAt.toString());
        requested.put("commitSha", waiver.commitSha());
        requested.put("imageDigest", waiver.imageDigest());
        audit.record(AuditCommand.of(AuditAction.WAIVER_REQUEST, String.valueOf(waiverPo.getId()),
                item.crid(), "申请豁免：变更项 " + item.crid())
            .after(requested)
            .reason(why)
            .scopeUnitId(item.unitId())
            .build());
        notifyWaiverPending(item, waiverPo.getId(), instance, why);
        return gateService.view(itemId);
    }

    /**
     * {@code WAIVER_PENDING} (design section 3): everybody who may approve the waiver plus the
     * system owner. The waiver id is the occurrence ref, so filing a second waiver after the first
     * one is decided produces a new notification.
     */
    private void notifyWaiverPending(ReleaseItem item, Long waiverId, PipelineInstance instance,
                                     String reason) {
        if (waiverId == null) {
            return;
        }
        String publishNo = null;
        if (instance != null && instance.orderId() != null && instance.orderId() > 0) {
            ReleaseOrderPO order = orderMapper.selectById(instance.orderId());
            publishNo = order == null ? null : order.getPublishNo();
        }
        String label = publishNo == null ? item.crid() : publishNo;
        notifications.notify(new NotificationCommand(
            NotificationEventKey.WAIVER_PENDING, String.valueOf(waiverId), label,
            NotificationEventKey.WAIVER_PENDING.label(),
            "变更项 " + item.crid() + " 的检查豁免申请待审批：" + reason,
            String.valueOf(waiverId),
            NotificationAudiences.waiverApprovers(item.id())));
    }

    /**
     * Approves a pending waiver. The approver needs {@code gate:waive} and must differ from the
     * requester (40008).
     */
    public GateService.GateView approve(long waiverId, String note) {
        CurrentUser user = UserContext.current();
        requirePerm(user, Permissions.GATE_WAIVE);
        GateWaiver waiver = requireWaiver(waiverId);
        if (waiver.status() != GateWaiverStatus.PENDING) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "豁免单不是待审批状态：" + waiver.status(),
                Map.of("waiverId", waiverId, "status", waiver.status().name()));
        }
        if (user.userId().equals(waiver.requestedBy())) {
            // No self-approval, not even for an administrator (C19/C20 spirit, decision D3): a
            // second person has to look at the finding. The request can always be re-filed by
            // somebody else.
            throw new BizException(BizError.SELF_APPROVAL_FORBIDDEN,
                "豁免不能自审：申请人与审批人必须不同",
                Map.of("waiverId", waiverId, "requestedBy", waiver.requestedBy()));
        }
        Instant now = Instant.now();
        GateWaiver approved = waiver.decide(GateWaiverStatus.APPROVED, user.userId(),
            trimToNull(note), now);
        waiverMapper.updateById(GateWaiverPO.of(approved));
        log.info("waiver approved: waiverId={}, itemId={}, by={}", waiverId, waiver.itemId(),
            user.userId());
        gateService.evaluate(waiver.itemId());
        GateService.GateView view = gateService.view(waiver.itemId());
        ReleaseItemPO scoped = itemMapper.selectById(waiver.itemId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", GateWaiverStatus.APPROVED.name());
        after.put("approvedBy", user.userId());
        after.put("decideReason", trimToNull(note));
        audit.record(AuditCommand.of(AuditAction.WAIVER_APPROVE, String.valueOf(waiverId),
                labelOf(scoped, waiver.itemId()), "批准豁免：" + labelOf(scoped, waiver.itemId()))
            .before(Map.of("status", GateWaiverStatus.PENDING.name()))
            .after(after)
            .reason(note)
            .scopeUnitId(scoped == null ? null : scoped.getUnitId())
            .build());
        return view;
    }

    /** Rejects a pending waiver; the rejection reason is required and kept. */
    public GateService.GateView reject(long waiverId, String note) {
        CurrentUser user = UserContext.current();
        requirePerm(user, Permissions.GATE_WAIVE);
        GateWaiver waiver = requireWaiver(waiverId);
        if (waiver.status() != GateWaiverStatus.PENDING) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "豁免单不是待审批状态：" + waiver.status(),
                Map.of("waiverId", waiverId, "status", waiver.status().name()));
        }
        String why = requireText(note, "驳回原因");
        Instant now = Instant.now();
        GateWaiver rejected = waiver.decide(GateWaiverStatus.REJECTED, user.userId(), why, now);
        waiverMapper.updateById(GateWaiverPO.of(rejected));
        log.info("waiver rejected: waiverId={}, itemId={}, by={}", waiverId, waiver.itemId(),
            user.userId());
        gateService.evaluate(waiver.itemId());
        GateService.GateView view = gateService.view(waiver.itemId());
        ReleaseItemPO scoped = itemMapper.selectById(waiver.itemId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", GateWaiverStatus.REJECTED.name());
        after.put("approvedBy", user.userId());
        after.put("decideReason", why);
        audit.record(AuditCommand.of(AuditAction.WAIVER_REJECT, String.valueOf(waiverId),
                labelOf(scoped, waiver.itemId()), "驳回豁免：" + labelOf(scoped, waiver.itemId()))
            .before(Map.of("status", GateWaiverStatus.PENDING.name()))
            .after(after)
            .reason(why)
            .scopeUnitId(scoped == null ? null : scoped.getUnitId())
            .build());
        return view;
    }

    /**
     * Expires the approved waivers whose validity passed (requirements §4.6: 豁免到期 → 视为
     * {@code CHECK_INVALID}). Called by the daily scan.
     *
     * @return the number of expired waivers
     */
    public int expireDue(Instant now, int limit) {
        List<GateWaiverPO> expired = waiverMapper.selectExpired(now, limit);
        int count = 0;
        for (GateWaiverPO po : expired) {
            GateWaiver waiver = po.toDomain();
            try {
                waiverMapper.updateById(GateWaiverPO.of(waiver.expired(now)));
                // requirements §4.6: 豁免到期 → 视为 CHECK_INVALID. The checks themselves did not
                // change, but the decision that lifted them is gone, so the recorded results are
                // invalid: the gate cannot be trusted until the checks run (and a new waiver is
                // decided) again. This is what turns a CHECK_WAIVED into CHECK_INVALID instead of
                // silently back into CHECK_FAIL, which would look like a brand-new finding.
                checkService.invalidateAll(waiver.itemId(), "豁免单已到期，原检查结果失效");
                gateService.evaluate(waiver.itemId());
                count++;
                log.warn("waiver expired: waiverId={}, itemId={}, expireAt={}", waiver.id(),
                    waiver.itemId(), waiver.expireAt());
            } catch (RuntimeException ex) {
                log.error("could not expire waiver {}: {}", waiver.id(), ex.getMessage(), ex);
            }
        }
        return count;
    }

    /** The item's approved or pending waiver, or {@code null}. */
    private GateWaiver activeOrPending(long itemId) {
        GateWaiver approved = waiverMapper.selectApproved(itemId) == null ? null
            : waiverMapper.selectApproved(itemId).toDomain();
        if (approved != null && approved.expireAt() != null
                && approved.expireAt().isAfter(Instant.now())) {
            return approved;
        }
        GateWaiverPO latest = waiverMapper.selectLatest(itemId);
        if (latest == null) {
            return null;
        }
        GateWaiver waiver = latest.toDomain();
        return waiver.status() == GateWaiverStatus.PENDING ? waiver : null;
    }

    private GateWaiver requireWaiver(long waiverId) {
        GateWaiverPO po = waiverMapper.selectById(waiverId);
        if (po == null) {
            throw new BizException(BizError.PARAM_INVALID, "豁免单不存在",
                Map.of("waiverId", waiverId));
        }
        return po.toDomain();
    }

    private ReleaseItem requireItem(long itemId) {
        ReleaseItemPO po = itemMapper.selectById(itemId);
        if (po == null) {
            throw new BizException(BizError.PARAM_INVALID, "变更项不存在", Map.of("itemId", itemId));
        }
        return po.toDomain();
    }

    /** Requesting is open to the release task members; approving is the reviewer's job. */
    private void requireTaskMember(CurrentUser user, ReleaseItem item) {
        if (user.hasRole(Roles.ADMIN)) {
            return;
        }
        Long members = memberMapper.selectCount(Wrappers.<ReleaseTaskMemberPO>lambdaQuery()
            .eq(ReleaseTaskMemberPO::getTaskId, item.taskId())
            .eq(ReleaseTaskMemberPO::getUserId, user.userId()));
        if (members == null || members == 0) {
            throw new BizException(BizError.FORBIDDEN,
                "只有该发布任务的成员可以申请豁免",
                Map.of("taskId", item.taskId(), "userId", user.userId()));
        }
    }

    private static void requirePerm(CurrentUser user, String perm) {
        if (!user.hasPerm(perm)) {
            throw new BizException(BizError.FORBIDDEN, "无权执行该操作：" + perm,
                Map.of("perm", perm));
        }
    }

    private static String requireText(String raw, String label) {
        String text = trimToNull(raw);
        if (text == null) {
            throw new BizException(BizError.PARAM_INVALID, label + "不能为空",
                Map.of("text", "不能为空"));
        }
        if (text.length() > GateWaiver.MAX_TEXT) {
            throw new BizException(BizError.PARAM_INVALID,
                label + "长度不能超过 " + GateWaiver.MAX_TEXT, Map.of("text", "长度超限"));
        }
        return text;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The item's CRID for the audit label, or its id when the item row is gone. */
    private static String labelOf(ReleaseItemPO item, long itemId) {
        return item == null || item.getCrid() == null || item.getCrid().isBlank()
            ? String.valueOf(itemId) : item.getCrid();
    }
}