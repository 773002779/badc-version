package com.sf.release.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.common.BizError;
import com.sf.release.common.BizException;
import com.sf.release.domain.resource.CodingConn;
import com.sf.release.domain.resource.CodingScope;
import com.sf.release.infra.crypto.CredentialCipher;
import com.sf.release.infra.engine.CodingProbe;
import com.sf.release.infra.engine.ProbeResult;
import com.sf.release.infra.repo.CodingConnMapper;
import com.sf.release.infra.repo.SubsystemMapper;
import com.sf.release.infra.repo.po.CodingConnPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * CODING connection management: the tenant-wide connection plus optional per-subsystem
 * overrides, with the credential encrypted at rest.
 *
 * <p><strong>Secrets.</strong> The plaintext token exists only inside {@link #save} (to
 * encrypt it) and {@link #test(long)} (to hand it to the probe). It is never written to the
 * database, logged, or returned: reads expose a hint such as {@code tok-***}.
 *
 * <p><strong>Upsert.</strong> The natural key is {@code (scope, subsystem_id)}; a second
 * save for the same scope updates the existing row instead of inserting a duplicate. A
 * GLOBAL row stores the sentinel {@code subsystem_id = 0}, because MySQL unique keys treat
 * NULLs as distinct.
 *
 * <p><strong>Probe seam.</strong> The HTTP engine is Task 10. This service depends on
 * {@link CodingProbe} through an {@link ObjectProvider}: the production context stays
 * bootable until the engine task registers its bean, and tests inject a fake probe.
 */
@Service
public class CodingConnAppService {

    private static final Logger log = LoggerFactory.getLogger(CodingConnAppService.class);

    private static final int BASE_URL_MAX_LENGTH = 255;
    private static final int TEAM_MAX_LENGTH = 64;
    private static final int PROJECT_MAX_LENGTH = 64;
    private static final int TOKEN_MAX_LENGTH = 4096;
    private static final int HINT_VISIBLE_CHARACTERS = 4;
    private static final int HINT_MIN_LENGTH = 6;

    private final CodingConnMapper connMapper;
    private final SubsystemMapper subsystemMapper;
    private final CredentialCipher credentialCipher;
    private final ObjectProvider<CodingProbe> probeProvider;

    public CodingConnAppService(CodingConnMapper connMapper,
                                SubsystemMapper subsystemMapper,
                                CredentialCipher credentialCipher,
                                ObjectProvider<CodingProbe> probeProvider) {
        this.connMapper = connMapper;
        this.subsystemMapper = subsystemMapper;
        this.credentialCipher = credentialCipher;
        this.probeProvider = probeProvider;
    }

    /**
     * Upserts the connection identified by {@code (scope, subsystemId)} and returns the
     * stored view (with a masked token hint).
     */
    @Transactional
    public CodingConnView save(CodingScope scope, Long subsystemId, String baseUrl,
                               String team, String project, String token) {
        CodingScope effectiveScope = requireScope(scope);
        long effectiveSubsystemId = requireSubsystemId(effectiveScope, subsystemId);
        if (effectiveScope == CodingScope.SUBSYSTEM
                && subsystemMapper.selectById(effectiveSubsystemId) == null) {
            throw new BizException(BizError.PARAM_INVALID, "所属子系统不存在",
                Map.of("subsystemId", effectiveSubsystemId));
        }
        String normalizedBaseUrl = requireBaseUrl(baseUrl);
        String normalizedTeam = requireText(team, TEAM_MAX_LENGTH, "team", "CODING 团队");
        String normalizedProject = requireText(project, PROJECT_MAX_LENGTH, "project", "CODING 项目");
        String normalizedToken = requireText(token, TOKEN_MAX_LENGTH, "token", "CODING 凭证");

        // Encrypt before touching the database so the plaintext never leaves this method.
        String cipherText = credentialCipher.encrypt(normalizedToken);
        Instant now = Instant.now();

        int rows = updateStoredConnection(effectiveScope, effectiveSubsystemId, normalizedBaseUrl,
            normalizedTeam, normalizedProject, cipherText, now);
        if (rows == 0) {
            CodingConn entity = new CodingConn(null, effectiveScope, effectiveSubsystemId,
                normalizedBaseUrl, normalizedTeam, normalizedProject, cipherText, now, now);
            try {
                connMapper.insert(CodingConnPO.of(entity));
            } catch (DuplicateKeyException ex) {
                // A concurrent first save won the unique-key race; fall back to the update.
                updateStoredConnection(effectiveScope, effectiveSubsystemId, normalizedBaseUrl,
                    normalizedTeam, normalizedProject, cipherText, Instant.now());
            }
        }

        CodingConnView view = get(effectiveScope, effectiveSubsystemId);
        log.info("coding connection saved: id={}, scope={}, subsystemId={}",
            view.id(), view.scope(), view.subsystemId());
        return view;
    }

    /** Reads a connection by natural key; the response never carries the token itself. */
    public CodingConnView get(CodingScope scope, Long subsystemId) {
        CodingScope effectiveScope = requireScope(scope);
        long effectiveSubsystemId = requireSubsystemId(effectiveScope, subsystemId);
        CodingConnPO po = selectStored(effectiveScope, effectiveSubsystemId);
        if (po == null) {
            throw notFound(effectiveScope, effectiveSubsystemId);
        }
        return toView(po);
    }

    /**
     * Decrypts the stored credential and asks the injected probe to check the connection.
     * A {@link ProbeResult.Status#FAILED} result is propagated as data (its message is the
     * operator-facing reason), not converted into an exception.
     */
    public ProbeResult test(long id) {
        CodingConnPO po = connMapper.selectById(id);
        if (po == null) {
            throw new BizException(BizError.PARAM_INVALID, "CODING 连接不存在",
                Map.of("id", id));
        }
        CodingProbe probe = probeProvider.getIfAvailable();
        if (probe == null) {
            throw new BizException(BizError.ENGINE_UNAVAILABLE, "CODING 探测引擎不可用",
                Map.of("id", id));
        }
        String token = credentialCipher.decrypt(po.getCredentialCipher());
        ProbeResult result = probe.probe(po.toDomain(), token);
        log.info("coding connection probe finished: id={}, status={}", id, result.status());
        return result;
    }

    private int updateStoredConnection(CodingScope scope, long subsystemId, String baseUrl,
                                       String team, String project, String cipherText,
                                       Instant updatedAt) {
        return connMapper.update(null, Wrappers.<CodingConnPO>lambdaUpdate()
            .eq(CodingConnPO::getScope, scope.name())
            .eq(CodingConnPO::getSubsystemId, subsystemId)
            .set(CodingConnPO::getBaseUrl, baseUrl)
            .set(CodingConnPO::getTeam, team)
            .set(CodingConnPO::getProject, project)
            .set(CodingConnPO::getCredentialCipher, cipherText)
            .set(CodingConnPO::getUpdatedAt, updatedAt));
    }

    private CodingConnPO selectStored(CodingScope scope, long subsystemId) {
        return connMapper.selectOne(Wrappers.<CodingConnPO>lambdaQuery()
            .eq(CodingConnPO::getScope, scope.name())
            .eq(CodingConnPO::getSubsystemId, subsystemId));
    }

    private static CodingScope requireScope(CodingScope scope) {
        if (scope == null) {
            throw new BizException(BizError.PARAM_INVALID, "连接范围不能为空",
                Map.of("scope", "不能为空"));
        }
        return scope;
    }

    private static long requireSubsystemId(CodingScope scope, Long subsystemId) {
        if (scope == CodingScope.GLOBAL) {
            return CodingConn.GLOBAL_SUBSYSTEM_ID;
        }
        if (subsystemId == null || subsystemId <= 0) {
            throw new BizException(BizError.PARAM_INVALID, "子系统连接必须指定子系统",
                Map.of("subsystemId", String.valueOf(subsystemId)));
        }
        return subsystemId;
    }

    private static String requireBaseUrl(String raw) {
        String value = requireText(raw, BASE_URL_MAX_LENGTH, "baseUrl", "CODING 地址");
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new BizException(BizError.PARAM_INVALID, "CODING 地址必须以 http:// 或 https:// 开头",
                Map.of("baseUrl", "协议不合法"));
        }
        return value;
    }

    private static String requireText(String raw, int maxLength, String field, String label) {
        String value = trimToNull(raw);
        if (value == null) {
            throw new BizException(BizError.PARAM_INVALID, label + "不能为空",
                Map.of(field, "不能为空"));
        }
        if (value.length() > maxLength) {
            throw new BizException(BizError.PARAM_INVALID,
                label + "长度不能超过 " + maxLength, Map.of(field, "长度超限"));
        }
        return value;
    }

    private CodingConnView toView(CodingConnPO po) {
        String token = credentialCipher.decrypt(po.getCredentialCipher());
        return new CodingConnView(
            po.getId(),
            po.getScope(),
            po.getSubsystemId() == null ? CodingConn.GLOBAL_SUBSYSTEM_ID : po.getSubsystemId(),
            po.getBaseUrl(),
            po.getTeam(),
            po.getProject(),
            hint(token),
            po.getCreatedAt(),
            po.getUpdatedAt());
    }

    /**
     * Shows at most the first four characters. Short tokens are fully masked, otherwise the
     * hint itself would be the secret: {@code tok-123} becomes {@code tok-***}.
     */
    private static String hint(String token) {
        if (token == null || token.length() <= HINT_MIN_LENGTH) {
            return "***";
        }
        return token.substring(0, HINT_VISIBLE_CHARACTERS) + "***";
    }

    private static BizException notFound(CodingScope scope, long subsystemId) {
        return new BizException(BizError.PARAM_INVALID, "CODING 连接不存在",
            Map.of("scope", scope.name(), "subsystemId", subsystemId));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Read model exposed to the web layer: a masked hint replaces the credential. */
    public record CodingConnView(
        long id,
        String scope,
        long subsystemId,
        String baseUrl,
        String team,
        String project,
        String tokenHint,
        Instant createdAt,
        Instant updatedAt) {
    }
}
