package com.sf.release.application.settings;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.application.audit.AuditCommand;
import com.sf.release.application.audit.AuditService;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.audit.AuditAction;
import com.sf.release.domain.audit.AuditObjectType;
import com.sf.release.domain.pipeline.BatchOrder;
import com.sf.release.domain.pipeline.UnitPipelineConfig;
import com.sf.release.infra.repo.DeployUnitMapper;
import com.sf.release.infra.repo.UnitPipelineConfigMapper;
import com.sf.release.infra.repo.po.UnitPipelineConfigPO;
import com.sf.release.infra.security.ScopeGuard;
import com.sf.release.infra.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The P6-C unit-level pipeline configuration (design §1/§2/§3): the environment-check switch, the
 * build/deploy timeouts and the batch order.
 *
 * <p><strong>Defaults are the absent row.</strong> A unit that never saved the page has no row and
 * therefore behaves exactly as before P6-C: environment checks on, build 60 minutes, deploy 8
 * minutes, batches forward. {@link #view(long)} reports that as {@code configured:false}; a save
 * upserts one row and reports {@code configured:true}.
 *
 * <p><strong>No cache, on purpose.</strong> The consumers are the publish/rerun creation of a new
 * instance and the per-trigger handler read; both must see a save on their next call, and the row is
 * read a handful of times per minute. The handler-side read goes through
 * {@code PipelineRepository.pipelineConfigOf(instanceId)} so the domain never depends on MyBatis.
 *
 * <p><strong>Validation is the service's job.</strong> The web DTO carries strings only (it must not
 * import a domain enum); the service parses them and rejects a non-numeric/out-of-range timeout, an
 * unknown batch order or a non-boolean switch with {@link BizError#PARAM_INVALID} (40000). The
 * response view carries the machine value <em>and</em> the Chinese label, so the page never has to
 * translate a domain enum.
 *
 * <p><strong>Scope and audit.</strong> Both the read and the write call {@link ScopeGuard} (a
 * non-ALL caller is limited to its {@code user_scope} units) and the write records one
 * {@code SETTING_UPDATE} row with object type {@code UNIT}, object id the unit id, the whole
 * configuration as before/after and {@code scope_unit_id = unitId}.
 */
@Service
public class PipelineConfigService {

    private static final Logger log = LoggerFactory.getLogger(PipelineConfigService.class);

    private final UnitPipelineConfigMapper configMapper;
    private final DeployUnitMapper unitMapper;
    private final AuditService audit;

    public PipelineConfigService(UnitPipelineConfigMapper configMapper,
                                 DeployUnitMapper unitMapper,
                                 AuditService audit) {
        this.configMapper = configMapper;
        this.unitMapper = unitMapper;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reads

    /**
     * The effective configuration of a unit, or {@code null} when the unit never saved it (or the
     * row cannot be read). Consumers treat {@code null} as the documented defaults; this method is
     * deliberately scope-free because it serves the publish/rerun path, which has already checked
     * the caller's scope before it creates an instance.
     */
    public UnitPipelineConfig configOfUnit(long unitId) {
        if (unitId <= 0) {
            return null;
        }
        try {
            UnitPipelineConfigPO po = configMapper.selectById(unitId);
            return po == null ? null : po.toDomain();
        } catch (RuntimeException ex) {
            log.warn("unit pipeline config could not be read; the defaults are used: unitId={},"
                + " reason={}", unitId, ex.getMessage());
            return null;
        }
    }

    /**
     * The configuration page view: the stored row, or the documented defaults with
     * {@code configured:false}.
     *
     * @throws BizException 40000 the unit does not exist; 40009 the caller has no data scope
     */
    public UnitPipelineConfigView view(long unitId) {
        ScopeGuard.assertUnitInScope(unitId);
        requireUnit(unitId);
        return readView(unitId);
    }

    // ------------------------------------------------------------------ writes

    /**
     * Full replacement of a unit's pipeline configuration.
     *
     * <p>The fields arrive as strings because the web DTO is string-only; each one is trimmed,
     * parsed and range-checked here. A successful save writes one {@code SETTING_UPDATE} audit row
     * with the whole configuration before/after.
     *
     * @throws BizException 40000 an invalid value or a missing unit; 40009 no data scope
     */
    public UnitPipelineConfigView save(long unitId, String envChecksEnabled,
                                       String buildTimeoutMinutes, String deployTimeoutMinutes,
                                       String batchOrder) {
        ScopeGuard.assertUnitInScope(unitId);
        requireUnit(unitId);
        boolean envEnabled = parseEnvChecksEnabled(envChecksEnabled);
        int buildMinutes = parseMinutes(buildTimeoutMinutes, "buildTimeoutMinutes", "构建超时",
            UnitPipelineConfig.MIN_BUILD_TIMEOUT_MINUTES,
            UnitPipelineConfig.MAX_BUILD_TIMEOUT_MINUTES);
        int deployMinutes = parseMinutes(deployTimeoutMinutes, "deployTimeoutMinutes",
            "部署超时", UnitPipelineConfig.MIN_DEPLOY_TIMEOUT_MINUTES,
            UnitPipelineConfig.MAX_DEPLOY_TIMEOUT_MINUTES);
        BatchOrder order = requireBatchOrder(batchOrder);

        UnitPipelineConfigView before = readView(unitId);
        String actor = UserContext.current().userId();
        Instant now = Instant.now();
        upsert(unitId, envEnabled, buildMinutes, deployMinutes, order, actor, now);
        UnitPipelineConfigView saved = readView(unitId);

        audit.record(AuditCommand.of(AuditAction.SETTING_UPDATE, String.valueOf(unitId),
                "部署单元流水线配置（" + unitId + "）",
                "更新部署单元流水线配置（单元 " + unitId + "）")
            .objectType(AuditObjectType.UNIT)
            .before(auditValues(before))
            .after(auditValues(saved))
            .scopeUnitId(unitId)
            .build());
        log.info("unit pipeline config saved: unitId={}, envChecksEnabled={}, buildTimeoutMinutes={},"
                + " deployTimeoutMinutes={}, batchOrder={}, by={}", unitId,
            saved.envChecksEnabled(), saved.buildTimeoutMinutes(), saved.deployTimeoutMinutes(),
            saved.batchOrder(), actor);
        return saved;
    }

    // ------------------------------------------------------------------ persistence

    private void upsert(long unitId, boolean envEnabled, int buildMinutes, int deployMinutes,
                        BatchOrder order, String actor, Instant now) {
        int rows = configMapper.update(null, Wrappers.<UnitPipelineConfigPO>lambdaUpdate()
            .eq(UnitPipelineConfigPO::getUnitId, unitId)
            .set(UnitPipelineConfigPO::getEnvChecksEnabled, envEnabled)
            .set(UnitPipelineConfigPO::getBuildTimeoutMinutes, buildMinutes)
            .set(UnitPipelineConfigPO::getDeployTimeoutMinutes, deployMinutes)
            .set(UnitPipelineConfigPO::getBatchOrder, order.name())
            .set(UnitPipelineConfigPO::getUpdatedBy, actor)
            .set(UnitPipelineConfigPO::getUpdatedAt, now));
        if (rows > 0) {
            return;
        }
        // No row yet: the design does not insert on unit creation, so the first save creates it.
        UnitPipelineConfigPO po = new UnitPipelineConfigPO();
        po.setUnitId(unitId);
        po.setEnvChecksEnabled(envEnabled);
        po.setBuildTimeoutMinutes(buildMinutes);
        po.setDeployTimeoutMinutes(deployMinutes);
        po.setBatchOrder(order.name());
        po.setUpdatedBy(actor);
        po.setUpdatedAt(now);
        configMapper.insert(po);
    }

    private UnitPipelineConfigView readView(long unitId) {
        UnitPipelineConfigPO po = configMapper.selectById(unitId);
        if (po == null) {
            return defaultsView(unitId);
        }
        UnitPipelineConfig config = po.toDomain();
        return new UnitPipelineConfigView(unitId, config.envChecksEnabled(),
            config.buildTimeoutMinutes(), config.deployTimeoutMinutes(),
            config.batchOrder().name(), config.batchOrder().label(), true, po.getUpdatedBy(),
            po.getUpdatedAt());
    }

    private static UnitPipelineConfigView defaultsView(long unitId) {
        return new UnitPipelineConfigView(unitId, true,
            UnitPipelineConfig.DEFAULT_BUILD_TIMEOUT_MINUTES,
            UnitPipelineConfig.DEFAULT_DEPLOY_TIMEOUT_MINUTES, BatchOrder.FORWARD.name(),
            BatchOrder.FORWARD.label(), false, null, null);
    }

    private void requireUnit(long unitId) {
        if (unitMapper.selectById(unitId) == null) {
            throw new BizException(BizError.PARAM_INVALID, "部署单元不存在",
                Map.of("unitId", unitId));
        }
    }

    // ------------------------------------------------------------------ validation / audit values

    private static boolean parseEnvChecksEnabled(String raw) {
        String value = trimToNull(raw);
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new BizException(BizError.PARAM_INVALID, "环境级检查开关必须是 true 或 false",
            Map.of("envChecksEnabled", raw == null ? "" : raw));
    }

    private static int parseMinutes(String raw, String field, String label, int min, int max) {
        String value = trimToNull(raw);
        if (value == null) {
            throw new BizException(BizError.PARAM_INVALID, label + "不能为空",
                Map.of(field, "不能为空"));
        }
        int minutes;
        try {
            minutes = Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new BizException(BizError.PARAM_INVALID, label + "必须是整数分钟数",
                Map.of(field, value));
        }
        if (minutes < min || minutes > max) {
            throw new BizException(BizError.PARAM_INVALID,
                label + "必须在 " + min + "-" + max + " 分钟之间", Map.of(field, value));
        }
        return minutes;
    }

    private static BatchOrder requireBatchOrder(String raw) {
        BatchOrder order = BatchOrder.parse(raw);
        if (order == null) {
            throw new BizException(BizError.PARAM_INVALID, "批次顺序只能是 FORWARD 或 REVERSE",
                Map.of("batchOrder", raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT)));
        }
        return order;
    }

    /** The whole configuration as the audit row's before/after (the design's "整段"). */
    private static Map<String, Object> auditValues(UnitPipelineConfigView view) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("envChecksEnabled", view.envChecksEnabled());
        values.put("buildTimeoutMinutes", view.buildTimeoutMinutes());
        values.put("deployTimeoutMinutes", view.deployTimeoutMinutes());
        values.put("batchOrder", view.batchOrder());
        values.put("configured", view.configured());
        return values;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ------------------------------------------------------------------ views

    /**
     * The configuration page's view. {@code batchOrder} is the stable machine value
     * ({@code FORWARD}/{@code REVERSE}) and {@code batchOrderLabel} the Chinese label the page
     * shows; {@code configured} tells the page whether a stored row exists.
     */
    public record UnitPipelineConfigView(
        long unitId,
        boolean envChecksEnabled,
        int buildTimeoutMinutes,
        int deployTimeoutMinutes,
        String batchOrder,
        String batchOrderLabel,
        boolean configured,
        String updatedBy,
        Instant updatedAt) {
    }
}