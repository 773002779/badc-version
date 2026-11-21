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
 * <p><strong>Scope.</strong> Phase 1 uses the GLOBAL connection only. The per-subsystem
 * {@code SUBSYSTEM} override needs the instance's subsystem, which the engine SPI call does not
 * carry yet; that selection is deliberately deferred (recorded in the Task 14 report) instead of
 * being guessed from thread context.
 *
 * <p>The plaintext token never appears in a log line or an exception message: a decryption
 * failure is reported without the ciphertext.
 */
@Component
public class GlobalCodingConnectionProvider implements Supplier<CodingEngine.Connection> {

    private static final Logger log = LoggerFactory.getLogger(GlobalCodingConnectionProvider.class);

    private final CodingConnMapper connMapper;
    private final CredentialCipher credentialCipher;

    public GlobalCodingConnectionProvider(CodingConnMapper connMapper,
                                          CredentialCipher credentialCipher) {
        this.connMapper = connMapper;
        this.credentialCipher = credentialCipher;
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