package com.sf.release.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.pipeline.PipelineInstance;
import com.sf.release.domain.pipeline.port.PipelineRepository;
import com.sf.release.infra.repo.AdmissionSnapshotMapper;
import com.sf.release.infra.repo.DeployUnitMapper;
import com.sf.release.infra.repo.GateWaiverMapper;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.ReleaseOrderMapper;
import com.sf.release.infra.repo.po.AdmissionSnapshotPO;
import com.sf.release.infra.repo.po.DeployUnitPO;
import com.sf.release.infra.repo.po.GateWaiverPO;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import com.sf.release.infra.repo.po.ReleaseOrderPO;
import com.sf.release.infra.security.ScopeGuard;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The 执行前复核 of one 发布单 (tech design 4.6, requirements J5/J27): the three facts that must
 * still be true when an operator presses 执行生产, compared against the stored admission snapshot.
 *
 * <p><strong>Only what can have changed since the admission.</strong> The window is <em>not</em>
 * recomputed (design 4.6: the release was admitted for its planned time; re-evaluating the window at
 * execution time would turn "the plan was inside the window but we ran late" into a different
 * verdict). The review compares:
 * <ol>
 *   <li><strong>封板</strong>: is a freeze <em>in force now</em> for the unit's system
 *       ({@link FreezeService#active(long, Instant)}, the strict {@code start_at <= now < end_at}
 *       predicate)? A freeze declared for a later time already governed admission and added the
 *       second signature there; it must not block execution today, otherwise every future freeze
 *       would stop the releases planned before it. A freeze that is in force now, on the other hand,
 *       is exactly the "封板生效，需重新审批" case of design 4.6.</li>
 *   <li><strong>豁免</strong>: every waiver id recorded by the snapshot must still be an
 *       {@code APPROVED}, unexpired {@code gate_waiver} row. The snapshot keeps the ids, not the
 *       status, so the review re-reads them (design 4.6: "快照里的豁免必须仍是 APPROVED 且未过期").</li>
 *   <li><strong>镜像</strong>: the image digest of the newest pipeline instance must equal the digest
 *       the snapshot admitted (J27). A mismatch is a <em>hard</em> block (40007) and cannot be
 *       waived.</li>
 * </ol>
 *
 * <p><strong>Read-only.</strong> The review never writes, never changes an item state and never
 * resumes anything; {@link ExecuteProdService} owns the state changes it may imply (a freeze pulls
 * the item back into approval). It does not check the gate either: a gate that became final fails
 * the VERIFY node through {@link GateService}, and the batch boundary re-checks it
 * ({@code ProductionBoundaryProbe}).
 */
@Service
public class ReviewService {

    /** Digest column width; a stored value longer than this cannot be a digest we recorded. */
    static final int DIGEST_MAX_LENGTH = 80;

    private final ReleaseOrderMapper orderMapper;
    private final ReleaseItemMapper itemMapper;
    private final DeployUnitMapper unitMapper;
    private final AdmissionSnapshotMapper snapshotMapper;
    private final GateWaiverMapper waiverMapper;
    private final FreezeService freezeService;
    private final PipelineRepository repository;

    public ReviewService(ReleaseOrderMapper orderMapper,
                         ReleaseItemMapper itemMapper,
                         DeployUnitMapper unitMapper,
                         AdmissionSnapshotMapper snapshotMapper,
                         GateWaiverMapper waiverMapper,
                         FreezeService freezeService,
                         PipelineRepository repository) {
        this.orderMapper = Objects.requireNonNull(orderMapper, "orderMapper must not be null");
        this.itemMapper = Objects.requireNonNull(itemMapper, "itemMapper must not be null");
        this.unitMapper = Objects.requireNonNull(unitMapper, "unitMapper must not be null");
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper,
            "snapshotMapper must not be null");
        this.waiverMapper = Objects.requireNonNull(waiverMapper, "waiverMapper must not be null");
        this.freezeService = Objects.requireNonNull(freezeService,
            "freezeService must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    /**
     * The review of one 发布单 by publish number.
     *
     * @throws BizException 40000 unknown publish number or a missing deployment unit; 40001 the
     *                      release was never submitted (there is no snapshot to review); 40009 no
     *                      data scope on the owning unit
     */
    public ReviewView review(String publishNo) {
        String no = trimToNull(publishNo);
        if (no == null) {
            throw new BizException(BizError.PARAM_INVALID, "发布单号不能为空",
                Map.of("publishNo", "不能为空"));
        }
        ReleaseOrderPO order = orderMapper.selectOne(Wrappers.<ReleaseOrderPO>lambdaQuery()
            .eq(ReleaseOrderPO::getPublishNo, no));
        if (order == null) {
            throw new BizException(BizError.PARAM_INVALID, "发布单不存在：" + no,
                Map.of("publishNo", no));
        }
        ReleaseItemPO item = itemMapper.selectById(order.getItemId());
        if (item == null) {
            throw new BizException(BizError.PARAM_INVALID, "变更项不存在",
                Map.of("publishNo", no, "itemId", order.getItemId()));
        }
        ScopeGuard.assertUnitInScope(item.getUnitId() == null ? 0L : item.getUnitId());
        AdmissionSnapshotPO snapshot = snapshotMapper.selectLatestByOrder(order.getId());
        if (snapshot == null) {
            throw new BizException(BizError.STATE_NOT_ALLOWED,
                "发布单尚未提交生产，没有可复核的准入快照", Map.of("publishNo", no));
        }
        return reviewOf(order, item, snapshot);
    }

    /**
     * The review of one already-loaded 发布单 and snapshot. Package-private: {@link SubmitProdService}
     * renders the same preview on the admission read, and both must ask exactly one question.
     */
    ReviewView reviewOf(ReleaseOrderPO order, ReleaseItemPO item, AdmissionSnapshotPO snapshot) {
        Instant now = Instant.now();
        long subsystemId = subsystemOf(item);
        List<FreezeView> freezes = freezeService.active(subsystemId, now);
        List<Long> freezeIds = freezes.stream().map(FreezeView::id).toList();
        boolean frozen = !freezeIds.isEmpty();

        List<Long> waiverIds = SnapshotJson.longsOf(snapshot.getWaiverIds());
        List<Long> invalidWaivers = new ArrayList<>();
        for (Long waiverId : waiverIds) {
            GateWaiverPO waiver = waiverMapper.selectById(waiverId);
            if (waiver == null
                    || !"APPROVED".equals(waiver.getStatus())
                    || waiver.getExpireAt() == null
                    || !waiver.getExpireAt().isAfter(now)) {
                invalidWaivers.add(waiverId);
            }
        }
        boolean waiverInvalid = !invalidWaivers.isEmpty();

        PipelineInstance instance = repository.latestInstanceOf(item.getId());
        String instanceDigest = instance == null ? null : instance.imageDigest();
        String snapshotDigest = trimToNull(snapshot.getImageDigest());
        boolean digestMatched = instanceDigest != null && snapshotDigest != null
            && instanceDigest.equals(snapshotDigest)
            && instanceDigest.length() <= DIGEST_MAX_LENGTH;

        List<String> reasons = new ArrayList<>(3);
        if (frozen) {
            reasons.add("生产封板生效：" + freezes.stream().map(FreezeView::reason)
                .filter(Objects::nonNull).reduce((a, b) -> a + "；" + b).orElse("封板中")
                + "，需重新审批");
        }
        if (waiverInvalid) {
            reasons.add("准入快照中的豁免已失效（豁免 " + invalidWaivers + " 不再有效或已过期）");
        }
        if (!digestMatched) {
            reasons.add("实例镜像 digest " + Objects.toString(instanceDigest, "缺失")
                + " 与准入快照 digest " + Objects.toString(snapshotDigest, "缺失") + " 不一致");
        }
        return new ReviewView(now, frozen, freezeIds, waiverInvalid, digestMatched,
            !reasons.isEmpty(), reasons);
    }

    /** The system whose freezes apply to the release, resolved through its deployment unit. */
    private long subsystemOf(ReleaseItemPO item) {
        DeployUnitPO unit = unitMapper.selectById(item.getUnitId());
        if (unit == null) {
            throw new BizException(BizError.PARAM_INVALID, "部署单元不存在",
                Map.of("itemId", item.getId(), "unitId", item.getUnitId()));
        }
        return unit.getSubsystemId() == null ? 0L : unit.getSubsystemId();
    }

    /**
     * The 执行前复核 result (design 4.6, {@code GET .../admission} and the execute preconditions).
     * {@code blocked} is the summary of the three checks; {@code reasons} names every failed one so
     * the page and the 40002 body can explain it without re-deriving the predicate.
     */
    public record ReviewView(
        Instant checkedAt,
        boolean frozen,
        List<Long> freezeIds,
        boolean waiverInvalid,
        boolean digestMatched,
        boolean blocked,
        List<String> reasons) {

        public ReviewView {
            freezeIds = freezeIds == null ? List.of() : List.copyOf(freezeIds);
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}