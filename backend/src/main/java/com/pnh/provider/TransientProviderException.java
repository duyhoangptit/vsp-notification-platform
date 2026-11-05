package com.pnh.provider;

import com.pnh.shared.domain.ErrorCode;

/** Retryable provider failure (5xx, 429, timeout, I/O) — research R7. */
public class TransientProviderException extends RuntimeException {

    private final ErrorCode code;

    public TransientProviderException(String message) {
        this(ErrorCode.PROVIDER_UNAVAILABLE, message, null);
    }

    public TransientProviderException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
