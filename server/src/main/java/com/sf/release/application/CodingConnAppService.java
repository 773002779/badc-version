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
import java.util.Objects;

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
    private static final int PROBE_MESSAGE_MAX_LENGTH = 500;
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
     * stored view (with a masked token hint). {@code scope} is the API string
     * {@code "GLOBAL"} / {@code "SUBSYSTEM"}, parsed case-insensitively, so the web layer
     * never depends on the domain enum.
     */
    @Transactional
    public CodingConnView save(String scope, Long subsystemId, String baseUrl,
                               String team, String project, String token) {
        CodingScope effectiveScope = parseScope(scope);
        long effectiveSubsystemId = requireSubsystemId(effectiveScope, subsystemId);
        if (effectiveScope == CodingScope.SUBSYSTEM
                && subsystemMapper.selectById(effectiveSubsystemId) == null) {
            throw new BizException(BizError.PARAM_INVALID, "所属系统不存在",
                Map.of("subsystemId", effectiveSubsystemId));
        }
        String normalizedBaseUrl = requireBaseUrl(baseUrl);
        String normalizedTeam = requireText(team, TEAM_MAX_LENGTH, "team", "CODING 团队");
        String normalizedProject = requireText(project, PROJECT_MAX_LENGTH, "project", "CODING 项目");

        // An empty token means "keep the stored credential". The page only ever knows the masked
        // hint, so a routine edit (new project, new base URL) must not force the operator to
        // paste the secret again; a rotation is the case where a token IS supplied. The first
        // save still requires one.
        CodingConnPO stored = selectStored(effectiveScope, effectiveSubsystemId);
        String normalizedToken = optionalText(token, TOKEN_MAX_LENGTH, "token", "CODING 凭证");
        String cipherText;
        if (normalizedToken == null) {
            if (stored == null || stored.getCredentialCipher() == null) {
                throw new BizException(BizError.PARAM_INVALID, "CODING 凭证不能为空",
                    Map.of("token", "不能为空"));
            }
            cipherText = stored.getCredentialCipher();
        } else {
            // Encrypt before touching the database so the plaintext never leaves this method.
            cipherText = credentialCipher.encrypt(normalizedToken);
        }
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

        CodingConnView view = loadStoredView(effectiveScope, effectiveSubsystemId);
        log.info("coding connection saved: id={}, scope={}, subsystemId={}",
            view.id(), view.scope(), view.subsystemId());
        return view;
    }

    /**
     * Reads a connection by natural key; {@code scope} is the API string. The response
     * never carries the token itself.
     */
    public CodingConnView get(String scope, Long subsystemId) {
        CodingScope effectiveScope = parseScope(scope);
        long effectiveSubsystemId = requireSubsystemId(effectiveScope, subsystemId);
        return loadStoredView(effectiveScope, effectiveSubsystemId);
    }

    /**
     * Decrypts the stored credential and probes one stored connection by id. A failed probe is
     * propagated as data (its message is the operator-facing reason), not converted into an
     * exception. The outcome is written back to the row.
     */
    public CodingProbeView test(long id) {
        CodingConnPO po = connMapper.selectById(id);
        if (po == null) {
            throw new BizException(BizError.PARAM_INVALID, "CODING 连接不存在",
                Map.of("id", id));
        }
        String token = credentialCipher.decrypt(po.getCredentialCipher());
        ProbeResult result = requireProbe(id).probe(po.toDomain(), token);
        Instant at = Instant.now();
        recordProbe(id, result, at);
        log.info("coding connection probe finished: id={}, status={}", id, result.status());
        return new CodingProbeView(result.status().name(), result.message(),
            result.buildJobs(), result.deployJobs(), at, true);
    }

    /**
     * Tests the values currently on screen. Blank fields fall back to the stored GLOBAL
     * connection, so editing only the base URL does not require retyping the team, project or
     * token — and a wrong draft token never lands in the database.
     *
     * <p><strong>When the result is recorded.</strong> Only when the resolved values are exactly
     * the saved connection ({@code testForm} with an untouched form). Testing a draft leaves
     * {@code last_probe_*} and {@code updated_at} untouched, and the page says so. Comparing
     * values is more honest than a "persist" flag the caller could set while testing something
     * else.
     */
    public CodingProbeView testForm(String baseUrl, String team, String project, String token) {
        CodingConnPO stored = selectStored(CodingScope.GLOBAL, CodingConn.GLOBAL_SUBSYSTEM_ID);
        String effectiveBaseUrl = firstNonBlank(baseUrl, stored == null ? null : stored.getBaseUrl());
        String effectiveTeam = firstNonBlank(team, stored == null ? null : stored.getTeam());
        String effectiveProject = firstNonBlank(project, stored == null ? null : stored.getProject());
        String effectiveToken = optionalText(token, TOKEN_MAX_LENGTH, "token", "CODING 凭证");
        if (effectiveToken == null && stored != null) {
            effectiveToken = credentialCipher.decrypt(stored.getCredentialCipher());
        }
        CodingConn draft = new CodingConn(
            stored == null ? null : stored.getId(),
            CodingScope.GLOBAL,
            CodingConn.GLOBAL_SUBSYSTEM_ID,
            requireBaseUrl(effectiveBaseUrl),
            requireText(effectiveTeam, TEAM_MAX_LENGTH, "team", "CODING 团队"),
            requireText(effectiveProject, PROJECT_MAX_LENGTH, "project", "CODING 项目"),
            null, null, null);
        ProbeResult result = requireProbe(null).probe(draft, effectiveToken);
        Instant at = Instant.now();
        boolean persisted = stored != null && matchesStored(stored, draft, effectiveToken);
        if (persisted) {
            recordProbe(stored.getId(), result, at);
        }
        log.info("coding connection form probe finished: status={}, persisted={}",
            result.status(), persisted);
        return new CodingProbeView(result.status().name(), result.message(),
            result.buildJobs(), result.deployJobs(), at, persisted);
    }

    /**
     * The tenant-wide connection, or {@code null} when this platform has none yet. The settings
     * screen reads this on load and must not treat "not configured yet" as an error.
     */
    public CodingConnView globalOrNull() {
        CodingConnPO po = selectStored(CodingScope.GLOBAL, CodingConn.GLOBAL_SUBSYSTEM_ID);
        return po == null ? null : toView(po);
    }

    private CodingProbe requireProbe(Long id) {
        CodingProbe probe = probeProvider.getIfAvailable();
        if (probe == null) {
            throw new BizException(BizError.ENGINE_UNAVAILABLE, "CODING 探测引擎不可用",
                id == null ? Map.of() : Map.of("id", id));
        }
        return probe;
    }

    private CodingConnView loadStoredView(CodingScope scope, long subsystemId) {
        CodingConnPO po = selectStored(scope, subsystemId);
        if (po == null) {
            throw notFound(scope, subsystemId);
        }
        return toView(po);
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

    /** Writes the last probe outcome; only the recent one is kept. */
    private void recordProbe(long id, ProbeResult result, Instant at) {
        connMapper.update(null, Wrappers.<CodingConnPO>lambdaUpdate()
            .eq(CodingConnPO::getId, id)
            .set(CodingConnPO::getLastProbeAt, at)
            .set(CodingConnPO::getLastProbeOk, result.isOk())
            .set(CodingConnPO::getLastProbeMessage,
                truncate(result.message(), PROBE_MESSAGE_MAX_LENGTH)));
    }

    /** True when the tested values are exactly the stored row (including the plaintext token). */
    private boolean matchesStored(CodingConnPO stored, CodingConn draft, String token) {
        if (stored == null) {
            return false;
        }
        if (!Objects.equals(trimToNull(stored.getBaseUrl()), draft.baseUrl())
                || !Objects.equals(trimToNull(stored.getTeam()), draft.team())
                || !Objects.equals(trimToNull(stored.getProject()), draft.project())) {
            return false;
        }
        try {
            return Objects.equals(
                credentialCipher.decrypt(stored.getCredentialCipher()), token);
        } catch (RuntimeException ex) {
            // A credential that cannot be decrypted is not the one that was just tested.
            return false;
        }
    }

    private static CodingScope parseScope(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            throw new BizException(BizError.PARAM_INVALID, "连接范围不能为空",
                Map.of("scope", "不能为空"));
        }
        try {
            return CodingScope.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BizException(BizError.PARAM_INVALID, "连接范围不合法：" + value,
                Map.of("scope", value));
        }
    }

    private static long requireSubsystemId(CodingScope scope, Long subsystemId) {
        if (scope == CodingScope.GLOBAL) {
            return CodingConn.GLOBAL_SUBSYSTEM_ID;
        }
        if (subsystemId == null || subsystemId <= 0) {
            throw new BizException(BizError.PARAM_INVALID, "系统连接必须指定系统",
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
            po.getUpdatedAt(),
            po.getLastProbeAt(),
            po.getLastProbeOk(),
            po.getLastProbeMessage());
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

    /** Same as {@link #requireText} but a blank value yields {@code null} instead of failing. */
    private static String optionalText(String raw, int maxLength, String field, String label) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        if (value.length() > maxLength) {
            throw new BizException(BizError.PARAM_INVALID,
                label + "长度不能超过 " + maxLength, Map.of(field, "长度超限"));
        }
        return value;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        String value = trimToNull(preferred);
        return value == null ? trimToNull(fallback) : value;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
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
        Instant updatedAt,
        Instant lastProbeAt,
        Boolean lastProbeOk,
        String lastProbeMessage) {
    }

    /**
     * Probe outcome exposed to the web layer. The {@code infra.engine.ProbeResult} enum is
     * flattened to its name so no domain or infra type appears in an application API.
     *
     * <p>{@code persisted} says whether this result became the row's "last verified" state: the
     * page can then be honest about a draft test instead of implying it was recorded.
     */
    public record CodingProbeView(String status, String message, int buildJobs, int deployJobs,
                                  Instant at, boolean persisted) {

        public boolean ok() {
            return "OK".equals(status);
        }
    }
}
