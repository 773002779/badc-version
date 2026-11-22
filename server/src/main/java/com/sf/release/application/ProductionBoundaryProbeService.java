package com.sf.release.application;

import com.sf.release.domain.gate.GateConclusion;
import com.sf.release.domain.gate.GateDecision;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.domain.pipeline.port.ProductionBoundaryProbe;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.infra.repo.DeployUnitMapper;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.po.DeployUnitPO;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The {@link ProductionBoundaryProbe} of the {@code PROD_DEPLOY} node: answers "may the next
 * production batch be triggered now?" from the freeze, gate and 变更项 status (tech design 4.7).
 *
 * <p><strong>Three conditions, in this order.</strong>
 * <ol>
 *   <li><strong>The release is executing.</strong> The pipeline enters {@code PROD_DEPLOY} as soon
 *       as the {@code VERIFY} node completes — which is <em>before</em> the operator has even
 *       submitted the release for production, approved it or pressed 执行生产. Deploying then would
 *       bypass the whole 审批/执行 gate, so the node pauses and waits for
 *       {@code POST .../execute} to move the 变更项 to {@code PROD_DEPLOYING}. This is the reason
 *       the execute use case finds the instance {@code PAUSED} at {@code PROD_DEPLOY}.</li>
 *   <li><strong>No freeze is in force.</strong> {@link FreezeService#active(long, Instant)} (the
 *       strict {@code start_at <= now < end_at} predicate): a batch boundary is the place to stop,
 *       so an in-force freeze pauses the next batch rather than aborting a running one.</li>
 *   <li><strong>The gate is not finally blocked.</strong> {@code INVALID} or a non-waivable
 *       {@code BLOCK} (the same rule {@link GateService#verifyBlockedReason} uses): a waivable
 *       medium finding never pauses production, the waiver already lifted it at admission.</li>
 * </ol>
 *
 * <p>Read-only: the handler writes the pause and its reason, this class only explains it.
 */
@Component
public class ProductionBoundaryProbeService implements ProductionBoundaryProbe {

    private final PipelineRepository repository;
    private final ReleaseOrderMapper orderMapper;
    private final ReleaseItemMapper itemMapper;
    private final DeployUnitMapper unitMapper;
    private final FreezeService freezeService;
    private final GateService gateService;

    public ProductionBoundaryProbeService(PipelineRepository repository,
                                          ReleaseOrderMapper orderMapper,
                                          ReleaseItemMapper itemMapper,
                                          DeployUnitMapper unitMapper,
                                          FreezeService freezeService,
                                          GateService gateService) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.orderMapper = Objects.requireNonNull(orderMapper, "orderMapper must not be null");
        this.itemMapper = Objects.requireNonNull(itemMapper, "itemMapper must not be null");
        this.unitMapper = Objects.requireNonNull(unitMapper, "unitMapper must not be null");
        this.freezeService = Objects.requireNonNull(freezeService, "freezeService must not be null");
        this.gateService = Objects.requireNonNull(gateService, "gateService must not be null");
    }

    @Override
    public String blockedReason(long instanceId) {
        PipelineInstance instance = repository.loadInstance(instanceId);
        if (instance == null) {
            return null;
        }
        PipelineRepository.InstanceContext context = repository.contextOf(instanceId);
        if (context.orderId() <= 0) {
            // A daily pipeline has no release order and no admission state: nothing to check here.
            return null;
        }
        ReleaseOrderPO order = orderMapper.selectById(context.orderId());
        if (order == null || order.getItemId() == null) {
            return null;
        }
        ReleaseItemPO item = itemMapper.selectById(order.getItemId());
        if (item == null) {
            return null;
        }
        ItemStatus status = AdmissionService.statusOf(item);
        if (status != ItemStatus.PROD_DEPLOYING) {
            return "变更项尚未进入生产执行（当前 " + status.name() + "），流水线停在 PROD_DEPLOY"
                + " 等待「执行生产」";
        }
        DeployUnitPO unit = unitMapper.selectById(item.getUnitId());
        long subsystemId = unit == null || unit.getSubsystemId() == null
            ? 0L : unit.getSubsystemId();
        List<FreezeView> freezes = freezeService.active(subsystemId, Instant.now());
        if (!freezes.isEmpty()) {
            return "生产封板生效：" + joinReasons(freezes) + "，批次边界暂停在生产部署（PROD_DEPLOY）";
        }
        GateDecision gate = gateService.decisionForInstance(instance);
        if (gate.conclusion() == GateConclusion.INVALID
                || (gate.conclusion() == GateConclusion.BLOCK && !gate.waivable())) {
            return "门禁未通过（" + gate.summary() + "），批次边界暂停在生产部署（PROD_DEPLOY）";
        }
        return null;
    }

    private static String joinReasons(List<FreezeView> freezes) {
        StringBuilder text = new StringBuilder();
        for (FreezeView freeze : freezes) {
            if (text.length() > 0) {
                text.append("；");
            }
            text.append(freeze.reason() == null ? "封板中" : freeze.reason());
        }
        return text.toString();
    }
}