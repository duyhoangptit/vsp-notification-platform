# Quickstart & Validation Guide: Project Notification Hub

Runnable scenarios that prove the feature end to end. Contracts:
[client-api](./contracts/client-api.openapi.yaml), [console-api](./contracts/console-api.openapi.yaml),
[callbacks](./contracts/provider-callbacks.openapi.yaml), [kafka](./contracts/kafka/README.md).
Data shapes: [data-model.md](./data-model.md).

## Prerequisites

- JDK 21, Node.js 22 LTS + pnpm, Docker (Compose v2), `curl`, `jq`, `kcat` (or the Kafka CLI inside the `kafka`
  container: `docker compose -f deploy/local/compose.yaml exec kafka /opt/kafka/bin/kafka-console-consumer.sh …`), `k6` (load scenario only).
- Ports free: 8080 (backend), 5173 (console dev), 5432, 9092, 6379 (Redis), 8025 (Mailpit UI), 1025 (SMTP), 8089 (WireMock).
- Optional real providers (free): a Mailtrap account (Email Sandbox SMTP credentials), an Android phone with the
  *SMS Gateway for Android* app (local server or cloud mode), a Firebase project with a test device token.

## Setup

```bash
# 1. Infrastructure: Postgres, Kafka (KRaft), Redis, Mailpit (SMTP sink), WireMock (SMS gateway + FCM stubs)
docker compose -f deploy/local/compose.yaml up -d

# 2. Backend (Flyway migrates, seeds demo client/operators/templates under profile "local")
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# 3. Console
cd frontend && pnpm install && pnpm dev     # http://localhost:5173
```

Seeded by the `local` profile:

| Kind | Values |
|------|--------|
| Client | `demo-client` / `demo-secret`, scopes SEND, TRACK, USER_DELEGATE, limit 1000/min |
| Operators | `admin`/`admin123` (ADMIN), `editor`/`editor123` (TEMPLATE_EDITOR), `support`/`support123` (SUPPORT_VIEWER) |
| Templates | `OTP_VERIFICATION` (EMAIL, OTP), `OTP_SMS` (SMS, OTP), `ORDER_SHIPPED_PUSH` (PUSH, STANDARD), `NEWSLETTER_EMAIL` (EMAIL, STANDARD) |
| Channel configs | SMTP → Mailpit, GENERIC_HTTP_SMS → WireMock, FCM → WireMock (switch in console to Mailtrap SMTP / ANDROID_SMS_GATEWAY / real FCM) |
| Alerts | recipient `ops@example.com` (lands in Mailpit), default rules from data-model |

```bash
export PNH=http://localhost:8080 AUTH='demo-client:demo-secret'
```

## Validation scenarios

### Q1. Template send + idempotency (US1, FR-004, SC-002)

```bash
curl -s -u $AUTH -XPOST $PNH/api/v1/notifications/send -H 'Content-Type: application/json' -d '{
 "client_request_id":"q1-001","user_id":"user_99","channel_type":"EMAIL","recipient_target":"user@example.com",
 "template_code":"NEWSLETTER_EMAIL","variables":{"name":"An"}}' -w '\n%{http_code}\n'
```

Expected: `202` with `notification_id`, status `RECEIVED`. Re-run the same command → `200`,
header `Idempotent-Replay: true`, same `notification_id`. Mailpit (http://localhost:8025) shows exactly one email.

### Q2. Direct-content mode + validation (US1 #7–8, FR-001a/b)

- Send `channel_type: SMS`, no `template_code`, `content.body` → `202`; WireMock request journal shows the body verbatim.
- Send both `template_code` and `content` → `422` `CONTENT_MODE_CONFLICT`.
- Send EMAIL direct content without `subject` → `422` `MISSING_CONTENT_FIELD`.
- Send template mode with a missing variable → `422` `MISSING_VARIABLES` listing the names.
- Send EMAIL direct content containing `<script>` / `onclick` / `javascript:` links → `202`; Mailpit shows the email
  without them and the console timeline has `CONTENT_SANITIZED`.
- Edit and deactivate `NEWSLETTER_EMAIL` right after a `202` → the delivered email still uses the content from acceptance.

### Q3. Tracking + result stream (US2, FR-015/016)

> Kafka ingest messages must carry `client_id` and `pnh-signature` headers (contracts/kafka/README.md). The `local`
> profile sets `pnh.kafka.signature-required=false` so `kcat` can be used directly; `deploy/local/scripts/kafka-sign.sh`
> computes a signature when you want to test the real check.


```bash
kcat -b localhost:9092 -C -t pnh.notification.result -f 'key=%k headers=%h %s\n' &
curl -s -u $AUTH $PNH/api/v1/notifications/track/q1-001 | jq
```

Expected: track shows `SENT` (SMTP has no delivery report). Result topic has one message keyed `user_99`
with `status: SENT`, `sequence: 1`, `event_id`. Tracking another client's request id → `404`.

### Q4. OTP flow: priority, expiry, masking (US3, FR-028..030, SC-012)

0. Send the original technical-input payload (`OTP_VERIFICATION`, `variables.expired_minutes="5"`, no `expires_at`) → `202`;
   detail shows `expires_at` = acceptance + 5 min (template `expiry_variable`).
1. Send `OTP_SMS` with `variables.otp_code=654321`, `expires_at` = now + 5 min → SMS reaches WireMock
   with `654321`; console detail and `/track` show `******`; `grep 654321` in backend logs → no hits.
2. Set WireMock SMS stub to delay 10 s / return 503; send OTP with `expires_at` = now + 3 s →
   notification ends `FAILED` / `OTP_EXPIRED`, timeline shows no attempt after expiry.
3. Load 5,000 STANDARD emails via `pnh.notification.ingest`, then send 50 OTPs → OTP p95
   accept→SENDING < 5 s (metric `pnh_dispatch_latency_seconds{category="OTP"}`).

### Q5. Opt-out and critical bypass (US5, FR-008)

```bash
curl -s -u $AUTH -XPUT $PNH/api/v1/users/user_99/preferences -H 'Content-Type: application/json' \
  -d '{"channels":{"EMAIL":false}}'
```

Then: STANDARD email to `user_99` → `FAILED` `USER_OPTED_OUT`, nothing in Mailpit.
OTP email (`OTP_VERIFICATION`) → delivered; timeline contains `OPT_OUT_OVERRIDDEN`.

### Q6. Retry and circuit breaker (FR-010/011, SC-006)

- WireMock SMS: first 2 calls 503, then 200 → notification `SENT`, `retry_count=2`, timeline has 2 `ATTEMPT_FAILED`.
- WireMock SMS: always 400 `INVALID_NUMBER` → `FAILED` immediately, no retries.
- WireMock SMS: always 503 under 20 req/s for 15 s → circuit opens (`resilience4j_circuitbreaker_state{name="sms"}`=open),
  SMS attempts fail fast with `PROVIDER_UNAVAILABLE`; emails in parallel stay `SENT`.
  Restore stub → circuit closed within 60 s; new SMS succeed.

### Q7. Delivery callbacks (FR-017)

```bash
# Sign and post a DELIVERED report for an SMS notification (helper script computes HMAC)
deploy/local/scripts/sms-callback.sh <notification_id> DELIVERED
```

Expected: status `DELIVERED`, new result message `sequence: 2`. Re-posting → no new result.
Posting with a bad signature → `401`, no state change, `CALLBACK_IGNORED` logged.

### Q8. Crash safety (FR-012, SC-003)

Start a 2,000-message ingest load, `kill -9` the backend mid-run, restart. After ≤ 3 minutes every
accepted notification is in a terminal state (`SELECT status, count(*) … GROUP BY status` has no
RECEIVED/PROCESSED/SENDING older than 3 min), `event_publication` has no incomplete rows, and
Mailpit/WireMock counts never exceed accepted counts (no notification sent twice). Notifications that were
in SENDING at the kill are FAILED `DELIVERY_STATE_UNKNOWN` after the stuck timeout (see Q13).

### Q9. Load (SC-001, SC-005, SC-014)

```bash
# the seeded demo-client is limited to 1000/min: raise it first (provisioning CLI, exits when done)
java -jar backend/target/pnh.jar --spring.profiles.active=local,cli update-client --client-id=demo-client --rate-limit=60000
k6 run deploy/local/k6/send-load.js -e RATE=500 -e DURATION=5m
```

Expected: p95 acknowledgement < 1 s at 500/s; `kafka-consumer-groups --describe` shows per-partition lag
within 2× of the mean when 80% of traffic is EMAIL.

### Q10. Inbox (US6, FR-018a..020)

Send 3 `ORDER_SHIPPED_PUSH` + 1 email to `user_99`.
`GET /api/v1/users/user_99/inbox` → 3 items, `unread_count: 3`.
`PATCH /api/v1/notifications/{id}/read` with `X-User-Id: user_99` → `read_at` set; repeat → same `read_at`.
`POST /bulk-read` with the other 2 + the email id + a random uuid → `updated_count: 2`, `not_found_ids` has 2 ids.
Same PATCH with `X-User-Id: someone_else` → `404`.

### Q11. Console: roles, templates, config audit (US7, US10, FR-021..026, FR-032..035)

1. Log in as `editor`: Templates page works; Channel Config menu absent and `GET /console/api/channel-configs` → `403`.
2. Create EMAIL template with `${name}` in the rich-text editor → placeholder highlighted; preview renders values;
   creating the same code again → "code already in use".
3. Open the same template in two tabs, save in tab A, then save in tab B → "modified by someone else" (409) with latest shown.
4. Log in as `admin`: update SMTP password → displayed as `********`; "Test connection" succeeds against Mailpit;
   Config Change Log shows `CHANNEL_CONFIG UPDATE` by `admin` with `password: ***changed***` and version +1.
5. Log in as `support`: Delivery Audit search by `recipient_target` finds Q6 notification; detail timeline shows retries and provider error.

### Q12. Dashboard (US9, SC-010)

After Q1–Q10, open Dashboard (last 24 h, hourly). Compare with
`SELECT channel_type, status, count(*) FROM pnh_notifications_history WHERE created_at > :clock_now - interval '24 hours' GROUP BY 1,2`
(use the console's current system time from `GET /console/api/admin/clock` as `:clock_now` if the clock is overridden)
→ numbers match exactly (allow ≤ 1 min rollup delay).

## Automated suites

```bash
cd backend && ./mvnw verify          # unit, module, integration (Testcontainers), contract, ArchUnit/Modulith verify, JaCoCo ≥80%
cd frontend && pnpm lint && pnpm test && pnpm e2e
```

### Q13. Stuck notifications & incident alert (US11, FR-039..045, SC-016/017)

Run with `--pnh.delivery.stuck-timeout=1m` and rule `STUCK_DELIVERY_SPIKE` threshold 5 / window 10 min.

1. WireMock SMS stub: delay 60 s. Send 10 SMS; once they are SENDING (`/track`), `kill -9` the backend and restart.
2. Expected: no new request for those ids reaches WireMock after restart (journal), within ~2 min after the
   timeout all 10 are `FAILED` / `DELIVERY_STATE_UNKNOWN` with timeline `SWEPT` (plus `RESUBMISSION_SKIPPED` when the
   registry resubmits before the sweeper runs — with a 1 min stuck timeout the sweeper usually wins), and 10 FAILED
   results are on `pnh.notification.result`.
3. Mailpit receives exactly one alert email to `ops@example.com` ("STUCK_DELIVERY_SPIKE · SMS · 10 in 10 min") with no
   OTP/full phone numbers; console Alerts history shows `FIRED / SENT`. Repeat step 1 within 30 min → no second alert.
4. Post a signed DELIVERED callback for one of them (`sms-callback.sh`) → status `DELIVERED`, new result `sequence: 2`.
5. Console (admin) → Alerts → add `oncall@example.com`, deactivate `ops@example.com`, "Send test alert" → only
   `oncall@` receives it; Config Change Log shows ALERT_RECIPIENT CREATE/UPDATE entries with versions.

### Q14. Redis degradation (constitution v1.1.0, R21/R22)

`docker compose stop redis` while sending 50 req/s for 2 minutes:
intake keeps accepting (rate limit falls back to local buckets), duplicates still rejected via Postgres, sweeper
and alert jobs still run (advisory-lock fallback after 3 skipped runs), health shows `redis: DOWN` and a metric
`pnh_redis_fallback_active=1`. `docker compose start redis` → fallbacks clear. No lost or duplicated notifications.

### Q15. Cache invalidation across replicas (R21)

Start two backend instances (ports 8080/8081). Edit template `NEWSLETTER_EMAIL` in the console; immediately send via
8081 → new content used (Caffeine evicted via Redisson topic within ~1 s).

### Q16. Channel SPI readiness for Zalo (FR-031, R25)

`./mvnw test -Dtest='*ProviderContractTest'` runs the shared contract kit against SMS, Email, Push
adapters and a test-only fake channel `FAKE` registered from test code: sending with `channel_type: FAKE` works end
to end without changes to coreapi/dispatcher/tracking, and a fake capability `requiresExternalTemplate=true` rejects
direct content with `DIRECT_CONTENT_NOT_ALLOWED`.

### Q17. System time override (FR-046, R29)

With profile `local` (`pnh.clock.override-enabled=true`):
1. `PUT /console/api/admin/clock {"mode":"FIXED","fixed_instant":"2027-01-01T08:00:00Z"}` as `admin` → console shows
   the override banner on every page for all roles; a second backend instance reports the same `now` within 1 s.
2. Send a notification → `created_at` = 2027-01-01T08:00Z; an OTP with `expired_minutes=5` gets `expires_at` 08:05Z.
3. `PUT {"mode":"OFFSET","offset":"PT10M"}` → stuck sweeper and OTP expiry act as if 10 minutes passed.
4. Config Change Log shows `CLOCK_OVERRIDE UPDATE` by `admin`. `DELETE` → back to `SYSTEM`.
5. Start with `--spring.profiles.active=prod --pnh.clock.override-enabled=true` → startup fails; with prod defaults the
   endpoint returns 404.
6. `./mvnw test -Dtest=ClockUsageArchTest` passes; adding `LocalDateTime.now()` anywhere in `com.pnh` fails it.
