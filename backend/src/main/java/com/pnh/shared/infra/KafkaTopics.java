package com.pnh.shared.infra;

/** Topic names (contracts/kafka/README.md). Message key = user_id on every topic (R5). */
public final class KafkaTopics {
    public static final String INGEST = "pnh.notification.ingest";
    public static final String INGEST_OTP = "pnh.notification.ingest.otp";
    public static final String RESULT = "pnh.notification.result";
    public static final String INGEST_DLT = "pnh.notification.ingest.DLT";

    public static final String HEADER_CLIENT_ID = "client_id";
    public static final String HEADER_CHANNEL_TYPE = "channel_type";
    public static final String HEADER_CATEGORY = "category";
    public static final String HEADER_EVENT_ID = "event_id";
    public static final String HEADER_STATUS = "status";
    public static final String HEADER_SIGNATURE = "pnh-signature";
    public static final String HEADER_ERROR_CODE = "pnh-error-code";
    public static final String HEADER_ERROR_MESSAGE = "pnh-error-message";

    private KafkaTopics() {}
}
