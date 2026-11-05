# Technical Input (preserved for /speckit-plan)

This file preserves the implementation-level details supplied with the `/speckit-specify` request on 2026-11-03.
They are intentionally kept OUT of `spec.md` (which is technology-agnostic) and MUST be used as binding input during planning.

## 1. Overview & Tech Stack

- System Name: Project Notification Hub (PNH)
- Architecture Pattern: Clean Architecture + Spring Modulith
- Backend: Spring Boot 4.1.1 (or latest stable 4.x available), JDK 21
- Frontend: ReactJS 19, Mantine UI v7+
- Database: PostgreSQL (transactional data, notification history, event logs)
- Message Broker: Apache Kafka (asynchronous ingestion & result streaming)
- Supported Channels: SMS (Gateway), Email (SMTP/SendGrid), Push Notification (Firebase Cloud Messaging - FCM)

## 2. Backend Architecture (Spring Modulith + Clean Architecture)

### 2.1 Module Structure

Independent modules communicating internally via Spring Application Events:

1. `pnh-core-api`: Receives requests via REST API and Kafka ingestion. Idempotency check and client authentication.
2. `pnh-template`: Manages, stores and compiles templates (Thymeleaf engine to replace dynamic placeholder variables).
3. `pnh-dispatcher`: Routing; decides send channel (SMS, Email, Push) and manages User Preferences (channel subscription).
4. `pnh-provider-sms`, `pnh-provider-email`, `pnh-provider-fcm`: External gateway integrations (Clean Architecture with isolated adapters).
5. `pnh-tracking`: Records history, logs status, handles provider webhook callbacks and publishes Kafka result events (success/failure).

### 2.2 Core Technical Mechanisms

- **Idempotency**: Every send request MUST contain `client_request_id` (or `idempotency_key`). Use Redis or a PostgreSQL unique index to block duplicates within a 5-minute window.
- **Spring Modulith Event Publication Registry**: `pnh-core-api -> pnh-dispatcher -> pnh-provider` communication recorded in the Event Publication Registry on Postgres so no events are lost on application crash.
- **Resilience4j**:
  - Retry: up to 3 retries with exponential backoff for network errors (5xx, timeout).
  - Circuit Breaker: open circuit to a provider (e.g. SMS gateway) if failure rate exceeds 50% within 10 seconds.

## 3. Database Schema (PostgreSQL)

```sql
-- 1. Client Registration
CREATE TABLE pnh_clients (
    client_id VARCHAR(50) PRIMARY KEY,
    client_name VARCHAR(100) NOT NULL,
    client_secret VARCHAR(255) NOT NULL,
    rate_limit_per_min INT DEFAULT 1000,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 2. Template Configuration
CREATE TABLE pnh_templates (
    template_code VARCHAR(100) PRIMARY KEY,
    template_name VARCHAR(150) NOT NULL,
    channel_type VARCHAR(20) NOT NULL, -- EMAIL, SMS, PUSH
    subject_template VARCHAR(255),     -- Email only
    body_template TEXT NOT NULL,       -- Placeholders as ${variable_name}
    status VARCHAR(20) DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 3. User Preferences
CREATE TABLE pnh_user_preferences (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    channel_type VARCHAR(20) NOT NULL, -- EMAIL, SMS, PUSH
    is_enabled BOOLEAN DEFAULT TRUE,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_channel UNIQUE(user_id, channel_type)
);

-- 4. Notification History & Status (Core Logging)
CREATE TABLE pnh_notifications_history (
    notification_id UUID PRIMARY KEY,
    client_id VARCHAR(50) REFERENCES pnh_clients(client_id),
    client_request_id VARCHAR(100) NOT NULL, -- Idempotency Key
    user_id VARCHAR(100) NOT NULL,
    recipient_target VARCHAR(255) NOT NULL,  -- Phone, Email, or FCM Token
    channel_type VARCHAR(20) NOT NULL,
    template_code VARCHAR(100) REFERENCES pnh_templates(template_code),
    compiled_content TEXT NOT NULL,
    status VARCHAR(30) DEFAULT 'RECEIVED',   -- RECEIVED, PROCESSED, SENDING, SENT, DELIVERED, FAILED
    retry_count INT DEFAULT 0,
    error_code VARCHAR(50),
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_client_request UNIQUE(client_id, client_request_id)
);
CREATE INDEX idx_notif_user ON pnh_notifications_history(user_id);
CREATE INDEX idx_notif_status ON pnh_notifications_history(status);

-- 5. In-app Notification Read State
CREATE TABLE pnh_notification_read_states (
    notification_id UUID PRIMARY KEY REFERENCES pnh_notifications_history(notification_id),
    user_id VARCHAR(100) NOT NULL,
    is_read BOOLEAN DEFAULT FALSE,
    read_at TIMESTAMP WITH TIME ZONE
);
```

**Gaps noted during specification** (to resolve in planning): no table yet for per-notification status-transition history (needed by the Audit Log timeline), for channel/provider configuration (needed by the Channel Configuration screen), or for admin-console users/roles.

## 4. Integration Interfaces

### 4.1 Inbound

- REST: `POST /api/v1/notifications/send`

```json
{
  "client_request_id": "req-unique-uuid-12345",
  "user_id": "user_99",
  "channel_type": "EMAIL",
  "recipient_target": "user@example.com",
  "template_code": "OTP_VERIFICATION",
  "variables": {
    "otp_code": "654321",
    "expired_minutes": "5"
  }
}
```

- Kafka ingestion topic: `pnh.notification.ingest` (payload same shape as REST).

### 4.2 Outbound

- REST: `GET /api/v1/notifications/track/{client_request_id}` → current `status`, `error_code` (if any), last-updated time.
- Kafka result topic: `pnh.notification.result`

```json
{
  "notification_id": "system-uuid-xyz",
  "client_request_id": "req-unique-uuid-12345",
  "status": "DELIVERED",
  "error_code": null,
  "timestamp": "2026-11-03T13:22:00Z"
}
```

### 4.3 In-App Notification Management (end-user facing)

- Mark single read: `PATCH /api/v1/notifications/{id}/read`
- Mark multiple read: `POST /api/v1/notifications/bulk-read` → `{"notification_ids": ["uuid1", "uuid2"]}`

## 5. Frontend (ReactJS 19 + Mantine UI)

1. **Dashboard**: Mantine Charts for send volume by day/hour; success/failure rate per channel (SMS, Email, Firebase).
2. **Template Management**: CRUD of template codes; `@mantine/tiptap` rich-text editor for Email HTML with visual highlighting of placeholders (e.g. `${name}`).
3. **Channel Configurations**: secure configuration of provider parameters (SMTP server, Firebase Admin SDK JSON, SMS API endpoint/secret).
4. **Audit Log**: Mantine Data Table with advanced search by `client_request_id`, `user_id`, `recipient_target`; status lifecycle Timeline with provider error logs.

## 6. Additions (2026-11-03, user follow-up)

- **OTP flow** via SMS and Email is a first-class use case (priority processing, expiry enforcement, OTP masking). See spec User Story 3, FR-028..FR-030.
- **Zalo** is a planned future channel (`ZALO`, e.g. Zalo ZNS). Provider modules must follow the same isolated-adapter pattern (`pnh-provider-zalo`) so it can be added without touching core modules. `channel_type` values must not be hard-coded to a closed set in contracts (VARCHAR already allows this). See FR-031.
- Schema implication: `pnh_templates` needs a category column (OTP/STANDARD); OTP requests need a validity/expiry field.
- Clarification outcomes affecting the data model: `pnh_templates` also needs an `is_critical` flag (bypasses opt-out); read state/inbox applies to PUSH notifications only (needs a listing query by `user_id` + `channel_type='PUSH'` + unread count); operator accounts need a `role` (ADMIN, TEMPLATE_EDITOR, SUPPORT_VIEWER), seeded/configured outside the console in v1.

## 7. Additions (2026-11-03, user follow-up #2)

### 7.1 Request content modes

Exactly one of `template_code` or `content` must be present.

Template mode (unchanged):

```json
{
  "client_request_id": "req-123",
  "user_id": "user_99",
  "channel_type": "EMAIL",
  "recipient_target": "user@example.com",
  "template_code": "OTP_VERIFICATION",
  "variables": { "otp_code": "654321", "expired_minutes": "5" }
}
```

Direct-content mode (no template):

```json
{
  "client_request_id": "req-124",
  "user_id": "user_99",
  "channel_type": "EMAIL",            // SMS | EMAIL | PUSH | (ZALO later)
  "recipient_target": "user@example.com",
  "category": "OTP",                  // optional, default STANDARD
  "otp_value": "654321",              // required when category=OTP (used for masking)
  "expires_at": "2026-11-03T13:27:00Z", // required when category=OTP
  "content": {
    "subject": "Mã xác thực của bạn",  // EMAIL (required)
    "body": "<p>Mã OTP: 654321</p>",   // EMAIL html / SMS text / PUSH body (required)
    "title": null,                     // PUSH (required)
    "data": null                       // PUSH optional key-value payload
  }
}
```

Schema implications: `pnh_notifications_history.template_code` becomes NULLABLE; add `content_mode` (TEMPLATE|DIRECT), `category` (OTP|STANDARD), `subject`/`title` (or store compiled content as JSON), `expires_at`. OTP value must never be persisted in clear text (store masked content for display, or encrypt). `pnh_clients` gets `allow_direct_content BOOLEAN DEFAULT TRUE`.

### 7.2 Audit columns on configuration tables

Apply to `pnh_clients`, `pnh_templates`, `pnh_user_preferences`, channel provider config table, operator accounts table:

```sql
created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
created_by  VARCHAR(100) NOT NULL,   -- operator username | client_id[:user_id] | 'system'
updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
updated_by  VARCHAR(100) NOT NULL,
version     BIGINT NOT NULL DEFAULT 0  -- optimistic locking (JPA @Version), +1 per update
```

Plus an append-only change log:

```sql
CREATE TABLE pnh_config_audit_log (
    id            BIGSERIAL PRIMARY KEY,
    entity_type   VARCHAR(50)  NOT NULL,   -- CLIENT, TEMPLATE, USER_PREFERENCE, CHANNEL_CONFIG, OPERATOR
    entity_id     VARCHAR(150) NOT NULL,
    action        VARCHAR(20)  NOT NULL,   -- CREATE, UPDATE, DEACTIVATE, DELETE
    version       BIGINT       NOT NULL,
    changed_fields JSONB,                  -- {"field": {"old": ..., "new": ...}}, secrets => "***changed***"
    actor         VARCHAR(100) NOT NULL,
    occurred_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_cfg_audit_entity ON pnh_config_audit_log(entity_type, entity_id);
```

(Candidate implementation: Spring Data JPA auditing `@CreatedBy/@LastModifiedBy` + `@Version`; change log via entity listener or Hibernate Envers — decide in plan.)

### 7.3 Kafka routing key / partitioning

| Topic | Message key | Headers (metadata, not used for partitioning) |
|-------|-------------|------------------------------------------------|
| `pnh.notification.ingest` | `user_id` (set by producer) | `client_id`, `channel_type`, `category` |
| `pnh.notification.result` | `user_id` | `client_id`, `channel_type`, `status` |
| internal topics (if any) | `user_id` | as needed |

- Why `user_id` not `channel_type`: only 3–4 channel values → at most 3–4 hot partitions regardless of partition count, and Email/SMS volume is usually skewed. `user_id` has high cardinality → even spread, and keeps per-user / per-notification ordering (SENT before DELIVERED).
- Ingest messages without a key are still consumed (round-robin placement); everything the hub publishes is keyed by `user_id`.
- Clients filter results by `client_id` header (or a per-client result topic later if isolation is required).
- OTP priority: Kafka has no per-message priority. Planning should consider a separate topic/consumer lane for OTP (e.g. `pnh.notification.ingest.otp`, same key) so OTP is not stuck behind a STANDARD backlog (SC-012).

## 8. Additions (2026-11-03, user follow-up #3 — risk decisions)

- **Crash after provider call**: never re-send. Notifications stuck in processing/sending are marked FAILED after a
  timeout (`DELIVERY_STATE_UNKNOWN` / `PROCESSING_TIMEOUT`). If such failures spike within a window, email alerts go
  to admins. Admins manage a **list of alert-recipient emails** for all incidents (console setting).
- **Providers for v1**: a free SMS gateway (chosen: SMS Gateway for Android, open source, via a phone/SIM; WireMock
  for CI) and a mock/sandbox email service (Mailtrap Email Sandbox via SMTP; Mailpit offline). SendGrid deferred.
- **Zalo**: pending; keep as an adapter behind the channel SPI so it can be integrated with minimal change.
- **Caching & locking**: add **Redis + Redisson** and **Caffeine** — Caffeine for in-process caches; Redisson for
  distributed locks, cache invalidation (pub/sub), rate limiting and short-lived keys. PostgreSQL remains the system of
  record. Constitution amended to v1.1.0 accordingly.

## 9. Decisions from /speckit-analyze fixes (2026-11-03)

- Template content is rendered at acceptance (intake), not in the dispatcher → content always matches the template
  version active when the request was accepted.
- Result stream is at-least-once with `event_id` (de-duplication) + `sequence` (ordering) — FR-016 updated.
- OTP templates may declare `expiry_variable` (e.g. `expired_minutes`); `expires_at` is derived when the request omits
  it, so the original sample payload stays valid.
- Direct-content Email HTML (and template HTML on save) is sanitized with OWASP Java HTML Sanitizer (allowlist).
