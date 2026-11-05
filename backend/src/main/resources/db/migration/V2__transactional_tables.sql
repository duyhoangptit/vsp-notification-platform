-- Transactional tables (data-model.md). History and events are partitioned monthly by created_at (R9).

CREATE TABLE pnh_request_keys (
    client_id         VARCHAR(50)  NOT NULL,
    client_request_id VARCHAR(100) NOT NULL,
    notification_id   UUID         NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (client_id, client_request_id)
);
CREATE INDEX ix_request_keys_created ON pnh_request_keys (created_at);

CREATE TABLE pnh_notifications_history (
    notification_id     UUID          NOT NULL,
    client_id           VARCHAR(50)   NOT NULL,
    client_request_id   VARCHAR(100)  NOT NULL,
    user_id             VARCHAR(100)  NULL,
    recipient_target    VARCHAR(4096) NULL,
    channel_type        VARCHAR(20)   NULL,
    category            VARCHAR(20)   NOT NULL CHECK (category IN ('STANDARD', 'OTP')),
    content_mode        VARCHAR(20)   NOT NULL CHECK (content_mode IN ('TEMPLATE', 'DIRECT')),
    template_code       VARCHAR(100)  NULL,
    template_version    BIGINT        NULL,
    subject             VARCHAR(255)  NULL,
    title               VARCHAR(255)  NULL,
    compiled_content    TEXT          NULL,
    payload_encrypted   BYTEA         NULL,
    push_data           JSONB         NULL,
    expires_at          TIMESTAMPTZ   NULL,
    opt_out_overridden  BOOLEAN       NOT NULL DEFAULT FALSE,
    status              VARCHAR(30)   NOT NULL DEFAULT 'RECEIVED'
        CHECK (status IN ('RECEIVED', 'PROCESSED', 'SENDING', 'SENT', 'DELIVERED', 'FAILED')),
    retry_count         INT           NOT NULL DEFAULT 0,
    provider_key        VARCHAR(50)   NULL,
    provider_message_id VARCHAR(255)  NULL,
    error_code          VARCHAR(50)   NULL,
    error_message       TEXT          NULL,
    result_sequence     INT           NOT NULL DEFAULT 0,
    sending_started_at  TIMESTAMPTZ   NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (notification_id, created_at),
    -- fix U1: NULL user/recipient/channel/content only for rejected ingest messages stored as FAILED
    CONSTRAINT ck_history_rejected_only CHECK (
        (status = 'FAILED' AND error_code IS NOT NULL)
        OR (user_id IS NOT NULL AND recipient_target IS NOT NULL AND channel_type IS NOT NULL AND compiled_content IS NOT NULL))
) PARTITION BY RANGE (created_at);

CREATE INDEX ix_history_client_request ON pnh_notifications_history (client_id, client_request_id);
CREATE INDEX ix_history_user_created ON pnh_notifications_history (user_id, created_at DESC);
CREATE INDEX ix_history_recipient ON pnh_notifications_history (recipient_target);
CREATE INDEX ix_history_status_updated ON pnh_notifications_history (status, updated_at);
CREATE INDEX ix_history_client_created ON pnh_notifications_history (client_id, created_at);
CREATE INDEX ix_history_provider_msg ON pnh_notifications_history (provider_key, provider_message_id);
CREATE INDEX ix_history_in_flight ON pnh_notifications_history (updated_at)
    WHERE status IN ('RECEIVED', 'PROCESSED', 'SENDING');
CREATE INDEX ix_history_inbox ON pnh_notifications_history (user_id, created_at DESC)
    WHERE channel_type = 'PUSH' AND category = 'STANDARD';
CREATE INDEX ix_history_id ON pnh_notifications_history (notification_id);

CREATE TABLE pnh_notification_events (
    id               BIGSERIAL    NOT NULL,
    notification_id  UUID         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    event_type       VARCHAR(30)  NOT NULL CHECK (event_type IN ('STATUS_CHANGED', 'ATTEMPT_FAILED', 'OPT_OUT_OVERRIDDEN',
        'CALLBACK_RECEIVED', 'CALLBACK_IGNORED', 'SWEPT', 'RESUBMISSION_SKIPPED', 'CONTENT_SANITIZED')),
    from_status      VARCHAR(30)  NULL,
    to_status        VARCHAR(30)  NULL,
    attempt          INT          NULL,
    provider_key     VARCHAR(50)  NULL,
    error_code       VARCHAR(50)  NULL,
    error_message    TEXT         NULL,
    provider_response JSONB       NULL,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);
CREATE INDEX ix_events_notification ON pnh_notification_events (notification_id, created_at);

CREATE TABLE pnh_notification_read_states (
    notification_id         UUID         PRIMARY KEY,
    user_id                 VARCHAR(100) NOT NULL,
    is_read                 BOOLEAN      NOT NULL DEFAULT FALSE,
    read_at                 TIMESTAMPTZ  NULL,
    notification_created_at TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_read_states_user_read ON pnh_notification_read_states (user_id, is_read);
CREATE INDEX ix_read_states_user_created ON pnh_notification_read_states (user_id, notification_created_at DESC);

CREATE TABLE pnh_notification_stats_hourly (
    bucket_hour   TIMESTAMPTZ NOT NULL,
    channel_type  VARCHAR(20) NOT NULL,
    client_id     VARCHAR(50) NOT NULL,
    status        VARCHAR(30) NOT NULL,
    count         BIGINT      NOT NULL,
    recomputed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (bucket_hour, channel_type, client_id, status)
);

CREATE TABLE pnh_alert_events (
    id             BIGSERIAL     PRIMARY KEY,
    rule_code      VARCHAR(50)   NOT NULL,
    channel_type   VARCHAR(20)   NULL,
    kind           VARCHAR(20)   NOT NULL CHECK (kind IN ('FIRED', 'RESOLVED')),
    measured_value NUMERIC(10,2) NULL,
    threshold      NUMERIC(10,2) NULL,
    window_minutes INT           NULL,
    occurred_at    TIMESTAMPTZ   NOT NULL,
    recipients     TEXT[]        NOT NULL,
    send_status    VARCHAR(20)   NOT NULL CHECK (send_status IN ('SENT', 'SEND_FAILED', 'NO_RECIPIENTS')),
    send_error     TEXT          NULL,
    details        JSONB         NULL
);
CREATE INDEX ix_alert_events_rule ON pnh_alert_events (rule_code, channel_type, occurred_at DESC);
