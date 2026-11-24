# PNH operations guide

## Provisioning (CLI)

Clients and operators are managed with the provisioning CLI (no console user management in v1, research R12):

```bash
java -jar backend/target/pnh.jar --spring.profiles.active=cli <command> [--option=value ...]
```

| Command | Options | Output |
|---------|---------|--------|
| `create-client` | `--client-id` (`^[a-z0-9-_]{3,50}$`), `--name`, `--rate-limit` (default 1000/min), `--scopes` (`SEND,TRACK,USER_DELEGATE`), `--allow-direct-content` | `client_secret` and `kafka_signing_key` — **shown once** |
| `update-client` | `--client-id`, any of `--rate-limit`, `--scopes`, `--allow-direct-content` | |
| `rotate-client-secret` / `rotate-kafka-key` | `--client-id` | the new secret / key, shown once |
| `deactivate-client` | `--client-id` | |
| `create-operator` | `--username`, `--role` (`ADMIN`, `TEMPLATE_EDITOR`, `SUPPORT_VIEWER`), `--display-name`, `--password` (generated when omitted) | generated password, shown once |
| `reset-operator-password` / `deactivate-operator` | `--username` | |

The CLI exits with the command's exit code (0 success, 1 invalid input/not found). It uses the normal datasource/crypto settings (`PNH_DB_URL`, `PNH_MASTER_KEY`, …), starts no web server, Kafka
listeners or jobs, exits non-zero on invalid input, and writes every change to the config audit log with actor
`cli:<os-user>`. Client secrets are stored as bcrypt hashes and Kafka signing keys AES-GCM encrypted.

## Stuck notifications (FR-039..041, research R4/R23)

- The delivery step commits `SENDING` **before** calling the provider. If an instance dies after that point, the
  outcome is unknown and the hub **never re-sends** (a resubmitted event that finds `SENDING` records
  `RESUBMISSION_SKIPPED`).
- The sweeper (every 60 s, one instance cluster-wide) fails:
  - `SENDING` older than `pnh.delivery.stuck-timeout` (default 5 min) → `FAILED / DELIVERY_STATE_UNKNOWN`;
  - `RECEIVED` / `PROCESSED` older than `pnh.delivery.processing-timeout` (default 15 min) → `FAILED / PROCESSING_TIMEOUT`;
  - in-flight OTPs past `expires_at` → `FAILED / OTP_EXPIRED`.
  Each gets a `SWEPT` timeline event and a FAILED result message; at most 500 per run.
- Startup fails when the stuck timeout is not greater than the longest possible delivery (attempts × call timeout + backoff).
- A later **verified** "delivered" provider report turns `FAILED / DELIVERY_STATE_UNKNOWN` into `DELIVERED` (new result).

## Incident alerts (FR-042..045, research R24)

Console → Alerts (ADMIN): recipients, rules, history. Defaults:

| Rule | Fires when | Default |
|------|-----------|---------|
| `STUCK_DELIVERY_SPIKE` | sweeper failures per channel in the window ≥ threshold | 10 in 10 min |
| `PROVIDER_UNAVAILABLE` | a provider circuit has been open ≥ threshold minutes | 2 min |
| `CHANNEL_FAILURE_RATE` | FAILED share of finished notifications ≥ threshold %, with ≥ `min_sample` | 30 % / 10 min / 50 |
| `INGEST_REJECTED_SPIKE` | rejected Kafka messages (DLT or FAILED) in the window ≥ threshold | 20 in 10 min |

Evaluation runs every 60 s on one instance. A rule fires once, then stays quiet for `cooldown_minutes` (default 30)
while the condition persists; a single "resolved" email follows when it clears. Alert emails go through the **active
EMAIL provider directly** (not the notification pipeline). If that fails the event is `SEND_FAILED` and retried on the
next run; configure `pnh.alerting.fallback-smtp.host/port/from` for an independent fallback. Events without active
recipients are still recorded (`NO_RECIPIENTS`). The durable cooldown state is the alert history table, so alerts are
not duplicated after a Redis loss.

## Redis degradation (constitution v1.1.0, R21/R22)

Redis is an accelerator, never the system of record. When it is unavailable:

| Feature | Fallback |
|---------|----------|
| Idempotency fast path | Postgres `pnh_request_keys` (authoritative) — duplicates still rejected |
| Rate limits | local Bucket4j bucket of `rate_limit_per_min / pnh.ratelimit.replica-count` per instance |
| Job locks | jobs skip their run; after 3 consecutive failures a Postgres advisory lock is used (logged once on entry: `scheduled jobs now run under PostgreSQL advisory locks`, and on exit) |
| Cache invalidation | local eviction only; other replicas converge by TTL (≤ 5 min, preferences ≤ 60 s) |
| Alert windows | Postgres counts (stuck, failure rate) / instance-local windows (ingest, circuits) |
| Clock override | the configured startup mode stays in effect |

Console operator sessions are not affected: they are stored in PostgreSQL (Spring Session JDBC, tables
`spring_session*`), so any replica can serve any signed-in operator and no sticky sessions are needed.

Signals: `GET /actuator/health` shows component `redisAvailability: DEGRADED` (HTTP 200), metric
`pnh_redis_fallback_active` = 1. Both clear automatically after the next successful Redis operation.

## Partitions and retention (R9, R29)

- `pnh_notifications_history` and `pnh_notification_events` are partitioned by month. `PartitionMaintenanceJob` (daily
  03:15 UTC and at startup) keeps partitions for the current month + 3 — around both the application clock and the real
  clock. There is no default partition: an insert outside existing partitions fails loudly.
- `RetentionJob` (monthly, 02:30 UTC on day 1) drops partitions older than `pnh.retention.months` (12) and deletes old
  idempotency keys in batches. While the system time is overridden it does nothing and increments
  `pnh_retention_skipped_total{reason="clock_override"}` unless `pnh.retention.skip-when-clock-overridden=false`.

## System time override (non-production only, R29)

`pnh.clock.override-enabled=true` (never with profile `prod` — startup fails) makes the hub follow the **VSP system
clock managed by vsp-catalog-service** (`PUT/DELETE /api/v1/admin/system-clock`, history in its database). Catalog
publishes the state on Redis key `vsp:clock:override` (absent = SYSTEM) and topic `vsp:clock` — the hub must use the
**same Redis**. Every instance applies it within ~1 s and re-reads the key every `pnh.clock.resync-interval` (60 s);
partitions for the target month (+3) are created first, otherwise the change is ignored and logged.
`GET /console/api/admin/clock` (console → System time) is read-only.
Kafka timestamps, Redis TTLs, Resilience4j windows and scheduler triggers keep real time by design.

## Observability

- Prometheus: `/actuator/prometheus` — `pnh_notifications_total{channel,status}`, `pnh_dispatch_latency_seconds{category}`,
  `pnh_provider_attempt_seconds{provider,outcome}`, `resilience4j_circuitbreaker_state{name}`, `resilience4j_retry_calls_total`,
  `pnh_redis_fallback_active`, `pnh_clock_override_active`, `pnh_retention_skipped_total`.
- Traces: spans `pnh.intake` → `pnh.dispatch` → `pnh.provider.send`, each with `notification_id`; enable OTLP export
  with `PNH_OTLP_TRACES_ENABLED=true` and `PNH_OTLP_TRACES_URL`.
- Logs: JSON (non-`local` profiles) with MDC `notification_id`, `client_request_id`, `client_id`, `traceId`; e-mail
  addresses, phone numbers, OTPs and secret JSON fields are masked by the log converter.

## Health endpoint

`GET /actuator/health` returns HTTP 200 for `UP` and `DEGRADED` (Redis fallback active — keep routing traffic) and 503
for `DOWN` / `OUT_OF_SERVICE`. Readiness/liveness groups: `/actuator/health/readiness`, `/actuator/health/liveness`.

## Sizing notes

- `PNH_DB_POOL_SIZE` (default 50) must cover the async executors that use the database concurrently
  (`pnh.async.standard-threads` 32 + `pnh.async.otp-threads` 16 + default executor 16) plus HTTP/Kafka intake threads.
  Pool exhaustion shows up as `Connection is not available` warnings; affected notifications stay `SENDING` and are
  resolved by the sweeper (never re-sent).
- PostgreSQL `max_connections` must cover every replica's pool plus the provisioning CLI (pool of 2) and migrations:
  e.g. 4 replicas × 50 + 10. The local compose stack sets `max_connections=200`.

## Console access via identity SSO (vsp-frontend Platform Console)

The operator screens now live in **vsp-frontend** (Platform Console → Notification). Set `PNH_JWKS_URI` (identity
staff realm JWKS, e.g. `http://identity:8080/t/ticket-staff/.well-known/jwks.json`, optional `PNH_JWT_ISSUER`) and
the console API accepts identity staff JWTs (`Authorization: Bearer`, no CSRF). Every endpoint checks an identity
permission of the `notification` application:

| Endpoint | Read | Write |
|---|---|---|
| `/console/api/dashboard/**` | `notification.dashboard.view` | |
| `/console/api/templates/**` | `notification.template.view` | `notification.template.manage` |
| `/console/api/notifications/**` | `notification.message.view` | |
| `/console/api/channel-configs/**` | `notification.channel.view` | `notification.channel.manage` |
| `/console/api/alerts/**` | `notification.alert.manage` or `notification.audit.view` | `notification.alert.manage` |
| `/console/api/config-change-log` | `notification.audit.view` | |

`ConsoleAuthorizationArchTest` fails the build if a console handler has no `@PreAuthorize`. The legacy local operator
login still works during the transition; operator roles map to the same permissions (ADMIN = all,
TEMPLATE_EDITOR = NOTIFICATION_TEMPLATE_EDITOR, SUPPORT_VIEWER = NOTIFICATION_SUPPORT; note SUPPORT_VIEWER can now read
templates). `frontend/` here is deprecated and will be removed once the Platform Console module is the only console.
