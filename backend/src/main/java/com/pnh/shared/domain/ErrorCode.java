package com.pnh.shared.domain;

/** Stable error codes (contracts: client-api Problem.code, console-api Problem). */
public enum ErrorCode {
    // authentication / authorization
    UNAUTHORIZED(401, "Authentication required"),
    CLIENT_INACTIVE(403, "Client is inactive"),
    SCOPE_DENIED(403, "Client scope does not allow this operation"),
    FORBIDDEN(403, "Operation not allowed for your role"),
    RATE_LIMITED(429, "Rate limit exceeded"),
    // intake validation
    VALIDATION_ERROR(422, "Validation failed"),
    CONTENT_MODE_CONFLICT(422, "Exactly one of template_code or content is required"),
    MISSING_CONTENT_FIELD(422, "A required content field is missing for this channel"),
    DIRECT_CONTENT_NOT_ALLOWED(422, "Direct content is not allowed"),
    TEMPLATE_NOT_FOUND(422, "Template not found"),
    TEMPLATE_INACTIVE(422, "Template is inactive"),
    TEMPLATE_CHANNEL_MISMATCH(422, "Template channel does not match the request channel"),
    MISSING_VARIABLES(422, "Template variables are missing"),
    INVALID_RECIPIENT(422, "Recipient is invalid"),
    CONTENT_TOO_LONG(422, "Content exceeds the channel limit"),
    OTP_EXPIRY_REQUIRED(422, "OTP notifications require an expiry"),
    OTP_VALUE_REQUIRED(422, "Direct OTP notifications require otp_value"),
    UNSUPPORTED_CHANNEL(422, "Channel is not supported"),
    BULK_LIMIT_EXCEEDED(422, "Too many items in bulk request"),
    NOT_FOUND(404, "Not found"),
    // console
    VERSION_CONFLICT(409, "The record was modified by someone else"),
    TEMPLATE_CODE_EXISTS(409, "Template code already exists"),
    RECIPIENT_EXISTS(409, "Recipient already exists"),
    INVALID_PLACEHOLDER(422, "Invalid placeholder"),
    INVALID_FIELD(422, "Invalid field"),
    MISSING_FIELD(422, "Missing required field"),
    // delivery (track / result)
    USER_OPTED_OUT(422, "User opted out of this channel"),
    OTP_EXPIRED(422, "OTP expired before delivery"),
    CHANNEL_NOT_CONFIGURED(422, "Channel has no active provider configuration"),
    DELIVERY_STATE_UNKNOWN(422, "Delivery state unknown after interruption; not re-sent"),
    PROCESSING_TIMEOUT(422, "Processing timed out"),
    PROVIDER_UNAVAILABLE(503, "Provider unavailable"),
    PROVIDER_TIMEOUT(504, "Provider timed out"),
    PROVIDER_REJECTED(422, "Provider rejected the message"),
    PROVIDER_AUTH_FAILED(502, "Provider authentication failed"),
    BOUNCED(422, "Message bounced"),
    // generic
    INTERNAL_ERROR(500, "Internal error");

    private final int httpStatus;
    private final String title;

    ErrorCode(int httpStatus, String title) {
        this.httpStatus = httpStatus;
        this.title = title;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String title() {
        return title;
    }
}
