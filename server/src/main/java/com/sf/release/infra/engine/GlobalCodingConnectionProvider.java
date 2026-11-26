package com.sf.release.infra.engine;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.domain.engine.EngineException;
import com.sf.release.domain.resource.CodingConn;
import com.sf.release.domain.resource.CodingScope;
import com.sf.release.infra.crypto.CredentialCipher;
import com.sf.release.infra.repo.CodingConnMapper;
import com.sf.release.infra.repo.po.CodingConnPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Resolves the CODING connection for each engine call: the tenant-wide {@code GLOBAL} row of
 * {@code coding_conn}, decrypted on demand with {@link CredentialCipher}.
 *
 * <p><strong>Lazy on purpose.</strong> Nothing is read at application startup, so the platform
 * boots without a CODING tenant and without outbound I/O; a call made before a connection is
 * configured fails with the normal {@code ENGINE_UNAVAILABLE} path. A credential rotation or a
 * base-URL change is picked up on the next call because the row is re-read every time.
 *
 * <p><strong>Scope.</strong> The tenant-wide {@code GLOBAL} connection plus, since PART-05, the
 * per-subsystem {@code SUBSYSTEM} override resolved through {@link #forUnit(long)}: a start-class
 * engine call carries the unit id, the unit resolves its subsystem, and the override wins over
 * the GLOBAL row. Calls that only carry an {@code externalId} (query/logs/cancel) keep resolving
 * the GLOBAL connection.
 *
 * <p>The plaintext token never appears in a log line or an exception message: a decryption
 * failure is reported without the ciphertext.
 */
@Component
public class GlobalCodingConnectionProvider implements CodingConnectionSource {

    private static final Logger log = LoggerFactory.getLogger(GlobalCodingConnectionProvider.class);

    private final CodingConnMapper connMapper;
    private final CredentialCipher credentialCipher;
    private final com.sf.release.infra.repo.DeployUnitMapper unitMapper;

    public GlobalCodingConnectionProvider(CodingConnMapper connMapper,
                                          CredentialCipher credentialCipher,
                                          com.sf.release.infra.repo.DeployUnitMapper unitMapper) {
        this.connMapper = connMapper;
        this.credentialCipher = credentialCipher;
        this.unitMapper = unitMapper;
    }

    @Override
    public CodingEngine.Connection get() {
        CodingConnPO po = connMapper.selectOne(Wrappers.<CodingConnPO>lambdaQuery()
            .eq(CodingConnPO::getScope, CodingScope.GLOBAL.name())
            .eq(CodingConnPO::getSubsystemId, CodingConn.GLOBAL_SUBSYSTEM_ID));
        if (po == null) {
            log.warn("no GLOBAL CODING connection is configured; engine calls will fail");
            return null;
        }
        return toConnection(po);
    }

    /**
     * PART-05: the connection of a call that carries a unit — the unit's subsystem override when
     * one is stored, else the GLOBAL connection. Both rows are read per call (the same lazy,
     * rotation-friendly contract as {@link #get()}); the resolution order is
     * {@code SUBSYSTEM → GLOBAL}, and an unresolvable unit simply answers the GLOBAL connection
     * (the unit's calls fail exactly like they did before PART-05).
     */
    @Override
    public CodingEngine.Connection forUnit(long unitId) {
        if (unitId <= 0) {
            return get();
        }
        Long subsystemId = null;
        com.sf.release.infra.repo.po.DeployUnitPO unit = unitMapper.selectById(unitId);
        if (unit != null && unit.getSubsystemId() != null) {
            subsystemId = unit.getSubsystemId();
        }
        if (subsystemId == null || subsystemId <= 0) {
            return get();
        }
        CodingConnPO po = connMapper.selectOne(Wrappers.<CodingConnPO>lambdaQuery()
            .eq(CodingConnPO::getScope, CodingScope.SUBSYSTEM.name())
            .eq(CodingConnPO::getSubsystemId, subsystemId));
        if (po == null) {
            return get();
        }
        log.debug("using the SUBSYSTEM CODING connection override: unitId={}, subsystemId={}",
            unitId, subsystemId);
        return toConnection(po);
    }

    private CodingEngine.Connection toConnection(CodingConnPO po) {
        String token;
        try {
            token = credentialCipher.decrypt(po.getCredentialCipher());
        } catch (RuntimeException ex) {
            // Never let the ciphertext or the key reach the message.
            throw EngineException.unavailable("CODING 凭证不可用（解密失败），请检查 "
                + CredentialCipher.KEY_ENVIRONMENT_VARIABLE + " 与连接配置");
        }
        return new CodingEngine.Connection(po.getBaseUrl(), token);
    }
}