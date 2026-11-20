package com.sf.release.domain.engine;

import com.sf.release.common.BizError;
import com.sf.release.common.BizException;

/**
 * An engine call that did not produce a usable result.
 *
 * <p>The two catalogue codes are fixed: a 5xx/IO/configuration failure is
 * {@link BizError#ENGINE_UNAVAILABLE} ({@code 50001}); a read timeout is
 * {@link BizError#ENGINE_TIMEOUT} ({@code 50002}). Messages must never contain a credential;
 * adapters redact the token before building one.
 */
public class EngineException extends BizException {

    public EngineException(BizError error, String message) {
        super(error, message);
    }

    public static EngineException unavailable(String message) {
        return new EngineException(BizError.ENGINE_UNAVAILABLE, message);
    }

    public static EngineException timeout(String message) {
        return new EngineException(BizError.ENGINE_TIMEOUT, message);
    }
}