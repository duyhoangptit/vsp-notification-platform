-- Configuration tables (data-model.md). Every table carries the config audit columns (FR-032, R17).
-- DB defaults for timestamps are a safety net only: the application always sets them from the Clock bean (R29).

CREATE TABLE pnh_clients (
    client_id                   VARCHAR(50)  PRIMARY KEY CHECK (client_id ~ '^[a-z0-9_-]{3,50}$'),
    client_name                 VARCHAR(100) NOT NULL,
    client_secret_hash          VARCHAR(255) NOT NULL,
    rate_limit_per_min          INT          NOT NULL DEFAULT 1000 CHECK (rate_limit_per_min > 0),
    scopes                      VARCHAR(100) NOT NULL DEFAULT 'SEND,TRACK',
    allow_direct_content        BOOLEAN      NOT NULL DEFAULT TRUE,
    kafka_signing_key_encrypted BYTEA        NULL,
    status                      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by VARCHAR(100) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by VARCHAR(100) NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0
);

CREATE TABLE pnh_templates (
    template_code         VARCHAR(100) PRIMARY KEY CHECK (template_code ~ '^[A-Z0-9_]{3,100}$'),
    template_name         VARCHAR(150) NOT NULL,
    channel_type          VARCHAR(20)  NOT NULL,
    category              VARCHAR(20)  NOT NULL DEFAULT 'STANDARD' CHECK (category IN ('STANDARD', 'OTP')),
    is_critical           BOOLEAN      NOT NULL DEFAULT FALSE,
    otp_variable          VARCHAR(64)  NULL,
    expiry_variable       VARCHAR(64)  NULL,
    subject_template      VARCHAR(255) NULL,
    title_template        VARCHAR(255) NULL,
    body_template         TEXT         NOT NULL,
    external_template_ref JSONB        NULL,
    required_variables    TEXT[]       NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by VARCHAR(100) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by VARCHAR(100) NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0
);

CREATE TABLE pnh_user_preferences (
    id           BIGSERIAL    PRIMARY KEY,
    user_id      VARCHAR(100) NOT NULL,
    channel_type VARCHAR(20)  NOT NULL,
    is_enabled   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by VARCHAR(100) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by VARCHAR(100) NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    UNIQUE (user_id, channel_type)
);

CREATE TABLE pnh_channel_configs (
    id                BIGSERIAL    PRIMARY KEY,
    channel_type      VARCHAR(20)  NOT NULL,
    provider_key      VARCHAR(50)  NOT NULL,
    is_active         BOOLEAN      NOT NULL DEFAULT FALSE,
    settings          JSONB        NOT NULL,
    secrets_encrypted BYTEA        NULL,
    secrets_key_id    VARCHAR(100) NULL,
    last_test_at      TIMESTAMPTZ  NULL,
    last_test_result  VARCHAR(500) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by VARCHAR(100) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by VARCHAR(100) NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    UNIQUE (channel_type, provider_key)
);
CREATE UNIQUE INDEX ux_channel_configs_one_active ON pnh_channel_configs (channel_type) WHERE is_active;

CREATE TABLE pnh_operators (
    username      VARCHAR(100) PRIMARY KEY,
    display_name  VARCHAR(150),
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(30)  NOT NULL CHECK (role IN ('ADMIN', 'TEMPLATE_EDITOR', 'SUPPORT_VIEWER')),
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by VARCHAR(100) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by VARCHAR(100) NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0
);

CREATE TABLE pnh_alert_recipients (
    id           BIGSERIAL    PRIMARY KEY,
    email        VARCHAR(254) NOT NULL UNIQUE,
    display_name VARCHAR(150) NULL,
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by VARCHAR(100) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by VARCHAR(100) NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0
);

CREATE TABLE pnh_alert_rules (
    rule_code        VARCHAR(50)   PRIMARY KEY,
    enabled          BOOLEAN       NOT NULL DEFAULT TRUE,
    threshold        NUMERIC(10,2) NOT NULL CHECK (threshold >= 0),
    window_minutes   INT           NOT NULL CHECK (window_minutes BETWEEN 1 AND 1440),
    min_sample       INT           NULL,
    cooldown_minutes INT           NOT NULL DEFAULT 30 CHECK (cooldown_minutes BETWEEN 5 AND 1440),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by VARCHAR(100) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by VARCHAR(100) NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0
);

-- Append-only configuration audit log (constitution V)
CREATE TABLE pnh_config_audit_log (
    id             BIGSERIAL    PRIMARY KEY,
    entity_type    VARCHAR(50)  NOT NULL,
    entity_id      VARCHAR(150) NOT NULL,
    action         VARCHAR(20)  NOT NULL CHECK (action IN ('CREATE', 'UPDATE', 'DEACTIVATE', 'DELETE')),
    version        BIGINT       NOT NULL,
    changed_fields JSONB,
    actor          VARCHAR(100) NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_config_audit_entity ON pnh_config_audit_log (entity_type, entity_id, occurred_at DESC);
CREATE INDEX ix_config_audit_time ON pnh_config_audit_log (occurred_at DESC);

-- The application role may only INSERT/SELECT the audit log. When migrations run as the table owner,
-- revoking from PUBLIC plus a trigger guard makes the table append-only for every role.
REVOKE UPDATE, DELETE, TRUNCATE ON pnh_config_audit_log FROM PUBLIC;
CREATE FUNCTION pnh_audit_log_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'pnh_config_audit_log is append-only';
END;
$$;
CREATE TRIGGER trg_audit_log_append_only BEFORE UPDATE OR DELETE ON pnh_config_audit_log
    FOR EACH ROW EXECUTE FUNCTION pnh_audit_log_append_only();
