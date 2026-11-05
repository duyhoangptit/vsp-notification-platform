# Implementation Plan: Project Notification Hub (PNH)

**Branch**: `001-notification-hub` | **Date**: 2026-11-03 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-notification-hub/spec.md` + binding
[technical-input.md](./technical-input.md)

## Summary

A centralized notification hub: registered client systems submit notifications (REST or Kafka,
template mode or direct-content mode), the hub de-duplicates per client request id, renders
templates, applies user channel preferences (OTP/critical bypass), and delivers via isolated SMS,
Email and Push provider adapters with retry, expiry and circuit breaking. Every status transition is
tracked, results are streamed back on Kafka (keyed by `user_id`), Push notifications form an in-app
inbox, and an operator console (React 19 + Mantine) provides dashboard, template authoring, channel
configuration, and Delivery Audit investigation with three roles and a full configuration audit trail.
Notifications whose outcome is unknown after a crash are never re-sent: a sweeper fails them after a
timeout, and incident rules email a configurable list of admin recipients.

Technical approach: one Spring Boot 4 / Java 21 deployable organized as Spring Modulith application
modules with Clean Architecture inside each; the Modulith Event Publication Registry on PostgreSQL
guarantees no event loss between modules and backs result externalization to Kafka; Resilience4j
wraps every provider call; PostgreSQL is the system of record (idempotency keys, partitioned
history, rollups, alert settings); Caffeine caches hot config in-process and Redis (via Redisson)
provides cache invalidation, distributed locks, exact rate limiting, the idempotency fast path and
alert windows/cooldowns — never as source of truth (constitution v1.1.0). Decisions and alternatives are in [research.md](./research.md).

## Technical Context

**Language/Version**: Java 21 (backend); TypeScript 5 / React 19 (frontend)

**Primary Dependencies**: Spring Boot 4.1.x, Spring Modulith 2.x (JDBC event registry, Kafka
externalization), Spring Kafka, Spring Security, Spring Data JPA + Flyway, Thymeleaf (string
templates, restricted), Resilience4j 2.x, Redisson (Redis client: RLock, RRateLimiter, RTopic,
RBucket), Caffeine + Spring Cache, Bucket4j (local rate-limit fallback only), libphonenumber,
OWASP Java HTML Sanitizer, Spring Mail (SMTP → Mailtrap sandbox / Mailpit), Firebase Admin SDK (FCM HTTP v1), HTTP client for
SMS Gateway for Android; SendGrid adapter deferred; Vite, Mantine 8 (core, form, dates,
charts, tiptap, notifications), mantine-datatable, TanStack Query, React Router

**Storage**: PostgreSQL 16+ (config tables, idempotency keys, monthly-partitioned history and
status events, hourly rollups, alert settings/history, event publication registry) — system of
record. Redis 7 (non-authoritative: idempotency fast path, rate limiters, locks, cache invalidation,
alert windows). Kafka for ingest/result streams.

**Testing**: JUnit 5, AssertJ, Spring Modulith test, Testcontainers (PostgreSQL, Kafka, Redis), WireMock,
GreenMail, swagger-request-validator, JSON Schema validator, JaCoCo; Vitest, Testing Library, MSW,
Playwright; k6 for load; separate Maven `performance` profile (`src/performanceTest/java`) for SLO measurements (SC-004/007/010/012/017), run nightly, outside the deterministic `check` gate

**Target Platform**: Linux containers (OCI images) on Kubernetes or VM; 2–4 backend replicas;
console served as static assets behind the same origin

**Project Type**: Web application (backend service + operator console SPA)

**Performance Goals**: 500 accepted notifications/s sustained (SC-005); p95 acknowledgement < 1 s
(SC-001); 99% SENT/FAILED within 30 s (SC-004); OTP p95 hand-off < 5 s under backlog (SC-012);
results published < 5 s after status change (SC-007); dashboard < 3 s for 30 days (SC-010)

**Constraints**: zero duplicate sends for client resubmissions; zero loss across restart;
OTP/secrets never in clear text in logs/console/results; no status regression; channel isolation;
new channel (Zalo) addable as a new adapter only; Kafka key = `user_id`; at-most-once provider
call after crash (stuck → FAILED, no resend); Redis loss must not lose/duplicate notifications;
all current time from the injected `Clock` bean (overridable in non-prod, research R29, constitution v1.2.0)

**Scale/Scope**: ~43 M notifications/day at peak rate, 12-month retention (monthly partitions);
3 channels (+ Zalo pending, adapter-ready); 10 backend modules; ~9 console screens (login,
dashboard, templates list/edit, channel configs, notifications search/detail, config audit log,
alert settings & history)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Gate | Pre-research | Post-design |
|-----------|------|--------------|-------------|
| I. Clean Architecture & Module Boundaries | Modules as Modulith packages, inward deps, provider ports, boundaries verified in CI | PASS — module list from technical input | PASS — R2 module map; provider SPI with nested `provider.*` modules; `ApplicationModules.verify()` test; cross-module only via events/published API |
| II. Test-First & Layered Testing | TDD; unit/module/integration (real PG+Kafka)/contract; ≥80% domain coverage | PASS — planned | PASS — R19 toolchain; contracts in `contracts/` drive contract tests; quickstart Q1–Q12 map to integration/E2E tests |
| III. Reliability by Design | Idempotency, atomic state+event, forward-only status, bounded transient retry, CB, high-cardinality keys, no blind replay after side effect (v1.1.0) | PASS | PASS — R3 key table (+Redis fast path), R4 registry + SENDING-before-call, R23 sweeper + R24 alerts, state machine in data-model (single documented FR-041 exception), R7, R5 |
| IV. Security & Sensitive Data | AuthN everywhere, server-side roles, encrypted secrets, OTP/PII masking, escaped HTML, verified callbacks, dependency scan | PASS | PASS — R11/R12 auth, R13 AES-GCM secrets write-only, R15 masking + encrypted OTP payload, R8 SSTI-safe rendering, callback signatures in contracts |
| V. Observability & Auditability | Structured logs w/ correlation ids, per-channel metrics/tracing, status timeline, config audit columns + append-only log | PASS | PASS — `pnh_notification_events`, R17 audit trail, Micrometer metrics named in quickstart, OTel tracing |
| Tech constraints (v1.1.0) | Only listed technologies; Redis via Redisson non-authoritative; Caffeine in-process | PASS | PASS — R21/R22 degradation paths; no entity data or secrets in Redis |
| Time source (v1.2.0) | All `now` via injected `Clock`; no direct `now()`/SQL `now()`; ArchUnit-enforced | PASS | PASS — R29 `PnhClock` + `ClockUsageArchTest`; runtime override non-prod only |
| VI. Code Quality, Simplicity & Extensibility | Lint/static analysis, YAGNI w/ justified complexity, domain naming, backward-compatible contracts, versioned migrations | PASS with justifications | PASS — see Complexity Tracking; Flyway; `/api/v1` + additive Kafka schemas |

No unjustified violations. Gate: **PASS**.

## Project Structure

### Documentation (this feature)

```text
specs/001-notification-hub/
├── plan.md                 # This file
├── spec.md                 # Feature specification
├── technical-input.md      # Binding technical input from the requester
├── research.md             # Phase 0 output
├── data-model.md           # Phase 1 output
├── quickstart.md           # Phase 1 output
├── contracts/
│   ├── client-api.openapi.yaml
│   ├── console-api.openapi.yaml
│   ├── provider-callbacks.openapi.yaml
│   └── kafka/{README.md, ingest.schema.json, result.schema.json}
├── checklists/requirements.md
└── tasks.md                # Phase 2 output (/speckit-tasks — not created here)
```

### Source Code (repository root)

```text
backend/
├── pom.xml / mvnw / .mvn/wrapper/          # Maven (surefire: *Test, failsafe: *IT)
└── src/
    ├── main/java/com/pnh/
    │   ├── PnhApplication.java
    │   ├── shared/                 # OPEN kernel: ChannelType, Category, NotificationId, Masking, Problem codes, crypto util,
    │   │                           #   infra: CacheConfig (Caffeine + Redisson invalidation), DistributedLock helper, Redis fallbacks
    │   │                           #   time: ClockConfig + PnhClock (single time source, override), ClockOverrideService
    │   ├── coreapi/                # pnh-core-api: REST send/track, Kafka ingest, client auth, rate limit, idempotency
    │   │   ├── domain/  application/  adapter/in/{web,kafka}  adapter/out/persistence
    │   ├── template/               # pnh-template: CRUD, placeholder validation, Thymeleaf rendering, preview
    │   ├── dispatcher/             # pnh-dispatcher: routing, preferences, opt-out/critical rule, OTP lane
    │   ├── provider/               # provider SPI (ChannelProvider, ChannelCapabilities, ChannelRegistry), channel configs (encrypted), test-connection, contract test kit
    │   │   ├── sms/                # pnh-provider-sms   (ANDROID_SMS_GATEWAY + GENERIC_HTTP_SMS adapters)
    │   │   ├── email/              # pnh-provider-email (SMTP adapter → Mailtrap/Mailpit; SendGrid deferred)
    │   │   └── push/               # pnh-provider-fcm   (FCM HTTP v1 adapter)   [pending: zalo/ — adapter only, R25]
    │   ├── tracking/               # pnh-tracking: history, timeline, callbacks, result externalization, stats rollup, search, stuck sweeper
    │   ├── alerting/               # alert recipients/rules, signal windows, evaluation job, AlertMailer, alert history
    │   ├── inbox/                  # Push inbox, read state, bulk read
    │   └── admin/                  # operator auth/roles, console BFF security, config audit log
    ├── main/resources/
    │   ├── application.yml, application-local.yml
    │   └── db/migration/           # Flyway V1__… (partitions, audit columns, registry, shedlock)
    └── test/java/com/pnh/
        ├── architecture/           # Modulith verify + documentation
        ├── <module>/unit|module|integration
        └── contract/               # OpenAPI + Kafka JSON-schema contract tests

frontend/
├── package.json, vite.config.ts, tsconfig.json
└── src/
    ├── app/                        # router, MantineProvider, QueryClient, auth guard by role
    ├── api/                        # typed client generated from contracts/console-api.openapi.yaml
    ├── features/
    │   ├── auth/  dashboard/  templates/ (TiptapPlaceholder extension)  channel-configs/
    │   ├── notifications/ (DataTable search + Timeline detail)  config-change-log/  alerts/ (recipients, rules, history)
    ├── components/  hooks/  utils/ (masking, formatting)
    └── tests/ (vitest) + e2e/ (playwright)

deploy/local/
├── compose.yaml                    # postgres, kafka (KRaft), redis, mailpit, wiremock
├── wiremock/                       # SMS gateway + FCM stubs
├── k6/send-load.js
└── scripts/sms-callback.sh
```

**Structure Decision**: Web application layout (`backend/` + `frontend/`) with a single backend
deployable. The requested `pnh-*` modules map 1:1 to Spring Modulith application modules
(packages) as listed above (research R2); `inbox`, `admin` and `alerting` are added for the
clarified inbox, role/audit and incident-alert scope, `shared` is the open kernel. The pending Zalo
channel is `provider/zalo/` only (R25).

### Key flows (module interaction)

```text
REST/Kafka ─► coreapi: auth → rate limit → validate → render NOW via TemplateApi (or sanitize direct HTML) → OTP expiry/masking
              → insert key+notification(RECEIVED, content, template_version)
              └─ publish NotificationAccepted (registry)                         [same TX]
dispatcher  ◄─ (otp|standard executor) preferences/opt-out → PROCESSED   (no rendering here — fix I1, R8)
              └─ publish NotificationReadyForDelivery(channel)
provider.*  ◄─ status==SENDING already? → skip (no resend, R4) | expiry gate → commit SENDING → Resilience4j(CB+retry+timeout) → adapter
              └─ publish DeliveryAttempted / DeliverySucceeded(SENT) / DeliveryFailed
tracking    ◄─ apply transition (forward only), timeline event, result (@Externalized → pnh.notification.result, key user_id)
tracking    ◄─ provider callbacks → DELIVERED/FAILED → result
inbox       ◄─ SENT for PUSH/STANDARD → read-state row
admin       ◄─ ConfigChanged events (all config modules) → pnh_config_audit_log
tracking    ◄─ sweeper job (Redisson lock, 60 s): stuck SENDING/RECEIVED/PROCESSED/expired OTP → FAILED + result + NotificationStuckFailed
alerting    ◄─ NotificationStuckFailed, ProviderAvailabilityChanged, failure counts, DLT events → Redis windows
alerting    ◄─ evaluation job (60 s) → rule fired & not in cooldown → AlertMailer (active EMAIL config) → pnh_alert_events
all modules ◄─ Redisson topic pnh:cache-invalidation → evict Caffeine entries
```

Status ownership: `tracking` owns `pnh_notifications_history` writes after creation; other modules
request transitions via events, so the forward-only rule lives in one aggregate.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Monthly partitioning of history/events + separate `pnh_request_keys` table | ~43 M rows/day at target load and 12-month retention; drop-partition retention | Unpartitioned table: retention deletes cause vacuum storms and slow audit queries; unique index can't span partitions without partition key |
| Separate OTP Kafka topic + dedicated executor/bulkhead | SC-012 OTP latency under STANDARD backlog; Kafka has no message priority | Single queue: OTP waits behind backlog |
| Redis (Redisson) alongside PostgreSQL | User decision: distributed cache invalidation, locks, exact cluster-wide rate limits, idempotency fast path, alert windows | Postgres-only (advisory locks, approximate local rate limits): workable but no cross-replica cache eviction and approximate limits; Redis kept non-authoritative with fallbacks |
| Two SMS adapters (Android SMS Gateway + generic HTTP) | Free real gateway for v1 (user decision) + hub-defined contract for WireMock/CI and future vendors | One adapter: either no real free delivery or no vendor-neutral test path |
| `alerting` module + sweeper job | User decision: stuck notifications must fail, not hang or resend; admins must be alerted | Rely on external monitoring only: admins wanted in-app managed recipient list |
| Envelope encryption for channel secrets + encrypted OTP payload | Constitution IV; secrets editable in console; OTP needed for retries but must not rest in clear text | Plain DB columns: violates IV; re-rendering from stored variables still stores OTP |
| Hourly rollup table + recompute job | SC-010 (< 3 s, exact) at volume | Live GROUP BY over 30 days of partitions is too slow; counters drift |
| `inbox` and `admin` modules beyond the listed `pnh-*` modules | Clarified scope (Push inbox, roles, config audit) has no natural owner among listed modules | Folding into tracking/coreapi mixes end-user, operator and delivery concerns |

## Phase Outputs

- Phase 0: [research.md](./research.md) — R1–R28, no open NEEDS CLARIFICATION (R26–R28 added by /speckit-analyze fixes).
- Phase 1: [data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md).
- Phase 2: `/speckit-tasks` (not part of this command).

## Risks & Follow-ups

- Confirm Resilience4j Spring Boot 4 starter availability (R1); fallback documented.
- Verify SMS Gateway for Android webhook signing headers and API at implementation (R14).
- Crash-window outcome uncertainty (R4/R23): by user decision such notifications are FAILED
  `DELIVERY_STATE_UNKNOWN` (never resent); some may actually have been delivered. Alerts surface spikes.
- SMS Gateway for Android depends on a physical phone/SIM (throughput a few SMS/min per device,
  carrier limits) — fine for dev/test, not for production volume; production gateway is a later adapter.
- Mailtrap sandbox never delivers to real inboxes (by design) — production ESP to be chosen later.
- Redis outage: rate limits fall back to approximate local buckets, cache invalidation falls back to TTLs,
  jobs fall back to Postgres advisory locks (R11/R21/R22).
- Operator SSO (OIDC) and fine-grained permissions deferred to a later phase (clarification Q3).
