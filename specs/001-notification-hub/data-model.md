# Data Model: Project Notification Hub

**Source**: [spec.md](./spec.md) Key Entities + FRs, [technical-input.md](./technical-input.md) §3/§6/§7,
decisions in [research.md](./research.md). PostgreSQL ≥ 16. All timestamps `TIMESTAMPTZ` (UTC).

Conventions:
- **Config audit columns** (FR-032, R17) — abbreviated below as `+AUDIT`:
  `created_at, created_by VARCHAR(100), updated_at, updated_by VARCHAR(100), version BIGINT NOT NULL DEFAULT 0`.
- **Timestamps** are always set by the application from the `Clock` bean (research R29); DB `DEFAULT now()` is a
  safety net only and SQL never uses `now()` for logic.
- Enumerations stored as `VARCHAR` + `CHECK` (except `channel_type`, which is open for new channels
  such as `ZALO` and validated against the registered provider set in code — FR-031).
- Owning module in brackets.

## Enumerations

| Name | Values |
|------|--------|
| ChannelType | `SMS`, `EMAIL`, `PUSH` (open set; `ZALO` planned) |
| Category | `STANDARD`, `OTP` |
| ContentMode | `TEMPLATE`, `DIRECT` |
| NotificationStatus | `RECEIVED`, `PROCESSED`, `SENDING`, `SENT`, `DELIVERED`, `FAILED` |
| RecordStatus | `ACTIVE`, `INACTIVE` |
| OperatorRole | `ADMIN`, `TEMPLATE_EDITOR`, `SUPPORT_VIEWER` |
| ClientScope | `SEND`, `TRACK`, `USER_DELEGATE` |

## Configuration entities (all `+AUDIT`, optimistic locking)

### Client — `pnh_clients` [coreapi]

| Field | Type | Rules |
|-------|------|-------|
| client_id | VARCHAR(50) PK | `^[a-z0-9-_]{3,50}$` |
| client_name | VARCHAR(100) NOT NULL | |
| client_secret_hash | VARCHAR(255) NOT NULL | bcrypt; never returned |
| rate_limit_per_min | INT NOT NULL DEFAULT 1000 | > 0 |
| scopes | VARCHAR(100) NOT NULL DEFAULT 'SEND,TRACK' | subset of ClientScope |
| allow_direct_content | BOOLEAN NOT NULL DEFAULT TRUE | FR-001a / assumption |
| kafka_signing_key_encrypted | BYTEA NULL | per-client HMAC key for ingest message signatures (R5, fix U2); AES-GCM via EnvelopeEncryptor; required when the client publishes to Kafka |
| status | VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' | RecordStatus |
| +AUDIT | | |

Changed from technical input: `client_secret` → `client_secret_hash`; added scopes, direct-content flag, Kafka signing key.

### Template — `pnh_templates` [template]

| Field | Type | Rules |
|-------|------|-------|
| template_code | VARCHAR(100) PK | `^[A-Z0-9_]{3,100}$`, unique (FR-021) |
| template_name | VARCHAR(150) NOT NULL | |
| channel_type | VARCHAR(20) NOT NULL | registered channel |
| category | VARCHAR(20) NOT NULL DEFAULT 'STANDARD' | Category (FR-028) |
| is_critical | BOOLEAN NOT NULL DEFAULT FALSE | bypasses opt-out (FR-008) |
| otp_variable | VARCHAR(64) NULL | required when category=OTP; default `otp_code` (R15) |
| expiry_variable | VARCHAR(64) NULL | OTP only, optional: variable holding validity in minutes (e.g. `expired_minutes`); value must be an integer 1..1440; used to derive `expires_at` = accepted_at + value when the request has no `expires_at` (FR-029) |
| subject_template | VARCHAR(255) NULL | required iff channel=EMAIL |
| title_template | VARCHAR(255) NULL | required iff channel=PUSH |
| body_template | TEXT NOT NULL | HTML for EMAIL, plain text otherwise |
| external_template_ref | JSONB NULL | provider-side template mapping, e.g. `{"zns_template_id":"…"}`; required when channel capability `requiresExternalTemplate` (Zalo, R25) |
| required_variables | TEXT[] NOT NULL | extracted on save; identifiers only (R8) |
| status | VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' | |
| +AUDIT | | `version` also keys the compiled-template cache |

Rules: placeholders must match `\$\{[a-zA-Z_][a-zA-Z0-9_]{0,63}\}`; SMS body ≤ 1,600 chars rendered;
OTP templates must contain `${otp_variable}`. "Delete" = set `INACTIVE` (FR-021).

### User Channel Preference — `pnh_user_preferences` [dispatcher]

| Field | Type | Rules |
|-------|------|-------|
| id | BIGSERIAL PK | |
| user_id | VARCHAR(100) NOT NULL | |
| channel_type | VARCHAR(20) NOT NULL | |
| is_enabled | BOOLEAN NOT NULL DEFAULT TRUE | absent row ⇒ enabled |
| +AUDIT | | `created_by/updated_by` = `client_id:user_id` |

`UNIQUE(user_id, channel_type)`.

### Channel Provider Configuration — `pnh_channel_configs` [provider]

| Field | Type | Rules |
|-------|------|-------|
| id | BIGSERIAL PK | |
| channel_type | VARCHAR(20) NOT NULL | |
| provider_key | VARCHAR(50) NOT NULL | v1: `SMTP` (Mailtrap/Mailpit), `FCM`, `ANDROID_SMS_GATEWAY`, `GENERIC_HTTP_SMS`; deferred: `SENDGRID`; future: `ZALO_ZNS` |
| is_active | BOOLEAN NOT NULL DEFAULT FALSE | exactly one active per channel (partial unique index) |
| settings | JSONB NOT NULL | non-secret params (host, port, sender, endpoint, project_id…) |
| secrets_encrypted | BYTEA NULL | AES-256-GCM JSON blob (password, api_key, service-account JSON, hmac secret) |
| secrets_key_id | VARCHAR(100) NULL | master key reference |
| last_test_at / last_test_result | TIMESTAMPTZ / VARCHAR(500) | "test connection" |
| +AUDIT | | |

`UNIQUE(channel_type, provider_key)`; `UNIQUE(channel_type) WHERE is_active`.

### Operator — `pnh_operators` [admin]

| Field | Type | Rules |
|-------|------|-------|
| username | VARCHAR(100) PK | |
| display_name | VARCHAR(150) | |
| password_hash | VARCHAR(255) NOT NULL | bcrypt |
| role | VARCHAR(30) NOT NULL | OperatorRole (exactly one) |
| status | VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' | |
| +AUDIT | | seeded via migration/CLI in v1 |

### Configuration Audit Entry — `pnh_config_audit_log` [admin] (append-only)

| Field | Type | Rules |
|-------|------|-------|
| id | BIGSERIAL PK | |
| entity_type | VARCHAR(50) NOT NULL | CLIENT, TEMPLATE, USER_PREFERENCE, CHANNEL_CONFIG, OPERATOR, ALERT_RECIPIENT, ALERT_RULE, CLOCK_OVERRIDE |
| entity_id | VARCHAR(150) NOT NULL | |
| action | VARCHAR(20) NOT NULL | CREATE, UPDATE, DEACTIVATE, DELETE |
| version | BIGINT NOT NULL | resulting version |
| changed_fields | JSONB | `{field:{old,new}}`, secrets ⇒ `"***changed***"` |
| actor | VARCHAR(100) NOT NULL | |
| occurred_at | TIMESTAMPTZ NOT NULL | |

Index `(entity_type, entity_id, occurred_at desc)`. DB role for the app has `INSERT, SELECT` only (no UPDATE/DELETE).

### Alert Recipient — `pnh_alert_recipients` [alerting]

| Field | Type | Rules |
|-------|------|-------|
| id | BIGSERIAL PK | |
| email | VARCHAR(254) NOT NULL UNIQUE | valid email (lower-cased) |
| display_name | VARCHAR(150) NULL | |
| is_active | BOOLEAN NOT NULL DEFAULT TRUE | |
| +AUDIT | | |

### Alert Rule — `pnh_alert_rules` [alerting]

| Field | Type | Rules / default |
|-------|------|-----------------|
| rule_code | VARCHAR(50) PK | `STUCK_DELIVERY_SPIKE` (10 / 10 min), `PROVIDER_UNAVAILABLE` (circuit open ≥ 2 min), `CHANNEL_FAILURE_RATE` (≥ 30% / 10 min, min 50), `INGEST_REJECTED_SPIKE` (20 / 10 min) |
| enabled | BOOLEAN NOT NULL DEFAULT TRUE | |
| threshold | NUMERIC(10,2) NOT NULL | count, minutes or percent per rule |
| window_minutes | INT NOT NULL | 1..1440 |
| min_sample | INT NULL | rate rules only |
| cooldown_minutes | INT NOT NULL DEFAULT 30 | 5..1440 |
| +AUDIT | | rows seeded by migration; console edits values only |

## Transactional entities

### Request Key — `pnh_request_keys` [coreapi] (idempotency, R3)

| Field | Type |
|-------|------|
| client_id | VARCHAR(50) |
| client_request_id | VARCHAR(100) |
| notification_id | UUID NOT NULL |
| created_at | TIMESTAMPTZ NOT NULL |

`PRIMARY KEY (client_id, client_request_id)`; purged after 12 months.

### Notification — `pnh_notifications_history` [tracking; created by coreapi via event] — partitioned monthly by `created_at`

| Field | Type | Rules |
|-------|------|-------|
| notification_id | UUID | UUIDv7 (time-ordered); PK `(notification_id, created_at)` |
| client_id | VARCHAR(50) NOT NULL | |
| client_request_id | VARCHAR(100) NOT NULL | |
| user_id | VARCHAR(100) NULL¹ | Kafka routing key |
| recipient_target | VARCHAR(4096) NULL¹ | normalized (E.164 / lowercase email / token); widened for FCM tokens |
| channel_type | VARCHAR(20) NULL¹ | |
| category | VARCHAR(20) NOT NULL | |
| content_mode | VARCHAR(20) NOT NULL | ContentMode |
| template_code | VARCHAR(100) NULL | NULL iff DIRECT |
| template_version | BIGINT NULL | template version rendered at acceptance |
| subject / title | VARCHAR(255) NULL | rendered (masked if OTP) |
| compiled_content | TEXT NULL¹ | rendered at **acceptance** (template mode) or supplied+sanitized (direct EMAIL HTML) body; **masked** for OTP |
| payload_encrypted | BYTEA NULL | OTP only, real content; nulled at terminal status |
| push_data | JSONB NULL | PUSH data payload |
| expires_at | TIMESTAMPTZ NULL | required for OTP: request `expires_at`, else derived from template `expiry_variable` |
| opt_out_overridden | BOOLEAN NOT NULL DEFAULT FALSE | |
| status | VARCHAR(30) NOT NULL DEFAULT 'RECEIVED' | NotificationStatus |
| retry_count | INT NOT NULL DEFAULT 0 | |
| provider_key / provider_message_id | VARCHAR(50) / VARCHAR(255) NULL | for callback matching |
| error_code / error_message | VARCHAR(50) / TEXT NULL | last error |
| result_sequence | INT NOT NULL DEFAULT 0 | per-notification result ordering (R5) |
| sending_started_at | TIMESTAMPTZ NULL | set when entering SENDING / each attempt; sweeper input (R23) |
| created_at / updated_at | TIMESTAMPTZ NOT NULL | |

Partitions under a clock override: the override is only applied after partitions for the new month (+3) exist (R29,
fix H2); retention is skipped while the clock is overridden unless explicitly enabled (fix H3).
¹ Nullable only for **rejected ingest messages** (fix U1): `CHECK (status = 'FAILED' AND error_code IS NOT NULL OR (user_id IS NOT NULL AND recipient_target IS NOT NULL AND channel_type IS NOT NULL AND compiled_content IS NOT NULL))`. A stream message that fails validation but has a readable `client_id` + `client_request_id` is stored as FAILED with whatever fields were valid, so it is trackable and idempotent; `category` defaults to STANDARD and `content_mode` to the mode detected (or TEMPLATE).
Indexes (per partition): `(client_id, client_request_id)`, `(user_id, created_at desc)`,
`(recipient_target)`, `(status, updated_at)`, `(client_id, created_at)`,
`(provider_key, provider_message_id)`, partial `(updated_at) WHERE status IN ('RECEIVED','PROCESSED','SENDING')` (sweeper), partial `(user_id, created_at desc) WHERE channel_type='PUSH' AND category='STANDARD'` (inbox).
No FK to templates/clients (partitioned, historical integrity kept by deactivate-not-delete).

#### State machine (FR-013)

```text
RECEIVED ──► PROCESSED ──► SENDING ──► SENT ──► DELIVERED
    │            │            │  ▲        │          ▲
    │            │            │  └─retry──┘(attempt n ≤ 3, transient only; status stays SENDING)
    │            │            └──────── verified callback ──┘ (SENDING→DELIVERED; SENT skipped, fix I4/I10)
    └────────────┴────────────┴───────────┴──► FAILED   (terminal; SENDING→FAILED also via verified callback)
```

- Allowed: `RECEIVED→PROCESSED|FAILED`, `PROCESSED→SENDING|FAILED`, `SENDING→SENT|FAILED`,
  `SENDING→DELIVERED|FAILED` (**verified provider callback only**, fix I4),
  `SENT→DELIVERED|FAILED` (bounce via callback). `DELIVERED`/`FAILED` terminal, **except**
  `FAILED(DELIVERY_STATE_UNKNOWN)→DELIVERED` on a verified delivery callback (FR-041).
- Late send acknowledgement (fix I4): the orchestrator's SENT commit is conditional on `status='SENDING'`; if a
  callback already moved it to DELIVERED/FAILED, only `provider_message_id`/`provider_key` are stored and no SENT
  result is emitted.
- Callback matching order: hub `notification_id` echoed by provider (preferred) → `(provider_key, provider_message_id)`.
- Crash rule (FR-039, R4): `SENDING` is committed before the provider call; a resubmitted event that
  finds `SENDING` never calls the provider again.
- Sweeper (FR-040, R23): `SENDING` > stuck timeout → `FAILED/DELIVERY_STATE_UNKNOWN`;
  `RECEIVED|PROCESSED` > processing timeout → `FAILED/PROCESSING_TIMEOUT`; OTP past `expires_at` →
  `FAILED/OTP_EXPIRED`.
- Any other transition (including duplicates/late callbacks) is a no-op recorded as an ignored event.
- FAILED reasons at each stage: RECEIVED (async validation e.g. Kafka), PROCESSED
  (`USER_OPTED_OUT`, `OTP_EXPIRED`, `CHANNEL_NOT_CONFIGURED`), SENDING (`PROVIDER_*`, `OTP_EXPIRED`,
  `INVALID_RECIPIENT`, `CONTENT_TOO_LONG`), SENT (`BOUNCED`, provider reasons).
- Result messages emitted on entering SENT, DELIVERED, FAILED (FR-016).

### Notification Status Event — `pnh_notification_events` [tracking] — partitioned monthly

| Field | Type |
|-------|------|
| id | BIGSERIAL |
| notification_id | UUID NOT NULL |
| created_at | TIMESTAMPTZ NOT NULL (partition key) |
| event_type | VARCHAR(30) NOT NULL — `STATUS_CHANGED`, `ATTEMPT_FAILED`, `OPT_OUT_OVERRIDDEN`, `CALLBACK_RECEIVED`, `CALLBACK_IGNORED`, `SWEPT`, `RESUBMISSION_SKIPPED`, `CONTENT_SANITIZED` |
| from_status / to_status | VARCHAR(30) NULL |
| attempt | INT NULL |
| provider_key | VARCHAR(50) NULL |
| error_code / error_message | VARCHAR(50) / TEXT NULL |
| provider_response | JSONB NULL (sanitized, no secrets/OTP) |

Index `(notification_id, created_at)`. Feeds the Audit Log timeline (FR-014, US8).

### Read State — `pnh_notification_read_states` [inbox]

| Field | Type | Rules |
|-------|------|-------|
| notification_id | UUID PK | must be a PUSH, STANDARD notification of `user_id` |
| user_id | VARCHAR(100) NOT NULL | |
| is_read | BOOLEAN NOT NULL DEFAULT FALSE | |
| read_at | TIMESTAMPTZ NULL | first read time preserved (FR-019) |
| notification_created_at | TIMESTAMPTZ NOT NULL | for inbox sort/join |

Row created when a PUSH STANDARD notification reaches SENT (inbox item). Index
`(user_id, is_read) `, `(user_id, notification_created_at desc)`. Unread count =
`count(*) WHERE user_id=? AND NOT is_read`.

### Hourly Stats — `pnh_notification_stats_hourly` [tracking] (R10)

`PK (bucket_hour, channel_type, client_id, status)`, `count BIGINT`, `recomputed_at`.

### Alert Event — `pnh_alert_events` [alerting] (append-only)

| Field | Type |
|-------|------|
| id | BIGSERIAL PK |
| rule_code | VARCHAR(50) NOT NULL |
| channel_type | VARCHAR(20) NULL |
| kind | VARCHAR(20) NOT NULL — `FIRED`, `RESOLVED` |
| measured_value / threshold | NUMERIC(10,2) |
| window_minutes | INT |
| occurred_at | TIMESTAMPTZ NOT NULL |
| recipients | TEXT[] NOT NULL (snapshot) |
| send_status | VARCHAR(20) NOT NULL — `SENT`, `SEND_FAILED`, `NO_RECIPIENTS` |
| send_error | TEXT NULL |
| details | JSONB (sample notification ids, provider key, audit-log filter link) |

Index `(rule_code, channel_type, occurred_at desc)` — also the durable cooldown source (R24).

### Framework tables

- `event_publication` (Spring Modulith registry, JDBC schema) — completion mode DELETE.
- Flyway `flyway_schema_history`.

## Redis keys (non-authoritative, R3/R21/R22/R24)

| Key pattern | Type | TTL | Purpose |
|-------------|------|-----|---------|
| `idem:{clientId}:{clientRequestId}` | string (notification id) | 5 min | idempotency fast path |
| `rl:{clientId}` | RRateLimiter | — | per-client rate limit |
| `lock:job:{jobName}` | RLock | watchdog | single-runner scheduled jobs |
| `lock:notif:{notificationId}` | RLock | 10 s | serialize callback/sweeper per notification |
| `alert:win:{rule}:{channel}` | sorted set (ts) | window + 1 min | sliding-window counters |
| `alert:cooldown:{rule}:{channel}` | string | cooldown | alert de-duplication |
| `pnh:cache-invalidation` | pub/sub topic | — | Caffeine eviction broadcast |
| `pnh:clock:override` / topic `pnh:clock` | string (JSON mode/instant/offset) / pub/sub | — | non-prod system time override (R29) |

Losing all Redis data must never lose or duplicate notifications (constitution v1.1.0).

## Entity relationships

```text
Client 1─* RequestKey 1─1 Notification *─0..1 Template
Notification 1─* NotificationStatusEvent
Notification(PUSH,STANDARD) 1─0..1 ReadState
User(user_id) 1─* UserChannelPreference (≤1 per channel)
ChannelType 1─* ChannelProviderConfig (exactly 1 active)
AlertRule 1─* AlertEvent
every config entity 1─* ConfigAuditEntry
Operator ─ performs ─► ConfigAuditEntry (actor)
```

## Delivery Result Message (stream payload, not a table)

`notification_id, client_request_id, client_id, user_id, channel_type, status, error_code, error_message?, sequence, event_id, timestamp` — see [contracts/kafka/result.schema.json](./contracts/kafka/result.schema.json).
