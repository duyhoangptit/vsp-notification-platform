# Phase 0 Research: Project Notification Hub

**Feature**: [spec.md](./spec.md) · **Binding technical input**: [technical-input.md](./technical-input.md) · **Date**: 2026-11-03

Each item: **Decision** · **Rationale** · **Alternatives considered**. Versions are "latest stable at
implementation time" within the stated major line; exact pins are set in the build files during T001.

---

## R1. Runtime & framework versions

- **Decision**: Java 21 (LTS), Spring Boot 4.1.x (Spring Framework 7), Spring Modulith 2.x,
  Spring Kafka 4.x, Resilience4j 2.x (Spring Boot 4–compatible starter), Maven (with Maven Wrapper) single
  deployable `backend`. Frontend: React 19, TypeScript 5, Vite, Mantine 8.x (satisfies "v7+").
- **Rationale**: Mandated by technical input/constitution. Mantine 8 is the current major line and
  is required by the current `mantine-datatable` and `@mantine/tiptap` releases.
- **Alternatives**: Gradle Kotlin DSL (faster incremental builds, convention plugins) — replaced by
  Maven per user decision 2026-11-03 (team familiarity, standard Spring Boot parent POM). Mantine 7 (older; no advantage).
- **Risk**: Resilience4j's Spring Boot 4 auto-configuration artifact must be confirmed at T001. If
  unavailable, use `resilience4j-circuitbreaker`/`-retry` core modules with programmatic
  registries configured from `application.yml` (same behavior, ~1 config class).

## R2. Mapping "pnh-*" modules onto Spring Modulith

- **Decision**: One Spring Boot application; each `pnh-*` module is an application-module package
  under `com.pnh`: `coreapi`, `template`, `dispatcher`, `provider` (parent: provider SPI + channel
  configuration) with nested modules `provider.sms`, `provider.email`, `provider.push`
  (future `provider.zalo`), `tracking`, `inbox`, `admin`, and an OPEN `shared` kernel.
  Every module uses Clean Architecture sub-packages: `domain`, `application` (use cases + ports),
  `adapter.in` (web/kafka/listeners), `adapter.out` (persistence/providers).
  Boundaries are verified by `ApplicationModules.of(PnhApplication.class).verify()` in CI.
- **Rationale**: Modulith modules are packages, not build artifacts; hyphenated names are not valid
  Java packages. Nested modules keep each provider isolated while sharing one SPI.
- **Alternatives**: Maven multi-module project per module (stronger compile-time isolation, but
  Modulith already enforces boundaries and the extra build wiring violates Simplicity).

## R3. Idempotency store

- **Decision**: PostgreSQL only. A dedicated non-partitioned table `pnh_request_keys`
  (PK `client_id, client_request_id` → `notification_id`) is inserted in the same transaction as
  the notification (`INSERT … ON CONFLICT DO NOTHING RETURNING`); on conflict the existing
  notification is returned. Keys are kept for the 12-month retention period.
  **Fast path (added 2026-11-03)**: before touching Postgres, coreapi does a Redisson
  `RBucket.setIfAbsent("idem:{client}:{reqId}", notificationId, 5 min)`. A hit returns the cached
  notification id immediately (absorbs retry storms / double clicks without DB conflicts). A miss
  or Redis outage falls through to the Postgres insert, which remains authoritative.
- **Rationale**: Spec assumption makes request IDs unique for the full retention period (needed for
  tracking), which a 5-minute Redis TTL alone cannot provide, so Redis is only an accelerator
  (constitution: Redis never system of record). A separate table is needed because the history
  table is partitioned (R9) and Postgres unique constraints on partitioned tables must include the
  partition key.
- **Alternatives**: Redis-only `SET NX EX 300` (rejected: window too short, loses guarantee if Redis
  is flushed); unique index on history (rejected: incompatible with monthly partitioning).

## R4. Reliable internal flow & no event loss

- **Decision**: Spring Modulith Event Publication Registry (JDBC, Postgres) for all cross-module
  events: `NotificationAccepted` → `NotificationPrepared` → `NotificationDispatched`
  → provider outcome events → tracking. Listeners are transactional and asynchronous.
  Settings: `republish-outstanding-events-on-restart=false` (multi-instance safe) plus a scheduled
  job (Redisson-lock-guarded, R22) that resubmits incomplete publications older than 2 min via
  `IncompletePublications`; completion mode `DELETE` with completed-event archive disabled.
- **Rationale**: Gives atomic "state change + event" (constitution III) without a hand-rolled outbox.
  The scheduled resubmission avoids every instance replaying the same events on restart.
- **Alternatives**: Hand-written outbox table + poller (more code, same guarantee).
- **Crash window — DECIDED by user (2026-11-03): at-most-once to the provider.** The provider
  listener commits `SENDING` (attempt n) in its own transaction *before* calling the provider. A
  resubmitted event that finds the notification already in `SENDING` does **not** call the provider;
  it completes the publication and leaves resolution to the stuck-notification sweeper (R23).
  Events for `RECEIVED`/`PROCESSED` (no provider call yet) are safely resubmitted. Trade-off: a
  message that did reach the user may be reported FAILED `DELIVERY_STATE_UNKNOWN`; a later verified
  delivery callback corrects it to DELIVERED (spec FR-041).

## R5. Kafka ingestion, results and partitioning

- **Decision**:
  - Topics: `pnh.notification.ingest` (STANDARD), `pnh.notification.ingest.otp` (OTP lane),
    `pnh.notification.result`, `pnh.notification.ingest.DLT`.
  - Message key = `user_id` on every topic; headers `client_id`, `channel_type`, `category`,
    `event_id`. Recommended ≥ 12 partitions per topic.
  - Ingest consumers: `@KafkaListener`, manual ack after the intake transaction commits; validation
    failures publish a FAILED result (if client/request id are known) else go to the DLT.
  - Results: published via Spring Modulith event externalization
    (`@Externalized("pnh.notification.result::#{userId()}")`), so results ride the same
    registry/outbox. Payload includes `event_id` (UUID) and `sequence` per notification so clients
    can drop duplicates and detect order.
  - Kafka client authentication (revised, fix U2): a Kafka consumer cannot see the producer's SASL
    principal, so identity is bound per message: header `pnh-signature: v1=<hex HMAC-SHA256(kafka_signing_key,
    client_id + "." + client_request_id + "." + hex(SHA-256(value bytes)))>`. The consumer looks up the ACTIVE
    client by the `client_id` header, decrypts its signing key and verifies the signature in constant time;
    missing/invalid → DLT `UNAUTHORIZED`. Replays are harmless (idempotency on client_request_id). Broker-side
    SASL/SCRAM + ACLs (only registered client principals may WRITE to the ingest topics, only PNH may READ) is an
    infrastructure layer configured in deployment, not checked by the app. Local/CI: `pnh.kafka.signature-required`
    may be `false` for ad-hoc kcat use; ITs run with it `true` and sign messages with a test helper (no SASL needed).
- **Rationale**: User-specified preference (high-cardinality key avoids hot partitions, keeps
  per-notification ordering → FR-036..038). Kafka has no per-message priority, so OTP gets its own
  lane (SC-012).
- **Alternatives**: key by `channel_type` (rejected: ≤4 hot partitions); key by `notification_id`
  (even spread but loses per-user order); a single topic with priority headers (not supported by
  Kafka consumers).

## R6. OTP priority inside the application

- **Decision**: Separate asynchronous executors: `otpExecutor` (dedicated threads, small queue) and
  `standardExecutor`. The dispatcher and provider listeners for OTP events use distinct event types
  (`OtpNotificationAccepted`, …) bound to `otpExecutor`; per-provider Bulkhead limits keep STANDARD
  load from starving OTP calls.
- **Rationale**: Event registry listeners are executor-driven; separate pools give OTP head-of-line
  isolation end to end (Kafka lane + executor + bulkhead).
- **Alternatives**: priority queue executor (complex, starvation tuning); separate deployable (too
  heavy for v1).

## R7. Retry, backoff, expiry and circuit breaker (Resilience4j)

- **Decision**: Per provider instance:
  - Retry: `maxAttempts=4` (1 initial + 3 retries), exponential backoff 1s → 2s → 4s with jitter,
    retry only on `TransientProviderException` (HTTP 5xx, 429, timeouts, I/O). Permanent errors map
    to `PermanentProviderException` and are not retried.
  - TimeLimiter: 5 s per call. Bulkhead: per-provider concurrent-call cap.
  - CircuitBreaker: `TIME_BASED` sliding window 10 s, `failureRateThreshold=50`,
    `minimumNumberOfCalls=20`, `waitDurationInOpenState=30s`, `permittedNumberOfCallsInHalfOpenState=5`,
    automatic transition to half-open. Open-circuit rejections count as a failed attempt with
    `PROVIDER_UNAVAILABLE`.
  - Before every attempt (initial and retry) the OTP `expires_at` is checked; if elapsed →
    FAILED `OTP_EXPIRED`, no call.
  - Each attempt is recorded as a timeline event.
- **Rationale**: Matches technical input (3 retries, 50% / 10 s) and SC-006 (resume < 1 min).
  `minimumNumberOfCalls` prevents tripping on 2 failures out of 3 during low traffic.
- **Alternatives**: durable delayed retry via DB `next_attempt_at` (survives crash between
  retries, but total in-process retry time is ≤ ~8 s and R4 resubmission already covers crashes).

## R8. Template engine (Thymeleaf) and placeholder syntax

- **Decision**: Operators author `${variable}` placeholders. On save, the template module validates
  that every `${…}` matches `^[a-zA-Z_][a-zA-Z0-9_]{0,63}$` (anything else is rejected) and
  extracts the required-variable list. At render time placeholders are rewritten to Thymeleaf
  inline expressions: Email HTML → `[[${vars.name}]]` (escaped, `TemplateMode.HTML`);
  SMS/Push/subject → `[(${vars.name})]` in `TemplateMode.TEXT`. The engine uses a restricted
  setup: no `T()` type expressions possible because raw expressions never reach the engine,
  template resolver is string-only (no file/URL resolvers), compiled templates cached by
  `template_code + version`.
  **Render timing (fix I1, 2026-11-03)**: rendering happens synchronously in coreapi intake (inside the
  acceptance transaction, before the 202), so content reflects the template version active at acceptance
  and no template version history is needed. The dispatcher no longer renders; it applies preferences and
  routes. Cost: one cached compile + render per request (sub-millisecond for typical templates).
  **Placeholders inside HTML attributes (fix H1)**: Thymeleaf HTML mode does not process inline `[[…]]`
  expressions in attribute values, so attribute placeholders are rewritten separately: an attribute whose value
  contains `${x}` (allowed attributes: `href`, `src`, `alt`, `title`) becomes `th:<attr>="|…${vars.x}…|"` (literal
  substitution, Thymeleaf escapes attribute values). Rules: for `href`/`src` the **rendered** value must be an
  absolute `http`, `https` or `mailto` URL (`src`: `https` or `data:image/*`), otherwise intake rejects with
  `VALIDATION_ERROR` naming the variable; placeholders in any other attribute (e.g. `style`, `on*`) are rejected
  when the template is saved (`INVALID_PLACEHOLDER`).
- **Rationale**: Keeps the user-friendly `${name}` syntax from the spec, gets escaping for free,
  and eliminates SpEL/SSTI injection since only whitelisted identifiers are ever passed through.
- **Alternatives**: Mustache/Handlebars (simpler, logic-less, but technical input mandates
  Thymeleaf); passing raw operator text to Thymeleaf (rejected: SSTI risk).

## R9. History volume, partitioning and retention

- **Decision**: `pnh_notifications_history` and `pnh_notification_events` are range-partitioned
  monthly on `created_at`; retention job detaches/drops partitions older than 12 months.
  Indexes per partition: `(user_id, created_at desc)`, `(recipient_target)`, `(status)`,
  `(client_id, created_at)`, and partial index `(user_id, created_at desc) WHERE channel_type='PUSH'`
  for the inbox. Read state uses a plain table keyed by `notification_id` (no FK to the
  partitioned table; integrity enforced in the inbox module).
- **Rationale**: At 500 notifications/s ≈ 43 M rows/day, unpartitioned tables make retention
  deletes and audit queries impractical. Dropping partitions is O(1).
- **Alternatives**: unpartitioned + batch delete (vacuum storms); time-series DB (extra infra).

## R10. Dashboard aggregation

- **Decision**: Rollup table `pnh_notification_stats_hourly(bucket_hour, channel_type, client_id,
  status, count)` maintained by a Redisson-lock-guarded job (R22) every minute that **recomputes** the
  current and previous hour from history (`INSERT … ON CONFLICT DO UPDATE`). The dashboard reads
  rollups; daily granularity sums hours. Status counted = latest status of each notification.
- **Rationale**: Exact (recompute is idempotent, no counter drift → SC-010), avoids hot-row
  contention of per-event counters, 30-day query reads ≤ 720 × channels rows (< 3 s).
- **Alternatives**: live `GROUP BY` on history (too slow at volume); per-event counter upserts
  (contention, drift on retries).

## R11. Client authentication & rate limiting

- **Decision**: REST client auth via HTTP Basic (`client_id:client_secret`) over TLS; secrets
  stored as bcrypt; successful verifications cached in-process (Caffeine, 5 min, key =
  client_id + SHA-256(secret)) to avoid bcrypt cost per request. Client scopes: `SEND`,
  `TRACK`, `USER_DELEGATE` (inbox/preferences on behalf of users).
  Rate limiting (revised 2026-11-03): Redisson `RRateLimiter` per client
  (`OVERALL`, rate = `rate_limit_per_min` per 1 minute) — exact across replicas. If Redis is
  unavailable, fall back to a local Bucket4j bucket with `rate_limit_per_min / replica_count`
  (fail-open-but-bounded) and emit a metric/alert.
- **Rationale**: Redis is now part of the stack (user decision), so the cluster-wide limit becomes
  exact at no extra infrastructure cost; local fallback keeps intake available during Redis outages.
- **Alternatives**: OAuth2 client credentials with an authorization server (better long term,
  heavier for v1 — upgrade path noted); local-only buckets (approximate).

## R12. Operator console authentication & roles

- **Decision**: Local operator accounts (`pnh_operators`, bcrypt passwords), Spring Security
  session login (HttpOnly, SameSite=Lax cookie, CSRF token for SPA), roles `ADMIN`,
  `TEMPLATE_EDITOR`, `SUPPORT_VIEWER` enforced with method security on console endpoints;
  accounts/roles seeded via a CLI/SQL migration (no console user management in v1).
- **Rationale**: Matches clarification Q3 (low-effort v1). Same-origin SPA avoids token storage.
- **Alternatives**: OIDC/Keycloak SSO (recommended later phase; adds an IdP dependency now).

## R13. Secrets for channel configuration

- **Decision**: Provider settings stored in `pnh_channel_configs`; secret fields encrypted with
  AES-256-GCM (envelope: data key per row, master key from environment/KMS); API returns secrets
  as `"********"` + `has_value`; updates accept new secrets write-only. Saving publishes
  `ChannelConfigChanged`; the provider module rebuilds its client and resets its circuit breaker.
  "Test connection" calls a provider-specific non-sending check (SMTP `NOOP`/auth, SendGrid
  API key scopes call, FCM OAuth token fetch / dry-run send, SMS gateway balance/ping endpoint).
- **Rationale**: FR-023, constitution IV.
- **Alternatives**: external secret manager only (better ops; can be added behind the same port).

## R14. Provider adapters and delivery callbacks

- **Decision**:
  - Email: `EmailSender` port. v1 primary adapter = **SMTP** (Spring Mail), pointed at
    **Mailtrap Email Sandbox** (`sandbox.smtp.mailtrap.io`, free tier; captures mail, never delivers
    to real inboxes) for dev/staging and Mailpit for offline local/CI. SMTP has no delivery callback
    → SENT is final. SendGrid Web API adapter (signed Event Webhook → DELIVERED/BOUNCED) stays in the
    design but is **deferred** (built when a production ESP is chosen). Active adapter chosen in
    channel config.
  - Push: FCM HTTP v1 API (service account JSON). FCM reports acceptance only → SENT is final;
    `UNREGISTERED`/`INVALID_ARGUMENT` token → FAILED `INVALID_RECIPIENT`.
  - SMS (revised 2026-11-03 — user chose a free gateway for v1):
    - Default real gateway: **SMS Gateway for Android** (open-source `android-sms-gateway`, Apache-2.0;
      sends through an Android phone's SIM; free apart from carrier SMS cost). Adapter
      `ANDROID_SMS_GATEWAY`: REST send API with basic auth; webhooks `sms:sent` / `sms:delivered` /
      `sms:failed` mapped to SENT/DELIVERED/FAILED; webhook signature verification per its docs
      (verify exact header/algorithm at implementation). Works in local mode (phone on LAN) or via its
      cloud relay.
    - Local/CI: WireMock stub implementing the generic HMAC callback contract (`GENERIC_HTTP_SMS`).
    - **Verified at implementation (2026-11-04, docs.sms-gate.app)**: send = `POST {base}/3rdparty/v1/messages`
      (cloud base `https://api.sms-gate.app`; local server base configurable), Basic auth, body
      `{"id": <client-supplied id>, "textMessage": {"text": …}, "phoneNumbers": ["+…"], "withDeliveryReport": true}` —
      PNH sends its `notification_id` as `id`, so webhooks match before SENT is committed. Webhook envelope
      `{deviceId, event, id, payload: {messageId, recipient, …, reason?}, webhookId}`; signature headers `X-Signature`
      (hex HMAC-SHA256 with the signing key over `raw_body + X-Timestamp`) and `X-Timestamp` (Unix seconds, UTC);
      PNH enforces a ±5 min window. Signing key stored as channel-config secret `webhook_signing_key`.
    - Module placement (implementation note): provider callback controllers live in `provider.sms` (they need the
      adapter's verifier and channel secrets) and apply verified reports through the published `TrackingApi`
      (`applyDeliveryReport`), avoiding a provider↔tracking module cycle; the delivery hand-off events
      `NotificationReadyForDelivery` / `OtpNotificationReadyForDelivery` are owned by `provider` for the same reason.
    - Alternative free option: Twilio trial (free credit, verified numbers only) — adapter not built in
      v1; production Vietnamese brandname gateways (Viettel/VNPT/eSMS…) are later adapters behind the
      same port.
  - Callbacks: `POST /api/v1/callbacks/{channel}/{provider}`; verified, idempotent, never moves
    status backwards.
- **Rationale**: FR-009, FR-017, FR-031 (new channel = new adapter).
- **Zalo (pending, adapter-ready)** — see R25.

## R15. Sensitive data handling (OTP, PII)

- **Decision**: For OTP notifications the stored `compiled_content` is the masked version
  (OTP value replaced with `******`); the real content is stored in `payload_encrypted`
  (AES-GCM) only until terminal status, then nulled. Template-mode OTP: the variables flagged as
  OTP are those named in template metadata `otp_variable` (default `otp_code`); direct mode uses
  `otp_value`. A Logback masking converter redacts OTP values, secrets and recipient addresses;
  tracking API/console never return unmasked OTP. Recipient addresses are masked in console lists
  (`u***@example.com`, `+84*****678`).
- **Rationale**: FR-030, FR-027, constitution IV.

- **Placement (fix C5)**: `OtpContentProtector` lives in `com.pnh.shared.security` (OPEN module) and is invoked by
  coreapi `SubmitNotificationUseCase` right after rendering and **before** `createReceived`, in the acceptance
  transaction. The plaintext OTP is therefore never written to `compiled_content`, `subject`, `title` or logs at any
  time; `SensitiveValues` is registered before any logging in the request. The dispatcher and provider modules only
  ever see the masked content plus `payload_encrypted`.

## R16. Validation details

- **Decision**: Phone numbers validated/normalized to E.164 with libphonenumber (default region VN);
  emails by RFC 5322 practical regex + length ≤ 254; FCM tokens non-empty ≤ 4096 chars.
  SMS max rendered length 1,600 chars; Push body ≤ 4 KB payload; Email subject ≤ 255. Bulk-read
  max 500 ids. Error responses use RFC 9457 Problem Details with a stable `code`.

## R17. Configuration audit trail

- **Decision**: JPA auditing (`@CreatedBy`, `@LastModifiedBy`, `@CreatedDate`, `@LastModifiedDate`)
  and `@Version` (optimistic locking) on all configuration entities; an `AuditorAware` resolves
  operator username / `client_id[:user_id]` / `system`. A `ConfigChanged` domain event per change
  is consumed by the `admin` module to append `pnh_config_audit_log` rows (diff computed from
  before/after snapshots; secret fields recorded as `***changed***`). Console updates send
  `version`; mismatch → HTTP 409 with the latest representation.
- **Alternatives**: Hibernate Envers (full row history tables; heavier and stores secrets unless
  carefully excluded).

## R18. Frontend stack

- **Decision**: Vite + React 19 + TypeScript; Mantine 8 (`core`, `hooks`, `form`, `dates`,
  `notifications`, `charts`, `tiptap`), `mantine-datatable`, TanStack Query, React Router.
  Custom Tiptap mark/decoration extension highlights `${var}` placeholders and offers an
  "Insert variable" menu. Tests: Vitest + Testing Library + MSW; Playwright for E2E.
- **Rationale**: Matches technical input; "Mantine Data Table" = `mantine-datatable`.

## R19. Testing & quality toolchain

- **Decision**: JUnit 5, AssertJ, Spring Modulith test (`@ApplicationModuleTest`, `Scenario`),
  Testcontainers (PostgreSQL, Kafka), WireMock (SMS/SendGrid/FCM), GreenMail (SMTP),
  OpenAPI request/response validation in contract tests (`swagger-request-validator`) and JSON
  Schema validation for Kafka payloads, JaCoCo (≥ 80% domain/application), Spotless
  (palantir-java-format), Error Prone, OWASP dependency-check / Trivy in CI; ESLint + Prettier
  + `tsc --noEmit` for frontend. Load test: k6 for SC-001/SC-005/SC-012.

## R20. Performance budget sanity check

- **Decision/finding**: Per notification ≈ 10 DB writes (key, history, ~5 events, registry rows,
  status updates) → ~5,000 writes/s at 500 notifications/s; feasible on a single tuned Postgres
  (batching via JDBC `reWriteBatchedInserts`, HikariCP pool ~50) with 2–4 app replicas.
  Validated by k6 in quickstart scenario Q9; if not met, first lever is moving status-event inserts
  to batched async writes.

## R21. Caching (Caffeine L1 + Redis invalidation)

- **Decision** (user-requested 2026-11-03): Spring Cache abstraction with **Caffeine** as the
  in-process cache for hot, read-mostly data; **Redisson** pub/sub topic `pnh:cache-invalidation`
  broadcasts evictions so every replica drops stale entries immediately after a config change.

  | Cache | Key | TTL | Invalidated by |
  |-------|-----|-----|----------------|
  | `templates` (parsed + compiled) | `code:version` | 1 h | TemplateChanged |
  | `templateByCode` (active pointer) | `code` | 5 min | TemplateChanged |
  | `clientAuth` (verified credential) | `clientId:sha256(secret)` | 5 min | ClientChanged |
  | `clients` | `clientId` | 5 min | ClientChanged |
  | `channelConfigs` (decrypted provider clients) | `channel` | until change | ChannelConfigChanged |
  | `userPreferences` | `userId` | 60 s | PreferenceChanged |
  | `alertRules` / `alertRecipients` | `all` | 5 min | AlertSettingsChanged |

  Redis is used as a shared cache only for the idempotency fast path (R3) and alert cooldown keys
  (R24); entity data is not stored in Redis (avoids serialization of secrets, keeps Redis optional).
- **Rationale**: Removes per-request DB/bcrypt cost from the hot path (SC-001, SC-005); pub/sub
  invalidation keeps console edits effective "without restart" across replicas within ~1 s.
- **Alternatives**: Redis as L2 for all caches (extra serialization, secrets at rest in Redis);
  Caffeine only with TTL (stale for up to TTL after an edit).
- **Degradation**: Redis down → invalidation messages lost; TTLs bound staleness (max 5 min, 60 s
  for preferences); a metric/alert is raised.

## R22. Distributed locks (Redisson)

- **Decision**: Redisson `RLock` (with watchdog lease) for: scheduled jobs (event resubmission,
  stuck sweeper, stats rollup, alert evaluation, partition maintenance) — one runner cluster-wide,
  `tryLock(0, …)` so other replicas skip; per-notification callback processing lock
  `lock:notif:{id}` (short lease) to serialize concurrent callbacks/sweeper on the same
  notification. Correctness never depends on the lock alone: status updates also use conditional
  SQL (`UPDATE … WHERE status = :expected`) so a lost lock cannot cause regression.
- **Rationale**: Replaces ShedLock (one less dependency now that Redisson exists); defense-in-depth
  with optimistic SQL guards.
- **Alternatives**: ShedLock on Postgres (fine, but redundant with Redisson); Postgres advisory locks.
- **Degradation**: Redis down → jobs skip their run (logged + metric); after 3 consecutive skips the
  job falls back to a Postgres advisory lock so stuck detection and alerting keep working.

## R23. Stuck-notification sweeper (user decision 2026-11-03)

- **Decision**: Job every 60 s (R22 lock):
  - `SENDING` with `updated_at < clock.instant() − stuck_timeout` (cutoff computed in Java from the `Clock` bean and bound as a SQL parameter) (default 5 min; validated at startup to be
    > max attempt time ≈ 4 × (5 s timeout) + 7 s backoff) → FAILED `DELIVERY_STATE_UNKNOWN`.
  - `RECEIVED`/`PROCESSED` with `updated_at < clock.instant() − processing_timeout` (default 15 min) → FAILED
    `PROCESSING_TIMEOUT`.
  - Any non-terminal OTP with `expires_at < now` → FAILED `OTP_EXPIRED`.
  - Transitions use conditional updates, write a timeline event `SWEPT`, publish a FAILED result, and
    emit `NotificationStuckFailed(channel)` for alerting. Processed in batches of 500 per run.
  - Late verified callback "delivered" on `FAILED/DELIVERY_STATE_UNKNOWN` → DELIVERED (FR-041).
- **Rationale**: Implements the user's at-most-once choice: no blind resend, bounded uncertainty,
  visibility through alerts.
- **Alternatives**: resend after timeout (rejected by user: duplicate risk); query provider for
  status (not supported by SMTP/FCM; can be added per adapter later via an optional SPI method
  `queryStatus`).

## R24. Incident alerting (user decision 2026-11-03)

- **Decision**: New module `alerting`.
  - Settings: `pnh_alert_recipients` (emails) and `pnh_alert_rules` (rule code, enabled, threshold,
    window, cooldown, min sample), both config records with audit columns, editable by ADMIN.
  - Signals: `NotificationStuckFailed`, provider circuit state transitions (Resilience4j event
    publisher → `ProviderAvailabilityChanged`), per-channel failure counts from tracking, DLT
    publishes from coreapi. Counted in Redis sliding windows (Redisson `RScoredSortedSet` by
    timestamp per rule+channel) with a Postgres query fallback.
  - Evaluation: job every 60 s; fire when value ≥ threshold; cooldown key
    `alert:cooldown:{rule}:{channel}` (Redis TTL = cooldown, also persisted in `pnh_alert_events`
    so cooldown survives Redis loss); send "resolved" once when the condition clears.
  - Delivery: dedicated `AlertMailer` using the **active EMAIL channel config** directly through the
    email provider port (not through the notification pipeline, so no opt-out, no idempotency/rate
    limit interplay). If the email provider is the failing component, the alert is recorded as
    `SEND_FAILED` and retried on the next evaluation; a fallback static SMTP (application config) may
    be configured for alerts.
  - History: `pnh_alert_events` + console screen "Alerts" (ADMIN).
- **Rationale**: FR-042..FR-045; simple rules first, no external monitoring dependency, while
  Micrometer metrics remain available for Prometheus/Grafana alerting in ops.
- **Alternatives**: Prometheus Alertmanager only (needs ops stack; admins wanted in-app list of
  recipients); sending alerts through the PNH pipeline (circular when the pipeline is the problem).

## R25. Channel SPI designed for Zalo (pending)

- **Decision**: `provider` module exposes the SPI:
  ```text
  ChannelProvider { channelType(); providerKey(); capabilities(); validateRecipient(target);
                    send(DeliveryRequest) -> DeliveryResult; testConnection(config);
                    parseCallback(request) -> Optional<DeliveryReport>  (optional) }
  ChannelCapabilities { supportsDirectContent, requiresExternalTemplate, supportsDeliveryCallback,
                        supportsHtml, maxContentLength, recipientType (PHONE|EMAIL|DEVICE_TOKEN|ZALO_USER) }
  ```
  A `ChannelRegistry` discovers provider beans; intake validates `channel_type` against registered,
  configured channels, and checks direct-content / external-template capabilities generically.
  Templates get an optional `external_template_ref` JSONB (e.g., `{ "zns_template_id": "…" }`),
  and channel configs are generic JSONB settings + encrypted secrets, so Zalo needs **no schema,
  contract or core-module change** — only `provider/zalo/` (adapter + settings validator) and a
  console form for its settings.
- **Zalo specifics recorded for later**: ZNS uses pre-approved templates (`requiresExternalTemplate=true`,
  `supportsDirectContent=false`), recipient is a phone number, OAuth access/refresh token with
  expiry (refresh handled inside the adapter, token cached via R21), delivery webhooks available.
- **v1 scope**: SPI + capabilities + `external_template_ref` column + a contract test kit that any
  adapter must pass (`ChannelProviderContractTest`); no Zalo code.

## R26. Result delivery semantics (fix I2)

- **Decision**: Results are produced once per outcome (single logical event via the registry) and delivered
  **at-least-once** to Kafka; each carries `event_id` (UUID, stable across re-publication of the same
  registry entry) and `sequence` (per notification). Spec FR-016 states this contract; clients de-duplicate
  on `event_id`. Kafka idempotent producer (enable.idempotence=true) removes producer-retry duplicates within
  a session; only registry re-publication after a crash can duplicate.
- **Alternatives**: Kafka transactions + DB outbox exactly-once (complex, cross-resource transactions not
  possible with Postgres anyway).

## R27. OTP expiry derivation (fix I3)

- **Decision**: Templates of category OTP may declare `expiry_variable`. At intake: `expires_at` from request
  if present (must be future); else if template has `expiry_variable` and the variable is an integer 1..1440 →
  `expires_at = accepted_at + minutes`; else reject `OTP_EXPIRY_REQUIRED`. Non-integer/out-of-range value →
  `VALIDATION_ERROR` on that variable. Keeps the original technical-input payload (`expired_minutes: "5"`)
  valid without client changes. Direct-content OTP still requires explicit `expires_at`.

## R28. Direct-content HTML sanitization (fix C3)

- **Decision**: OWASP Java HTML Sanitizer with a policy allowing common formatting (p, br, b, i, u, strong,
  em, span, div, h1–h6, ul/ol/li, table/thead/tbody/tr/td/th, a[href http/https/mailto], img[src https/
  data:image], style attributes limited to safe CSS properties). Removed: script, style elements, iframes,
  forms, on* handlers, `javascript:` URLs. Applied at intake to direct EMAIL bodies; if output differs from
  input, timeline event `CONTENT_SANITIZED` is recorded (no rejection). Template HTML authored in the console
  is sanitized with the same policy on save.
- **Placeholder-safe template sanitization (fix H1)**: on template save, every `${…}` placeholder is first replaced
  by an opaque token (e.g. `https://pnh.placeholder/0`, which survives URL attribute policies), the HTML is sanitized,
  then tokens are restored. A unit test asserts `<a href="${reset_link}">` and `<img src="${logo}">` survive save
  byte-for-byte; render-time URL checks (R8) then validate the actual values.
- **Rationale**: Constitution IV intent (no HTML injection via the hub) while keeping direct mode usable.

## R29. Single time source: `Clock` bean (user requirement 2026-11-03)

- **Decision**:
  - `ClockConfig` (only class allowed to call `Clock.systemUTC()`) exposes a `@Bean Clock` of type
    `PnhClock extends java.time.Clock` that delegates to a base clock and supports modes
    `SYSTEM`, `FIXED(instant)`, `OFFSET(duration)`; zone UTC (display zone handled in UI).
  - Startup configuration: `pnh.clock.mode=system|fixed|offset`, `pnh.clock.fixed-instant`,
    `pnh.clock.offset` (ISO-8601 duration), `pnh.clock.override-enabled` (default `false`; `true` only in
    local/dev/test/UAT profiles; startup fails if enabled with profile `prod`).
  - Runtime override (non-prod only): `GET/PUT/DELETE /console/api/admin/clock` (ADMIN). The value is stored
    in Redis key `pnh:clock:override` and broadcast on Redisson topic `pnh:clock` so every replica switches
    within ~1 s; new replicas read the key at startup; Redis loss → fall back to configured mode with a
    warning metric `pnh_clock_override_active`. Each change emits `ConfigChanged(entity_type=CLOCK_OVERRIDE)`.
    Console shows a banner "System time overridden: …" whenever mode ≠ SYSTEM.
  - Usage rules: domain/application code receives `Clock` via constructor injection and calls
    `X.now(clock)` / `clock.instant()`; JPA auditing uses a `DateTimeProvider` backed by the bean;
    Hibernate `@CreationTimestamp/@UpdateTimestamp` are forbidden (they use the JVM clock); SQL never uses
    `now()`/`CURRENT_TIMESTAMP` for logic — cutoffs are computed in Java and bound as parameters; DB column
    defaults remain only as a safety net (application always sets values). Scheduled job *triggers* still
    fire on real time (Spring scheduler), but every job reads "now" from the bean.
  - Enforcement: ArchUnit test `ClockUsageArchTest` forbids calls to the no-arg `now()` of
    `LocalDate`, `LocalDateTime`, `LocalTime`, `Instant`, `ZonedDateTime`, `OffsetDateTime`, `Year`,
    `YearMonth`, plus `System.currentTimeMillis()`, `new java.util.Date()`, `Clock.systemUTC/systemDefaultZone()`
    outside `ClockConfig`; a source scan test fails on `now()`/`CURRENT_TIMESTAMP` inside SQL strings under
    `adapter/out` (Flyway migrations excluded). Tests use `MutableClock`/`Clock.fixed` instead of sleeps.
- **Partitions under override (fix H2)**: before an override is applied, `ClockOverrideService` synchronously calls
  `pnh_ensure_month_partitions` for the month of the new "now" plus the next 3 months (history and events); if partition
  creation fails the override is rejected (422) and the clock is unchanged. `PartitionMaintenanceJob` always ensures
  partitions around BOTH the overridden clock month and the real month. No DEFAULT partition is used (it would block
  later creation of a partition whose range already has rows in the default).
- **Retention under override (fix H3)**: `pnh.retention.skip-when-clock-overridden` (default `true`): while the clock
  mode ≠ SYSTEM the retention job logs a warning, records metric `pnh_retention_skipped_total{reason="clock_override"}`
  and deletes nothing; testers who want to exercise retention set it to `false` explicitly.
- **UUIDv7 time component (fix U4)**: `NotificationId` generation lives in `com.pnh.shared.domain` and takes its
  millisecond timestamp from the `Clock` bean (same source as `created_at`), plus a monotonic counter and random
  bits (RFC 9562 method 1), so IDs stay unique and sortable within a clock mode; ordering across an override switch
  follows the overridden time by design.
- **Tests (fix C9)**: time-dependent tests advance time with `PnhClock` (FIXED/OFFSET) or `MutableClock`, never with
  sleeps or by editing timestamps in the database.
- **Not affected by override (documented)**: Kafka record timestamps, Redis TTLs, Resilience4j sliding
  windows, HTTP session expiry, provider-side timestamps, Spring scheduler triggers.
- **Alternatives**: libfaketime / OS clock change (affects whole JVM and infra, unsafe, not per-env);
  `Instant.now()` everywhere + mocking statics in tests (non-deterministic, no runtime override).
