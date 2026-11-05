package com.pnh.provider;

import com.pnh.shared.domain.ErrorCode;

/** Non-retryable provider failure mapped to a stable error code. */
public class PermanentProviderException extends RuntimeException {

    private final ErrorCode code;

    public PermanentProviderException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public PermanentProviderException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
