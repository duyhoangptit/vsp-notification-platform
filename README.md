# Project Notification Hub (PNH)

Centralized notification hub: registered client systems submit notifications (REST or Kafka, template or
direct-content mode) that are de-duplicated, rendered and delivered over **SMS, Email and Push**, with retries,
circuit breaking, OTP priority/expiry/masking, user channel preferences, full status tracking with results streamed
back on Kafka, an in-app Push inbox, incident alerts and an operator console.

- Specification & design: [specs/001-notification-hub](specs/001-notification-hub/) (spec, plan, research R1–R29,
  data model, contracts)
- Run & validate locally: [quickstart.md](specs/001-notification-hub/quickstart.md)
- Operations (alerts, stuck policy, Redis fallbacks, partitions, provisioning CLI): [docs/operations.md](docs/operations.md)

| Path | Content |
|------|---------|
| `backend/` | Spring Boot 4.1 / Java 21 / Spring Modulith service — Maven (`./mvnw verify`) |
| `frontend/` | Operator console — React 19, Mantine 8, TanStack Query (`pnpm dev`, `pnpm test`, `pnpm e2e`) |
| `deploy/local/` | Docker Compose stack (Postgres 16, Kafka KRaft, Redis 7, Mailpit, WireMock), k6 load test, helper scripts |
| `.github/workflows/` | CI (`ci.yml`) and nightly SLO/load suite (`performance.yml`) |

## Architecture

One deployable organised as Spring Modulith application modules (Clean Architecture inside each:
`domain` → `application` → `adapter.in/out`). Modules talk only through published APIs and events persisted in the
**event publication registry** (PostgreSQL), so a state change and the event announcing it commit atomically.
Boundaries are verified by `ModularityTest` in every build.

| Module | Responsibility |
|--------|----------------|
| `coreapi` | REST `send`, Kafka ingest (signed messages), client auth (HTTP Basic), rate limit, idempotency, intake validation, rendering at acceptance, OTP protection |
| `template` | Templates: placeholder validation (`${name}` only), safe Thymeleaf rendering, sanitization, preview, console CRUD |
| `dispatcher` | Routing, user preferences / opt-out (OTP & critical bypass), OTP lane |
| `provider` | Provider SPI (`ChannelProvider`, capabilities, registry), channel configs (AES-GCM secrets), Resilience4j, delivery orchestration (SENDING committed before the call) |
| `provider.email` / `.sms` / `.push` | SMTP (Mailtrap/Mailpit); generic HTTP SMS + SMS Gateway for Android (+ signed callbacks); FCM HTTP v1 |
| `tracking` | Status machine & timeline, result stream (`pnh.notification.result`, key `user_id`), delivery reports, Delivery Audit search, dashboard rollups, stuck sweeper, partitions & retention |
| `inbox` | In-app inbox for PUSH STANDARD notifications |
| `alerting` | Incident rules, signal windows, evaluation, alert mailer, history |
| `admin` | Operator auth & roles, config audit log, clock override API, provisioning CLI |
| `shared` (open) | Value types, masking, crypto, errors (RFC 9457), time source (`PnhClock`), Redis/locks/cache infrastructure |

```text
REST / Kafka ─► coreapi (auth, rate limit, idempotency, validate, render, OTP mask) ─► RECEIVED + NotificationAccepted
dispatcher   ◄─ preferences/opt-out ─► PROCESSED + NotificationReadyForDelivery          (OTP: own executor/bulkhead)
provider     ◄─ SENDING committed ─► Bulkhead → CircuitBreaker → TimeLimiter → adapter (retry 3× on transient errors)
tracking     ◄─ SENT / DELIVERED / FAILED ─► timeline + result message (Kafka, at-least-once, event_id + sequence)
```

Reliability rules: idempotent on `client_id + client_request_id` (Postgres authoritative, Redis fast path), forward-only
status, at-most-once provider calls after a crash (stuck → `FAILED/DELIVERY_STATE_UNKNOWN`, never re-sent), Redis is
never the system of record, all time from the injected `Clock` (overridable outside production).

## Run locally

```bash
docker compose -f deploy/local/compose.yaml up -d
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local      # http://localhost:8080
cd frontend && pnpm install && pnpm dev                                    # http://localhost:5173
```

The `local` profile seeds `demo-client`/`demo-secret`, operators `admin`/`admin123`, `editor`/`editor123`,
`support`/`support123`, four templates and channel configs pointing to Mailpit (http://localhost:8025) and WireMock.

```bash
curl -u demo-client:demo-secret -XPOST localhost:8080/api/v1/notifications/send -H 'Content-Type: application/json' \
  -d '{"client_request_id":"hello-1","user_id":"user_99","channel_type":"EMAIL","recipient_target":"user@example.com",
       "template_code":"NEWSLETTER_EMAIL","variables":{"name":"An"}}'
```

## Tests

```bash
cd backend && ./mvnw verify            # unit + module (surefire *Test), integration + contract (failsafe *IT, Testcontainers),
                                       # ArchUnit/Modulith, JaCoCo ≥ 80 % on domain/application, Spotless, Error Prone
cd backend && ./mvnw -Pperformance verify   # SLO suite (SC-004/007/010/012/017), report in target/reports/performance
cd backend && ./mvnw -Psecurity-scan verify # OWASP dependency-check (fails on CVSS ≥ 7)
cd frontend && pnpm lint && pnpm typecheck && pnpm test && pnpm e2e   # e2e needs the local stack + backend
k6 run deploy/local/k6/send-load.js -e RATE=500 -e DURATION=5m
```

Docker is required for the integration tests (Testcontainers starts PostgreSQL, Kafka and Redis).

## Real providers (free tiers)

- **Email — Mailtrap Email Sandbox**: create an inbox, copy its SMTP credentials; in the console → Channel configs → SMTP
  set host `sandbox.smtp.mailtrap.io`, port `2525`, STARTTLS on, username, replace the password, *Test connection*.
- **SMS — SMS Gateway for Android**: install the app on an Android phone, enable the cloud (or local) server, note the
  username/password; add an `ANDROID_SMS_GATEWAY` config (mode `cloud`, or `local` + `base_url` of the phone) and make it
  active. Register the webhook `https://<hub>/api/v1/callbacks/sms/android-sms-gateway` for `sms:sent`, `sms:delivered`,
  `sms:failed` and store the app's webhook signing key as secret `webhook_signing_key`.
- **Push — Firebase Cloud Messaging**: create a Firebase project and a service account key (JSON); on the FCM config set
  `project_id`, clear `base_url`, upload the JSON as `service_account_json`.

Production: provide `PNH_MASTER_KEY` (base64 of 32 random bytes) and DB/Kafka/Redis endpoints via environment,
build the console into the jar with `./mvnw -Pprod package` (after `pnpm build`), never enable `pnh.clock.override-enabled`.

## Spec Kit notes

```text
/speckit-constitution Create principles focused on code quality, testing, and maintainability.
/speckit-specify Build a photo organizer with albums grouped by date and a tile preview of each album.
/speckit-plan Use Vite with vanilla JavaScript. Keep images local and store metadata in SQLite.
/speckit-tasks
/speckit-implement
/speckit-converge
```
