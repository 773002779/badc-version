package com.sf.release.application;

import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.admission.AdmissionDecision;
import com.sf.release.domain.admission.AdmissionEvaluator;
import com.sf.release.domain.admission.AdmissionFacts;
import com.sf.release.domain.gate.GateConclusion;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.domain.task.TestStatus;
import com.sf.release.infra.repo.DeployUnitMapper;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.po.DeployUnitPO;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The 生产准入 judgement of one 发布单 (tech design 4.5): resolve the facts the pure
 * {@link AdmissionEvaluator} needs (gate conclusion, test dimension, OA ticket, window, freeze) and
 * answer the decision plus the evidence the snapshot must record.
 *
 * <p><strong>No clock, no writes, no authorisation.</strong> {@link #evaluate} only reads. The
 * caller owns the transaction and the access check: {@link SubmitProdService} checks {@code
 * control:submit-prod} and the deployment-unit scope, then calls {@link #plan}. A future "check
 * before submit" preview endpoint must do the same.
 *
 * <p><strong>The freeze is judged at submission time, the window at the planned time.</strong> The
 * window question is "will the release happen inside an allowed slot" and uses the planned instant;
 * the freeze question is "is production frozen while we hand this release over" and uses now. That
 * asymmetry is in design 4.5 (step 4 uses the planned time, step 5 says "生效中") and is deliberate:
 * a freeze that starts later must not block the submission, it only adds a signature when the
 * release is actually handed over during the freeze.
 */
@Service
public class AdmissionService {

    private final ReleaseOrderMapper orderMapper;
    private final ReleaseItemMapper itemMapper;
    private final DeployUnitMapper unitMapper;
    private final GateService gateService;
    private final WindowService windowService;
    private final FreezeService freezeService;

    public AdmissionService(ReleaseOrderMapper orderMapper,
                            ReleaseItemMapper itemMapper,
                            DeployUnitMapper unitMapper,
                            GateService gateService,
                            WindowService windowService,
                            FreezeService freezeService) {
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.unitMapper = unitMapper;
        this.gateService = gateService;
        this.windowService = windowService;
        this.freezeService = freezeService;
    }

    /**
     * The judgement of one 发布单 for a draft plan. Read-only.
     *
     * @param orderId      the 发布单 (it knows the 变更项)
     * @param plannedAt    the proposed production time; {@code null} produces "请填写" failures
     * @param oaTicketNo   the OA ticket the operator typed; may be null while drafting
     * @throws BizException 40000 the order, item or deployment unit does not exist
     */
    public AdmissionDecision evaluate(long orderId, Instant plannedAt, String oaTicketNo) {
        return plan(orderId, plannedAt, oaTicketNo).decision();
    }

    /**
     * The judgement plus every fact the admission snapshot records (design 4.5).
     *
     * <p>Package-private on purpose: it reads and writes nothing, but its result is the evidence the
     * submit use case persists, so it stays next to that use case instead of becoming a public API
     * that callers might mistake for an authorised operation.
     */
    AdmissionPlan plan(long orderId, Instant plannedAt, String oaTicketNo) {
        ReleaseOrderPO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(BizError.PARAM_INVALID, "发布单不存在",
                Map.of("orderId", orderId));
        }
        ReleaseItemPO item = itemMapper.selectById(order.getItemId());
        if (item == null) {
            throw new BizException(BizError.PARAM_INVALID, "变更项不存在",
                Map.of("orderId", orderId, "itemId", order.getItemId()));
        }
        DeployUnitPO unit = unitMapper.selectById(item.getUnitId());
        if (unit == null) {
            throw new BizException(BizError.PARAM_INVALID, "部署单元不存在",
                Map.of("itemId", item.getId(), "unitId", item.getUnitId()));
        }
        long subsystemId = unit.getSubsystemId() == null ? 0L : unit.getSubsystemId();
        Instant now = Instant.now();

        // One gate read answers the check conclusion, the waiver, the test dimension and the image
        // digest of the instance the item will deploy; nothing here recomputes the gate itself.
        GateService.GateView gate = gateService.view(item.getId());
        Long windowId = null;
        boolean inWindow = false;
        if (plannedAt != null) {
            WindowCheckView window = windowService.check(subsystemId, plannedAt);
            inWindow = window.inWindow();
            windowId = window.windowId();
        }
        List<FreezeView> freezes = freezeService.active(subsystemId, now);
        boolean frozen = !freezes.isEmpty();
        AdmissionDecision decision = AdmissionEvaluator.evaluate(new AdmissionFacts(oaTicketNo,
            plannedAt, now, GateConclusion.valueOf(gate.conclusion()), gate.waivable(),
            gate.reasons(), TestStatus.parse(gate.testStatus()), inWindow, frozen));
        boolean waiverCounted = GateConclusion.WAIVED.name().equals(gate.conclusion())
            && gate.waiver() != null;
        return new AdmissionPlan(
            order.getPublishNo(),
            order.getId(),
            item.getId(),
            item.getUnitId(),
            subsystemId,
            plannedAt,
            oaTicketNo,
            decision,
            windowId,
            inWindow,
            freezes.stream().map(FreezeView::id).toList(),
            gate.maxSeverity(),
            gate.conclusion(),
            waiverCounted ? List.of(gate.waiver().id()) : List.of(),
            waiverCounted ? gate.waiver().expireAt() : null,
            gate.testStatus(),
            gate.testPassBy(),
            gate.imageDigest(),
            gate.commitSha());
    }

    /** The main status of a stored item; a missing/corrupt word degrades to {@code PENDING}. */
    static ItemStatus statusOf(ReleaseItemPO item) {
        return item.toDomain().status();
    }
}