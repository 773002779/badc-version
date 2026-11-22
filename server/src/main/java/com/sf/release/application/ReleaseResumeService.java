package com.sf.release.application;

import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.pipeline.InstanceStatus;
import com.sf.release.domain.pipeline.NodeHandlerRegistry;
import com.sf.release.domain.pipeline.NodeType;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.port.PipelineAdvancer;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.infra.repo.ReleaseQueryMapper;
import com.sf.release.infra.repo.po.ReleaseHeadRow;
import com.sf.release.infra.security.ScopeGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Resumes a {@code PAUSED} pipeline instance (P2, closes R5: a pause must have a way out).
 *
 * <p><strong>When a resume is allowed.</strong> Three conditions, all checked before anything is
 * written:
 * <ol>
 *   <li>the newest execution of the 发布单 is {@code PAUSED} — a running or finished instance has
 *       nothing to resume;</li>
 *   <li>the paused node has a registered {@code NodeHandler} — otherwise resuming would pause again
 *       immediately, which is a lie about what the button does;</li>
 *   <li>the gate is satisfied again — {@code PAUSED} is a stop caused by a gate that needs a human,
 *       so the human must fix it first (a passing check or an approved waiver).</li>
 * </ol>
 *
 * <p><strong>What it does not do.</strong> It never rolls anything back and never re-runs a
 * succeeded node: the instance is flipped back to {@code RUNNING} with its status reason cleared and
 * the very next advance enters the node it was blocked on. In P2 the only pause a normal release
 * could reach was "the next node has no handler yet" ({@code PROD_DEPLOY}, P3).
 *
 * <p><strong>Production is not a gate pause.</strong> A pause at {@code PROD_DEPLOY} is the
 * production execution boundary (approval, pre-execution review, batch boundary). Resuming it here
 * would skip {@code control:execute-prod} and the review, so it is refused with an explicit pointer
 * to {@code POST /releases/{publishNo}/execute} ({@link ExecuteProdService}) instead.
 */
@Service
public class ReleaseResumeService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseResumeService.class);

    private final ReleaseQueryMapper queryMapper;
    private final PipelineRepository repository;
    private final NodeHandlerRegistry handlerRegistry;
    private final GateService gateService;
    private final PipelineAdvancer advancer;

    public ReleaseResumeService(ReleaseQueryMapper queryMapper,
                                PipelineRepository repository,
                                NodeHandlerRegistry handlerRegistry,
                                GateService gateService,
                                PipelineAdvancer advancer) {
        this.queryMapper = Objects.requireNonNull(queryMapper, "queryMapper must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.handlerRegistry = Objects.requireNonNull(handlerRegistry,
            "handlerRegistry must not be null");
        this.gateService = Objects.requireNonNull(gateService, "gateService must not be null");
        this.advancer = Objects.requireNonNull(advancer, "advancer must not be null");
    }

    /**
     * @throws BizException 40000 unknown publish number; 40001 the instance is not paused, the node
     *     has no handler, or the state changed concurrently; 40002 the gate is not satisfied yet;
     *     40009 the caller has no data scope on the owning unit
     */
    public ResumeResult resume(String publishNo) {
        String no = publishNo == null ? "" : publishNo.trim();
        if (no.isEmpty()) {
            throw new BizException(BizError.PARAM_INVALID, "发布单号不能为空",
                Map.of("publishNo", "不能为空"));
        }
        ReleaseHeadRow head = queryMapper.selectHeadRow(no);
        if (head == null) {
            throw new BizException(BizError.PARAM_INVALID, "发布单不存在：" + no,
                Map.of("publishNo", no));
        }
        ScopeGuard.assertUnitInScope(head.getUnitId());
        if (head.getInstanceId() == null) {
            throw new BizException(BizError.STATE_NOT_ALLOWED, "该发布单没有执行记录",
                Map.of("publishNo", no));
        }
        PipelineInstance instance = repository.loadInstance(head.getInstanceId());
        if (instance == null) {
            throw new BizException(BizError.STATE_NOT_ALLOWED, "流水线实例不存在",
                Map.of("publishNo", no));
        }
        if (instance.status() != InstanceStatus.PAUSED) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "只有暂停（PAUSED）的实例可以恢复，当前是 " + instance.status(),
                Map.of("publishNo", no, "status", instance.status().name()));
        }
        NodeType blocked = instance.currentNode();
        if (blocked == null || handlerRegistry.handler(blocked) == null) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "节点 " + blocked + " 的处理器尚未交付，恢复没有意义",
                Map.of("publishNo", no, "node", blocked == null ? "" : blocked.name()));
        }
        if (blocked == NodeType.PROD_DEPLOY) {
            // Production resume is not a gate recovery: it needs control:execute-prod and the
            // pre-execution review (freeze / waiver / digest), which this generic endpoint does not
            // do. Refusing here keeps the release control from being bypassed by the P2 button.
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "生产部署（PROD_DEPLOY）必须使用「执行生产」接口恢复（需 control:execute-prod 与执行前复核），"
                    + "本接口只恢复门禁类暂停",
                Map.of("publishNo", no, "node", blocked.name()));
        }
        if (!gateService.verifySatisfied(instance)) {
            String reason = gateService.verifyBlockedReason(instance);
            throw new BizException(BizError.GATE_BLOCKED,
                "门禁尚未满足，不能恢复：" + (reason == null ? "检查或测试还未通过" : reason),
                Map.of("publishNo", no));
        }
        if (!repository.resumeInstance(instance.id(), Instant.now())) {
            throw new BizException(BizError.STATE_NOT_ALLOWED, "实例状态已变化，恢复未生效",
                Map.of("publishNo", no));
        }
        log.info("pipeline resumed: publishNo={}, instanceId={}, node={}", no, instance.id(),
            blocked);
        advancer.advance(instance.id());
        return new ResumeResult(no, instance.pipelineNo(), InstanceStatus.RUNNING.name(), blocked.name());
    }

    /** What the resume endpoint returns: the instance is running again at {@code resumeNode}. */
    public record ResumeResult(String publishNo, String pipelineNo, String status,
                               String resumeNode) {
    }
}