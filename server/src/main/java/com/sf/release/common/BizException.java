package com.sf.release.common;

/**
 * A business failure expressed as a {@link BizError} plus optional payload
 * (for example the current status or the missing gate items).
 */
public class BizException extends RuntimeException {

    private final BizError error;
    private final Object payload;

    public BizException(BizError error) {
        this(error, error.getMessage(), null);
    }

    public BizException(BizError error, String message) {
        this(error, message, null);
    }

    public BizException(BizError error, String message, Object payload) {
        super(message == null || message.isBlank() ? error.getMessage() : message);
        this.error = error;
        this.payload = payload;
    }

    public BizError getError() {
        return error;
    }

    public Object getPayload() {
        return payload;
    }
}