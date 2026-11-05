---

description: "Task list for Project Notification Hub (PNH)"
---

# Tasks: Project Notification Hub (PNH)

**Input**: Design documents from `/specs/001-notification-hub/`

**Prerequisites**: plan.md, spec.md, research.md (R1–R25), data-model.md, contracts/, quickstart.md, technical-input.md

**Tests**: INCLUDED — constitution principle II (Test-First, NON-NEGOTIABLE). Within each story, test tasks come first and MUST fail before implementation.

**Organization**: Tasks are grouped by user story (spec.md) in priority order: P1 → US1, US2, US3; P2 → US4, US5, US7, US8, US11; P3 → US6, US9, US10.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: User story from spec.md (US1…US11)

## Path Conventions (web app, plan.md)

- Backend main: `backend/src/main/java/com/pnh/<module>/{domain,application,adapter/in,adapter/out}/…`
- Backend resources: `backend/src/main/resources/…`; migrations `backend/src/main/resources/db/migration/`
- Backend tests: `backend/src/test/java/com/pnh/<module>/{unit,module,integration}/…`, contract tests `backend/src/test/java/com/pnh/contract/…`
- Frontend: `frontend/src/…`, unit tests next to code (`*.test.tsx`), E2E `frontend/e2e/…`
- Local infra: `deploy/local/…`
- JSON field names in APIs are snake_case exactly as in `contracts/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and tooling

- [X] T001 Create repository skeleton per plan.md "Source Code" tree: `backend/`, `frontend/`, `deploy/local/{wiremock,k6,scripts}/`, root `.gitignore`, `.editorconfig`, `README.md` (links to specs/001-notification-hub/quickstart.md)
- [X] T002 Create Maven build in `backend/pom.xml` (parent `spring-boot-starter-parent` 4.1.x, `maven.compiler.release=21`, Spring Modulith BOM imported in `<dependencyManagement>`, library versions as `<properties>`) plus Maven Wrapper (`backend/mvnw`, `backend/mvnw.cmd`, `backend/.mvn/wrapper/maven-wrapper.properties`); surefire runs unit/module tests (`*Test`), failsafe runs integration tests (`*IT`) in `verify`; dependencies from plan.md Technical Context: Spring Boot 4.1.x (web, validation, security, data-jpa, actuator, mail, thymeleaf, kafka, cache), Spring Modulith 2.x BOM (starter-core, starter-jpa, events-jdbc, events-kafka, starter-test), Flyway + PostgreSQL driver, Resilience4j 2.x (Spring Boot 4 starter — if unavailable use core `resilience4j-circuitbreaker/retry/timelimiter/bulkhead` per research R1), Redisson (Spring Boot starter), Caffeine, OWASP Java HTML Sanitizer, Bucket4j core, libphonenumber, Firebase Admin SDK, Jackson, Micrometer Prometheus + OpenTelemetry bridge, logstash-logback-encoder; test: JUnit 5, AssertJ, ArchUnit, Testcontainers (postgresql, kafka, redis via GenericContainer), WireMock, GreenMail, swagger-request-validator, networknt json-schema-validator, Awaitility
- [X] T003 [P] Configure quality plugins in `backend/pom.xml` `<build><plugins>` (shared settings in `<pluginManagement>`): `spotless-maven-plugin` (palantir-java-format, `check` bound to `verify`), Error Prone via `maven-compiler-plugin` `annotationProcessorPaths` + `-Xplugin:ErrorProne`, `jacoco-maven-plugin` (`prepare-agent`, `prepare-agent-integration`, `merge`, `report`, `check` with line coverage ≥ 0.80 for packages `com.pnh.*.domain` and `com.pnh.*.application`), `dependency-check-maven` with `failBuildOnCVSS=7` in a `security-scan` profile
- [X] T004 [P] Scaffold frontend in `frontend/package.json`, `frontend/vite.config.ts` (dev proxy `/console/api` → `http://localhost:8080`), `frontend/tsconfig.json`, `frontend/index.html`, `frontend/src/main.tsx` with React 19, TypeScript 5, Mantine 8 (`@mantine/core`, `hooks`, `form`, `dates`, `notifications`, `charts`, `tiptap`), `mantine-datatable`, `@tiptap/react` + starter-kit, `@tanstack/react-query`, `react-router`, `openapi-typescript`, `openapi-fetch`, `dayjs`
- [X] T005 [P] Configure frontend lint/test tooling: `frontend/eslint.config.js`, `frontend/.prettierrc`, `frontend/vitest.config.ts`, `frontend/src/tests/setup.ts` (Testing Library + MSW server), `frontend/playwright.config.ts`; npm scripts `lint`, `typecheck`, `test`, `e2e`, `gen:api`
- [X] T006 [P] Create `deploy/local/compose.yaml` with postgres:16, Kafka (KRaft, single node), redis:7, mailpit (SMTP 1025, UI 8025), wiremock (8089, mounts `deploy/local/wiremock`), plus a one-shot `kafka-init` service creating topics `pnh.notification.ingest` (12 partitions), `pnh.notification.ingest.otp` (6), `pnh.notification.result` (12), `pnh.notification.ingest.DLT` (3)
- [X] T007 [P] Create WireMock stubs in `deploy/local/wiremock/mappings/`: `sms-send-ok.json` (POST /sms/send → 200 `{"message_id":"…"}`), `sms-send-503.json` (scenario-based transient failures), `sms-send-400-invalid.json` (`INVALID_NUMBER`), `fcm-send-ok.json` (POST /v1/projects/*/messages:send), `fcm-unregistered.json` (404 `UNREGISTERED`), `fcm-oauth-token.json`
- [X] T008 [P] Create CI workflow `.github/workflows/ci.yml`: backend `./mvnw -B verify` (Testcontainers enabled), frontend `pnpm lint && pnpm typecheck && pnpm test`, dependency scan job, artifact upload of JaCoCo/test reports
- [X] T009 Create `backend/src/main/java/com/pnh/PnhApplication.java` (`@SpringBootApplication`, `@EnableScheduling`, `@EnableAsync`, `@Modulithic(sharedModules = "shared")`) and `backend/src/main/resources/application.yml` + `application-local.yml` with datasource, Kafka, Redis, mail, `pnh.*` property skeleton (`pnh.delivery.stuck-timeout=5m`, `pnh.delivery.processing-timeout=15m`, `pnh.ratelimit.replica-count=1`, `pnh.crypto.master-key` from env)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure every story depends on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Tests first (foundational)

- [X] T010 [P] Create architecture test `backend/src/test/java/com/pnh/architecture/ModularityTest.java`: `ApplicationModules.of(PnhApplication.class).verify()` and generate Documenter output to `backend/target/modulith-docs`
- [X] T011 [P] Create Testcontainers base `backend/src/test/java/com/pnh/support/IntegrationTestBase.java` (PostgreSQL 16, Kafka, Redis 7 via `@ServiceConnection`/dynamic properties, reusable containers) and `backend/src/test/java/com/pnh/support/TestDataFactory.java`
- [X] T012 [P] Copy contracts into test resources (`backend/src/test/resources/contracts/` ← `specs/001-notification-hub/contracts/**`) via a `maven-resources-plugin` `copy-resources` execution bound to the `generate-test-resources` phase in `backend/pom.xml`, and create `backend/src/test/java/com/pnh/contract/ContractSupport.java` (OpenAPI request/response validator for MockMvc + JSON-schema validator for Kafka payloads)
- [X] T013 [P] Unit tests for notification state machine in `backend/src/test/java/com/pnh/tracking/unit/NotificationStatusTest.java`: allowed `RECEIVED→PROCESSED|FAILED`, `PROCESSED→SENDING|FAILED`, `SENDING→SENT|FAILED`, `SENDING→DELIVERED|FAILED` only when the trigger is a verified provider callback (fix I4), `SENT→DELIVERED|FAILED`; `DELIVERED`/`FAILED` terminal except `FAILED(DELIVERY_STATE_UNKNOWN)→DELIVERED`; every other transition rejected
- [X] T014 [P] Unit tests for masking utilities in `backend/src/test/java/com/pnh/shared/unit/MaskingTest.java`: OTP value → `******`, email `user@example.com` → `u***@example.com`, phone `+84901234678` → `+84*****678`, token truncation
- [X] T015 [P] Unit tests for envelope encryption in `backend/src/test/java/com/pnh/shared/unit/EnvelopeEncryptorTest.java` (AES-256-GCM round trip, tamper detection, key id recorded)
- [X] T016 [P] Unit tests for resilience policy factory in `backend/src/test/java/com/pnh/provider/unit/ProviderResilienceFactoryTest.java`: retry maxAttempts=4 (1+3), backoff 1s→2s→4s with jitter, retries only `TransientProviderException`, CircuitBreaker TIME_BASED window 10 s, failureRateThreshold 50, minimumNumberOfCalls 20, waitDurationInOpenState 30 s, permittedNumberOfCallsInHalfOpenState 5, TimeLimiter 5 s
- [X] T017 [P] Integration test for distributed lock + fallback in `backend/src/test/java/com/pnh/shared/integration/DistributedLockServiceIT.java` (single runner across two lock clients; after 3 consecutive Redis failures falls back to Postgres advisory lock)
- [X] T018 [P] Integration test for config auditing in `backend/src/test/java/com/pnh/admin/integration/ConfigAuditTrailIT.java`: creating/updating an auditable entity sets `created_by/updated_by`, increments `version`, stale version update throws optimistic-lock → mapped to 409 `VERSION_CONFLICT`, and an append-only `pnh_config_audit_log` row is written with changed fields (secret fields as `"***changed***"`)
- [X] T019 [P] Contract test `backend/src/test/java/com/pnh/contract/ConsoleAuthContractTest.java`: `POST /console/api/auth/login` (200 with `Me`, 401 Problem), `POST /console/api/auth/logout` (204), `GET /console/api/auth/me` (200, 401 when no session) validated against contracts/console-api.openapi.yaml via ContractSupport (T012)
- [X] T020 [P] Integration test `backend/src/test/java/com/pnh/admin/integration/SecurityChainsIT.java`: `/api/v1/**` without credentials → 401, wrong secret → 401, missing scope → 403 `SCOPE_DENIED`; `/console/api/**` without session → 401, wrong role → 403 `FORBIDDEN`, POST without `X-XSRF-TOKEN` → 403; `/api/v1/callbacks/**` reachable without credentials (handler-level verification)
- [X] T021 [P] Integration test `backend/src/test/java/com/pnh/tracking/integration/StatusTransitionServiceIT.java`: conditional update succeeds only when current status equals `expected` (concurrent transitions → exactly one wins); timeline event written in the same transaction (rollback leaves neither); `result_sequence` increments by 1 on each SENT/DELIVERED/FAILED and `NotificationOutcome` is published once per outcome; callback-triggered `SENDING→DELIVERED` allowed, non-callback `SENDING→DELIVERED` rejected; conditional SENT after DELIVERED stores only `provider_message_id` and publishes nothing; `payload_encrypted` nulled on terminal status
- [X] T022 [P] Integration test `backend/src/test/java/com/pnh/shared/integration/IncompletePublicationResubmitterIT.java`: a listener that throws once leaves an incomplete `event_publication`; after it is older than 2 min (injected `Clock`) the resubmitter re-delivers it and the publication completes; with two resubmitter instances only one runs per cycle (DistributedLockService); publications younger than 2 min are not touched
- [X] T023 [P] Integration test `backend/src/test/java/com/pnh/shared/integration/CacheInvalidationBusIT.java`: two Spring contexts sharing Redis; evicting `templateByCode:X` on context A removes it from context B's Caffeine within 1 s; with Redis stopped, local eviction still works and entries expire by TTL
- [X] T024 [P] Integration test `backend/src/test/java/com/pnh/tracking/integration/PartitionMaintenanceIT.java`: `pnh_ensure_month_partitions(3)` creates missing monthly partitions for `pnh_notifications_history` and `pnh_notification_events`, is idempotent, and an insert with `created_at` in month+2 succeeds; job skips when lock is held elsewhere
- [X] T025 [P] Unit test `backend/src/test/java/com/pnh/provider/unit/ChannelRegistryTest.java`: discovers provider beans by channel/provider_key; unknown channel → not registered (`UNSUPPORTED_CHANNEL` at intake); registered but no active config → `CHANNEL_NOT_CONFIGURED`; duplicate provider_key per channel fails fast at startup; capabilities exposed per channel
- [X] T026 [P] Test `backend/src/test/java/com/pnh/shared/integration/ContextPropagationIT.java` (G2, constitution V): a request with MDC `client_request_id` and an active trace publishes an event handled on `otpExecutor` and on `standardExecutor` → listener sees the same MDC values and the same trace id (child span), including after Modulith resubmission (trace id stored/restored or new linked span with `notification_id` attribute)
- [X] T027 [P] Integration test `backend/src/test/java/com/pnh/shared/integration/ObservabilityMetricsIT.java` (G2, constitution V): `/actuator/prometheus` exposes `resilience4j_circuitbreaker_state{name=<provider>}`, `resilience4j_retry_calls_total`, `pnh_notifications_total{channel,status}`, `pnh_dispatch_latency_seconds{category}`, `pnh_redis_fallback_active`; forcing a provider circuit open changes the state gauge
- [X] T028 [P] Unit test `backend/src/test/java/com/pnh/shared/unit/SensitiveDataMaskingConverterTest.java`: log lines containing emails, phone numbers, values registered in `SensitiveValues` (OTP) and JSON fields named `password`/`secret`/`api_key`/`service_account_json` are masked; holder is cleared after the request/async task
- [X] T029 [P] Architecture test `backend/src/test/java/com/pnh/architecture/ClockUsageArchTest.java` (constitution VI, research R29): ArchUnit rule — no class in `com.pnh..` except `com.pnh.shared.time.ClockConfig` calls the no-arg `now()` of `LocalDate`, `LocalDateTime`, `LocalTime`, `Instant`, `ZonedDateTime`, `OffsetDateTime`, `Year`, `YearMonth`, nor `System.currentTimeMillis()`, `new java.util.Date()`, `Clock.systemUTC()`, `Clock.systemDefaultZone()`; no use of Hibernate `@CreationTimestamp`/`@UpdateTimestamp`; plus a source scan failing on `now()` or `CURRENT_TIMESTAMP` inside Java string literals under `**/adapter/out/**` (Flyway SQL files excluded)
- [X] T030 [P] Unit test `backend/src/test/java/com/pnh/shared/unit/PnhClockTest.java`: mode SYSTEM delegates to base clock; FIXED returns the configured instant; OFFSET adds the duration to base time (negative allowed); reset returns to SYSTEM; switching is thread-safe; `withZone` keeps the override; `ClockProperties` validation (FIXED needs `fixed-instant`, OFFSET needs ISO-8601 `offset`)
- [X] T031 [P] Integration test `backend/src/test/java/com/pnh/admin/integration/ClockOverrideIT.java`: with `pnh.clock.override-enabled=false` `/console/api/admin/clock` PUT/DELETE → 404; enabled: ADMIN `PUT {"mode":"FIXED","fixed_instant":"2027-01-01T08:00:00Z"}` → `GET` on a second app context returns the same `now` within 1 s (Redisson topic), non-ADMIN PUT → 403, invalid body → 422; JPA auditing `created_at`/`updated_at` of a config entity equal the fixed instant; `pnh_config_audit_log` has `CLOCK_OVERRIDE` entry; DELETE → SYSTEM; app context with profile `prod` and override-enabled=true fails to start; override to `2020-01-15T00:00:00Z` (a month with no partition) → partitions for 2020-01..2020-04 are created before the switch, then a send succeeds and its row lands in 2020-01; simulated partition-creation failure → 422 and the clock stays unchanged
- [X] T032 [P] Unit test `backend/src/test/java/com/pnh/shared/unit/ProblemDetailsFormatTest.java` (MockMvc standalone with a dummy controller): `PnhException` → `application/problem+json` with `type`, `title`, `status`, stable `code`; Bean Validation errors → 422 `VALIDATION_ERROR` with `errors[{field,message}]`; `MISSING_VARIABLES` carries `missing_variables`; optimistic-lock → 409 `VERSION_CONFLICT` with `current`; unexpected exception → 500 without stack trace or sensitive data
- [X] T033 [P] Integration test `backend/src/test/java/com/pnh/shared/integration/KafkaInfrastructureIT.java`: producer publishes with key = `user_id` and headers (`client_id`, `channel_type`, `event_id`), idempotent producer settings active (`acks=all`, `enable.idempotence=true`), manual-ack consumer does not commit before the handler returns, deserialization failure goes to `pnh.notification.ingest.DLT` with `pnh-error-code`/`pnh-error-message` headers
- [X] T034 [P] Integration test `backend/src/test/java/com/pnh/provider/integration/ChannelConfigProviderIT.java`: returns decrypted settings+secrets of the single active config per channel; inactive configs ignored; no active config → `CHANNEL_NOT_CONFIGURED`; result cached (one DB read for repeated calls) and evicted on `ChannelConfigChanged` (also from another context via invalidation bus); tampered `secrets_encrypted` → startup/read fails closed with a clear error, never returns partial secrets

### Module structure & shared kernel

- [X] T035 Create module `package-info.java` files with `@ApplicationModule` for `com.pnh.coreapi`, `template`, `dispatcher`, `provider`, `provider.sms`, `provider.email`, `provider.push` (nested modules), `tracking`, `inbox`, `admin`, `alerting`, and `@ApplicationModule(type = OPEN)` for `com.pnh.shared` under `backend/src/main/java/com/pnh/*/package-info.java`
- [X] T036 [P] Create shared value types in `backend/src/main/java/com/pnh/shared/domain/`: `ChannelType` (open value object validated against registry, constants SMS/EMAIL/PUSH), `Category` (STANDARD, OTP), `ContentMode` (TEMPLATE, DIRECT), `NotificationStatus` (RECEIVED, PROCESSED, SENDING, SENT, DELIVERED, FAILED), `NotificationId` (UUIDv7 generator per RFC 9562 taking its millisecond timestamp from the injected `Clock` + monotonic counter + random bits; unit test `backend/src/test/java/com/pnh/shared/unit/NotificationIdTest.java` asserting uniqueness of 100k ids under a FIXED clock and time component equal to `clock.millis()`), `ErrorCode` enum with all codes from contracts/client-api.openapi.yaml `Problem.code` incl. `DELIVERY_STATE_UNKNOWN`, `PROCESSING_TIMEOUT`
- [X] T037 [P] Implement masking utilities `backend/src/main/java/com/pnh/shared/domain/Masking.java` (make T014 pass)
- [X] T038 [P] Implement `backend/src/main/java/com/pnh/shared/crypto/EnvelopeEncryptor.java` (AES-256-GCM, per-record data key, master key from `pnh.crypto.master-key`) (make T015 pass)
- [X] T039 [P] Implement RFC 9457 error handling `backend/src/main/java/com/pnh/shared/web/GlobalProblemHandler.java` + `PnhException` (code, status, field errors, `missing_variables`, `current` for VERSION_CONFLICT) (make T032 pass)
- [X] T040 [P] Structured logging: `backend/src/main/resources/logback-spring.xml` (JSON via logstash encoder, MDC keys `notification_id`, `client_request_id`, `client_id`), `backend/src/main/java/com/pnh/shared/logging/CorrelationFilter.java`, and `SensitiveDataMaskingConverter.java` masking email/phone patterns and any value registered in a request-scoped `SensitiveValues` holder (make T028 pass)
- [X] T041 Implement single time source in `backend/src/main/java/com/pnh/shared/time/`: `ClockProperties` (`pnh.clock.mode=system|fixed|offset`, `fixed-instant`, `offset` ISO-8601 duration, `override-enabled` default false), `PnhClock extends java.time.Clock` (UTC, modes SYSTEM/FIXED/OFFSET, atomic switch, `reset()`), `ClockConfig` (`@Bean Clock` — the ONLY place calling `Clock.systemUTC()`; fail startup if `override-enabled` with active profile `prod`); every component that needs time gets `Clock` by constructor injection and uses `X.now(clock)` / `clock.instant()` (make T029, T030 pass)

### Database

- [X] T042 Create `backend/src/main/resources/db/migration/V1__config_tables.sql` exactly per data-model.md: `pnh_clients` (client_id VARCHAR(50) PK, client_name VARCHAR(100) NOT NULL, client_secret_hash VARCHAR(255) NOT NULL, rate_limit_per_min INT NOT NULL DEFAULT 1000, scopes VARCHAR(100) NOT NULL DEFAULT 'SEND,TRACK', allow_direct_content BOOLEAN NOT NULL DEFAULT TRUE, kafka_signing_key_encrypted BYTEA NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'), `pnh_templates` (template_code VARCHAR(100) PK, template_name VARCHAR(150) NOT NULL, channel_type VARCHAR(20) NOT NULL, category VARCHAR(20) NOT NULL DEFAULT 'STANDARD', is_critical BOOLEAN NOT NULL DEFAULT FALSE, otp_variable VARCHAR(64) NULL, expiry_variable VARCHAR(64) NULL, subject_template VARCHAR(255) NULL, title_template VARCHAR(255) NULL, body_template TEXT NOT NULL, external_template_ref JSONB NULL, required_variables TEXT[] NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'), `pnh_user_preferences` (id BIGSERIAL PK, user_id VARCHAR(100) NOT NULL, channel_type VARCHAR(20) NOT NULL, is_enabled BOOLEAN NOT NULL DEFAULT TRUE, UNIQUE(user_id, channel_type)), `pnh_channel_configs` (id BIGSERIAL PK, channel_type, provider_key VARCHAR(50), is_active BOOLEAN NOT NULL DEFAULT FALSE, settings JSONB NOT NULL, secrets_encrypted BYTEA NULL, secrets_key_id VARCHAR(100) NULL, last_test_at TIMESTAMPTZ, last_test_result VARCHAR(500); UNIQUE(channel_type, provider_key); unique partial index on channel_type WHERE is_active), `pnh_operators` (username VARCHAR(100) PK, display_name VARCHAR(150), password_hash VARCHAR(255) NOT NULL, role VARCHAR(30) NOT NULL CHECK IN ('ADMIN','TEMPLATE_EDITOR','SUPPORT_VIEWER'), status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'), `pnh_alert_recipients` (id BIGSERIAL PK, email VARCHAR(254) NOT NULL UNIQUE, display_name VARCHAR(150) NULL, is_active BOOLEAN NOT NULL DEFAULT TRUE), `pnh_alert_rules` (rule_code VARCHAR(50) PK, enabled BOOLEAN NOT NULL DEFAULT TRUE, threshold NUMERIC(10,2) NOT NULL, window_minutes INT NOT NULL CHECK 1..1440, min_sample INT NULL, cooldown_minutes INT NOT NULL DEFAULT 30 CHECK 5..1440); every table above gets `created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by VARCHAR(100) NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by VARCHAR(100) NOT NULL, version BIGINT NOT NULL DEFAULT 0`; plus `pnh_config_audit_log` (id BIGSERIAL PK, entity_type VARCHAR(50) NOT NULL, entity_id VARCHAR(150) NOT NULL, action VARCHAR(20) NOT NULL, version BIGINT NOT NULL, changed_fields JSONB, actor VARCHAR(100) NOT NULL, occurred_at TIMESTAMPTZ NOT NULL; index (entity_type, entity_id, occurred_at desc)) and `REVOKE UPDATE, DELETE` on it from the app role
- [X] T043 Create `backend/src/main/resources/db/migration/V2__transactional_tables.sql` per data-model.md: `pnh_request_keys` (PK (client_id, client_request_id), notification_id UUID NOT NULL, created_at), partitioned-by-month `pnh_notifications_history` (PK (notification_id, created_at); `user_id`, `recipient_target`, `channel_type`, `compiled_content` nullable with `CHECK (status = 'FAILED' AND error_code IS NOT NULL OR (user_id IS NOT NULL AND recipient_target IS NOT NULL AND channel_type IS NOT NULL AND compiled_content IS NOT NULL))` (fix U1); all columns incl. recipient_target VARCHAR(4096), category, content_mode, template_code NULL, template_version, subject/title VARCHAR(255), compiled_content TEXT NOT NULL, payload_encrypted BYTEA, push_data JSONB, expires_at, opt_out_overridden BOOLEAN NOT NULL DEFAULT FALSE, status VARCHAR(30) NOT NULL DEFAULT 'RECEIVED', retry_count INT NOT NULL DEFAULT 0, provider_key, provider_message_id VARCHAR(255), error_code VARCHAR(50), error_message TEXT, result_sequence INT NOT NULL DEFAULT 0, sending_started_at; indexes (client_id, client_request_id), (user_id, created_at desc), (recipient_target), (status, updated_at), (client_id, created_at), (provider_key, provider_message_id), partial (updated_at) WHERE status IN ('RECEIVED','PROCESSED','SENDING'), partial (user_id, created_at desc) WHERE channel_type='PUSH' AND category='STANDARD'), partitioned `pnh_notification_events` (event_type incl. `SWEPT`, `RESUBMISSION_SKIPPED`, `CONTENT_SANITIZED`; index (notification_id, created_at)), `pnh_notification_read_states`, `pnh_notification_stats_hourly` (PK (bucket_hour, channel_type, client_id, status)), `pnh_alert_events` (index (rule_code, channel_type, occurred_at desc))
- [X] T044 Create `backend/src/main/resources/db/migration/V3__modulith_event_publication.sql` (Spring Modulith JDBC `event_publication` schema for PostgreSQL matching the Modulith 2.x version in use)
- [X] T045 Create `backend/src/main/resources/db/migration/V4__partition_functions.sql` (function `pnh_ensure_month_partitions(base_date date, months_ahead int)` creating monthly partitions for history/events; create current + next 2 months) and `backend/src/main/java/com/pnh/tracking/adapter/out/PartitionMaintenanceJob.java` (daily, lock-guarded: ensure 3 months ahead) (make T024 pass) — current time only from the injected `Clock` (cutoffs computed in Java and bound as SQL parameters; no SQL `now()`); ensures partitions around BOTH the (possibly overridden) clock month and the real month; `pnh_ensure_month_partitions` takes a base date parameter
- [X] T046 Create seed migration `backend/src/main/resources/db/migration/V5__seed_alert_rules.sql` inserting rules `STUCK_DELIVERY_SPIKE` (threshold 10, window 10), `PROVIDER_UNAVAILABLE` (threshold 2 minutes, window 2), `CHANNEL_FAILURE_RATE` (threshold 30 percent, window 10, min_sample 50), `INGEST_REJECTED_SPIKE` (threshold 20, window 10), all cooldown 30, actor `system`; and local-only seed `backend/src/main/resources/db/local/R__local_seed.sql` (enabled via `spring.flyway.locations` in application-local.yml) with client `demo-client`/bcrypt(`demo-secret`) scopes `SEND,TRACK,USER_DELEGATE`, operators `admin`/`editor`/`support` (bcrypt of `admin123`/`editor123`/`support123`), templates `OTP_VERIFICATION` (EMAIL, OTP, otp_variable `otp_code`), `OTP_SMS` (SMS, OTP), `ORDER_SHIPPED_PUSH` (PUSH, STANDARD), `NEWSLETTER_EMAIL` (EMAIL, STANDARD), channel configs SMTP→mailpit, GENERIC_HTTP_SMS→wiremock, FCM→wiremock, alert recipient `ops@example.com`

### Auditing, caching, locking, messaging

- [X] T047 Implement config auditing base in `backend/src/main/java/com/pnh/shared/audit/`: `AuditableConfigEntity` (@MappedSuperclass with @CreatedDate/@CreatedBy/@LastModifiedDate/@LastModifiedBy/@Version), `PnhAuditorAware` (operator username | `clientId:userId` | `system`), `ConfigChanged` event (entity_type, entity_id, action, version, before/after snapshot, secret field names), `ChangeDiff` utility; enable JPA auditing in `backend/src/main/java/com/pnh/shared/config/JpaConfig.java` with a `DateTimeProvider` backed by the `Clock` bean (no JVM clock)
- [X] T048 Implement audit writer `backend/src/main/java/com/pnh/admin/application/ConfigAuditRecorder.java` (`@ApplicationModuleListener` on `ConfigChanged` → insert `pnh_config_audit_log`) and map `ObjectOptimisticLockingFailureException` to 409 `VERSION_CONFLICT` in GlobalProblemHandler (make T018 pass)
- [X] T049 [P] Implement Redis infrastructure in `backend/src/main/java/com/pnh/shared/infra/`: `RedissonConfig.java`, `RedisAvailability.java` (health indicator + gauge `pnh_redis_fallback_active`), `DistributedLockService.java` (`tryRunExclusive(jobName, Runnable)` with Redisson RLock watchdog, Postgres `pg_try_advisory_lock` fallback after 3 consecutive Redis failures) (make T017 pass)
- [X] T050 [P] Implement caching in `backend/src/main/java/com/pnh/shared/infra/CacheConfig.java` (Caffeine caches per research R21 table: `templates` 1 h, `templateByCode` 5 min, `clientAuth` 5 min, `clients` 5 min, `channelConfigs` no TTL, `userPreferences` 60 s, `alertSettings` 5 min) and `CacheInvalidationBus.java` (publish/subscribe Redisson `RTopic` `pnh:cache-invalidation`, evict local Caffeine entries on message) (make T023 pass)
- [X] T051 Implement runtime override `backend/src/main/java/com/pnh/shared/time/ClockOverrideService.java` (store JSON state in Redis key `pnh:clock:override`, broadcast on Redisson topic `pnh:clock`, before applying: synchronously call `pnh_ensure_month_partitions` for the new clock month + 3 months (reject with 422 if it fails, research R29 fix H2), apply to local `PnhClock`, read key on startup, fall back to configured mode if Redis unavailable, gauge `pnh_clock_override_active`, publish `ConfigChanged(entity_type=CLOCK_OVERRIDE)`) and `backend/src/main/java/com/pnh/admin/adapter/in/web/ClockController.java` (`GET/PUT/DELETE /console/api/admin/clock` per console-api.openapi.yaml; registered only when `pnh.clock.override-enabled=true`; PUT/DELETE ADMIN only) (make T031 pass)
- [X] T052 [P] Implement Kafka infrastructure `backend/src/main/java/com/pnh/shared/infra/KafkaConfig.java`: producer (acks=all, idempotence on, key serializer String = user_id), consumer factory with manual ack, topic name constants, DLT publishing helper (make T033 pass)
- [X] T053 [P] Configure Spring Modulith events in `application.yml` (`spring.modulith.events.jdbc.schema-initialization.enabled=false`, completion-mode DELETE, `republish-outstanding-events-on-restart=false`) and implement `backend/src/main/java/com/pnh/shared/infra/IncompletePublicationResubmitter.java` (every 60 s via DistributedLockService, resubmit incomplete publications older than 2 min) (make T022 pass) — current time only from the injected `Clock` (cutoffs computed in Java and bound as SQL parameters; no SQL `now()`)
- [X] T054 [P] Implement async executors `backend/src/main/java/com/pnh/shared/config/AsyncConfig.java`: `otpExecutor` (dedicated pool, small queue) and `standardExecutor`, with a TaskDecorator propagating MDC and the OpenTelemetry/Micrometer observation context (ContextSnapshot) to async listeners (make T026 pass)
- [X] T055 [P] Observability config `backend/src/main/java/com/pnh/shared/config/ObservabilityConfig.java` (Micrometer common tags, OTel tracing, metric names `pnh_dispatch_latency_seconds{category}`, `pnh_notifications_total{channel,status}`; spans `pnh.intake`, `pnh.dispatch`, `pnh.provider.send` with attribute `notification_id`; Resilience4j Micrometer binders `TaggedCircuitBreakerMetrics`/`TaggedRetryMetrics`/`TaggedBulkheadMetrics` registered for every provider instance; store W3C `traceparent` in event payload metadata so resubmitted events link to the original trace) (make T026, T027 pass)

### Security

- [X] T056 Implement client authentication in `backend/src/main/java/com/pnh/coreapi/`: `domain/Client.java` (JPA entity extends AuditableConfigEntity; client_id `^[a-z0-9-_]{3,50}$`, rate_limit_per_min > 0, scopes subset of `SEND,TRACK,USER_DELEGATE`), `adapter/out/ClientRepository.java`, `application/ClientAuthenticationProvider.java` (HTTP Basic, bcrypt verify, Caffeine `clientAuth` cache keyed `clientId:sha256(secret)`, reject INACTIVE → `CLIENT_INACTIVE`), `application/ClientPrincipal.java` with scope checks (`SCOPE_DENIED`)
- [X] T057 Implement operator authentication in `backend/src/main/java/com/pnh/admin/`: `domain/Operator.java` (entity, role exactly one of ADMIN/TEMPLATE_EDITOR/SUPPORT_VIEWER), `adapter/out/OperatorRepository.java`, `adapter/in/web/AuthController.java` (`POST /console/api/auth/login`, `POST /logout`, `GET /me` per contracts/console-api.openapi.yaml)
- [X] T058 Implement `backend/src/main/java/com/pnh/shared/config/SecurityConfig.java` with three filter chains: `/api/v1/callbacks/**` (permitAll; verification in handlers), `/api/v1/**` (stateless HTTP Basic via ClientAuthenticationProvider), `/console/api/**` (session cookie `PNH_SESSION`, HttpOnly, SameSite=Lax, CSRF cookie `XSRF-TOKEN`/header `X-XSRF-TOKEN`, method security enabled for role checks) (make SecurityChainsIT and ConsoleAuthContractTest pass)

### Provider SPI & channel config (read side)

- [X] T059 Implement provider SPI in `backend/src/main/java/com/pnh/provider/`: `ChannelProvider` (channelType, providerKey, capabilities, validateRecipient, send, testConnection, optional parseCallback), `ChannelCapabilities` (supportsDirectContent, requiresExternalTemplate, supportsDeliveryCallback, supportsHtml, maxContentLength, recipientType PHONE|EMAIL|DEVICE_TOKEN|ZALO_USER), `DeliveryRequest`, `DeliveryResult` (providerMessageId), `TransientProviderException`, `PermanentProviderException` (with ErrorCode), `ChannelRegistry` (discovers beans; `isRegistered`, `activeProvider(channel)` → `CHANNEL_NOT_CONFIGURED` if none active) (make T025 pass)
- [X] T060 Implement channel config read side in `backend/src/main/java/com/pnh/provider/`: `domain/ChannelConfig.java` (entity extends AuditableConfigEntity, settings JSONB, secrets via EnvelopeEncryptor), `adapter/out/ChannelConfigRepository.java`, `application/ChannelConfigProvider.java` (decrypted active config per channel, Caffeine `channelConfigs`, evicted on `ChannelConfigChanged`) (make T034 pass)
- [X] T061 Implement `backend/src/main/java/com/pnh/provider/application/ProviderResilienceFactory.java` building Retry/CircuitBreaker/TimeLimiter/Bulkhead per provider key with the settings in T016, publishing CB state transitions as `ProviderAvailabilityChanged(channel, providerKey, state, since)` events (make T016 pass)
- [X] T062 [P] Create reusable adapter contract kit `backend/src/test/java/com/pnh/provider/ChannelProviderContractTest.java` (abstract: recipient validation, transient vs permanent error mapping, testConnection, capabilities declared) and a test-only `FakeChannelProvider` (channel `FAKE`) in `backend/src/test/java/com/pnh/provider/fake/FakeChannelProvider.java`

### Tracking core (shared by all stories)

- [X] T063 Implement notification aggregate in `backend/src/main/java/com/pnh/tracking/domain/`: `Notification.java` (maps `pnh_notifications_history`), `NotificationStatusMachine.java` (rules of T013), `NotificationEvent.java` (maps `pnh_notification_events`, event types STATUS_CHANGED, ATTEMPT_FAILED, OPT_OUT_OVERRIDDEN, CALLBACK_RECEIVED, CALLBACK_IGNORED, SWEPT, RESUBMISSION_SKIPPED, CONTENT_SANITIZED) (make T013 pass)
- [X] T064 Implement `backend/src/main/java/com/pnh/tracking/application/StatusTransitionService.java` + `adapter/out/NotificationJdbcRepository.java`: conditional `UPDATE … SET status=:to … WHERE notification_id=:id AND status=:expected`, append timeline event in the same transaction, increment `result_sequence` and publish `NotificationOutcome` when entering SENT/DELIVERED/FAILED; published module API `tracking/TrackingApi.java` (`createReceived`, `transition`, `findById`) (make T021 pass)

### Frontend foundation

- [X] T065 [P] Generate typed console API client: script `gen:api` → `frontend/src/api/schema.d.ts` from `specs/001-notification-hub/contracts/console-api.openapi.yaml`, wrapper `frontend/src/api/client.ts` (openapi-fetch, credentials include, `X-XSRF-TOKEN` from cookie, Problem → typed error)
- [X] T066 Create app shell in `frontend/src/app/`: `App.tsx` (MantineProvider light/dark, Notifications, QueryClientProvider), `router.tsx` (routes: /login, /dashboard, /templates, /templates/:code, /channel-configs, /notifications, /notifications/:id, /config-change-log, /alerts), `AppLayout.tsx` (AppShell navbar items filtered by role: ADMIN all; TEMPLATE_EDITOR dashboard+templates; SUPPORT_VIEWER dashboard+notifications), `RequireRole.tsx`
- [X] T067 [P] Implement `frontend/src/components/ClockOverrideBanner.tsx` shown in `AppLayout` on every page when `GET /console/api/admin/clock` returns mode ≠ SYSTEM ("System time overridden: <now> (<mode>)"; hidden on 404) and an ADMIN-only form on `frontend/src/features/admin/ClockPage.tsx` (route `/admin/clock`, FIXED date-time picker / OFFSET duration, Reset) with test `frontend/src/components/ClockOverrideBanner.test.tsx`
- [X] T068 Create login + session in `frontend/src/features/auth/`: `LoginPage.tsx`, `useMe.ts`, `auth.test.tsx` (redirect to /login on 401, nav hides items not allowed for role)
- [X] T069 [P] Create shared UI utils `frontend/src/utils/format.ts` (dates, masking display) and `frontend/src/components/ProblemAlert.tsx` (renders RFC 9457 `code`/`detail`/field errors; VERSION_CONFLICT shows "modified by someone else" with reload action)

**Checkpoint**: Foundation ready — `./mvnw verify` green (ModularityTest, foundational tests), app boots with `local` profile, console login works.

---

## Phase 3: User Story 1 - Send a notification reliably, by template or direct content (Priority: P1) 🎯 MVP

**Goal**: Authenticated clients submit template-mode or direct-content requests; hub validates, de-duplicates, renders, and delivers once through the channel provider with retry/circuit breaker; outcome SENT or FAILED.

**Independent Test**: quickstart Q1, Q2, Q6 — one client + one active template; send → exactly one message in Mailpit; resubmit → same notification_id, nothing sent; validation errors per contract.

### Tests for User Story 1 ⚠️ (write first, must fail)

- [X] T070 [P] [US1] Contract test `backend/src/test/java/com/pnh/contract/SendContractTest.java`: `POST /api/v1/notifications/send` request/response validated against client-api.openapi.yaml for 202, 200 (`Idempotent-Replay: true`), 401, 403, 422, 429 (`Retry-After`)
- [X] T071 [P] [US1] Integration test `backend/src/test/java/com/pnh/coreapi/integration/SendTemplateEmailIT.java`: template `NEWSLETTER_EMAIL` → GreenMail receives exactly one email with rendered subject/body; status reaches SENT; HTML variable values are escaped; template edited AND deactivated immediately after the 202 (before dispatch) → delivered email still has the content of the version active at acceptance, and `template_version` equals that version (spec edge case, FR-007); EMAIL template with `<a href="${reset_link}">` delivers an email whose link equals the supplied https URL
- [X] T072 [P] [US1] Integration test `backend/src/test/java/com/pnh/coreapi/integration/IdempotencyIT.java`: same client+request id twice → 200 replay, one send; 20 concurrent identical requests → exactly one notification; two different clients same request id → two notifications; Redis stopped → still exactly one (Postgres authoritative)
- [X] T073 [P] [US1] Integration test `backend/src/test/java/com/pnh/coreapi/integration/DirectContentIT.java`: SMS direct `content.body` sent verbatim (WireMock journal); both `template_code`+`content` → 422 `CONTENT_MODE_CONFLICT`; neither → 422; EMAIL direct without `subject` → `MISSING_CONTENT_FIELD`; PUSH without `title` → `MISSING_CONTENT_FIELD`; client with `allow_direct_content=false` → `DIRECT_CONTENT_NOT_ALLOWED`; direct EMAIL body `<p onclick="x()">Hi<script>alert(1)</script><a href="javascript:evil()">l</a><iframe></iframe></p>` is delivered without script/iframe/on*/javascript: and timeline has `CONTENT_SANITIZED`; safe formatting (b, a[href=https], table, img[src=https]) preserved
- [X] T074 [P] [US1] Integration test `backend/src/test/java/com/pnh/coreapi/integration/IntakeValidationIT.java`: unknown template → `TEMPLATE_NOT_FOUND`; inactive → `TEMPLATE_INACTIVE`; channel mismatch → `TEMPLATE_CHANNEL_MISMATCH`; missing vars → `MISSING_VARIABLES` with names; malformed phone/email/empty token → `INVALID_RECIPIENT`; SMS > 1,600 chars → `CONTENT_TOO_LONG`; unregistered channel → `UNSUPPORTED_CHANNEL`; wrong secret → 401; inactive client → `CLIENT_INACTIVE`
- [X] T075 [P] [US1] Integration test `backend/src/test/java/com/pnh/provider/integration/DeliveryResilienceIT.java`: WireMock SMS 503,503,200 → SENT with `retry_count=2` and 2 `ATTEMPT_FAILED` events; 400 INVALID_NUMBER → FAILED immediately, no retry; sustained 503 → circuit opens, attempts fail fast `PROVIDER_UNAVAILABLE`, email deliveries unaffected
- [X] T076 [P] [US1] Integration test `backend/src/test/java/com/pnh/shared/integration/EndToEndTracingIT.java` (G2): one send request produces spans `pnh.intake` → `pnh.dispatch` → `pnh.provider.send` in the same trace (in-memory span exporter), each with `notification_id`; log lines of all three stages carry the same `notification_id`/`client_request_id` MDC; no OTP or recipient in span attributes
- [X] T077 [P] [US1] Integration test `backend/src/test/java/com/pnh/provider/integration/NoResendAfterCrashIT.java`: notification already in SENDING when its `NotificationReadyForDelivery` publication is resubmitted → provider NOT called, timeline `RESUBMISSION_SKIPPED` (FR-039)
- [X] T078 [P] [US1] Integration test `backend/src/test/java/com/pnh/coreapi/integration/RateLimitIT.java`: client with `rate_limit_per_min`=5 → 6th request in a minute gets 429 `RATE_LIMITED` with `Retry-After`; with Redis stopped, local Bucket4j fallback still limits
- [X] T079 [P] [US1] Unit tests `backend/src/test/java/com/pnh/template/unit/PlaceholderParserTest.java` and `TemplateRendererTest.java`: only `\$\{[a-zA-Z_][a-zA-Z0-9_]{0,63}\}` accepted (expressions like `${T(java.lang.Runtime)}` rejected), required variables extracted, EMAIL HTML escaping via `[[…]]`, TEXT mode via `[(…)]`, extra variables ignored; attribute placeholders: `<a href="${reset_link}">Đặt lại</a>` with `reset_link=https://x/y?t=1` renders a working link, `reset_link=javascript:alert(1)` → `VALIDATION_ERROR`, `<img src="${logo}">` with https URL renders, placeholder in `style`/`onclick` attribute rejected with `INVALID_PLACEHOLDER`; template sanitization round-trip keeps `href="${reset_link}"` and `src="${logo}"` byte-for-byte
- [X] T080 [P] [US1] Unit tests `backend/src/test/java/com/pnh/coreapi/unit/RecipientValidatorTest.java`: E.164 normalization with default region VN, email ≤ 254 chars, FCM token non-empty ≤ 4096 chars

### Implementation for User Story 1

- [X] T081 [P] [US1] Create template read model in `backend/src/main/java/com/pnh/template/domain/Template.java` (entity extends AuditableConfigEntity; template_code `^[A-Z0-9_]{3,100}$`; subject_template required iff EMAIL; title_template required iff PUSH; required_variables TEXT[]) and `adapter/out/TemplateRepository.java`
- [X] T082 [P] [US1] Implement `backend/src/main/java/com/pnh/template/domain/PlaceholderParser.java` (regex `\$\{[a-zA-Z_][a-zA-Z0-9_]{0,63}\}`, rejects any other `${…}`) (make T079 parser tests pass)
- [X] T083 [US1] Implement `backend/src/main/java/com/pnh/template/application/TemplateRenderer.java`: string-only Thymeleaf engine, rewrite placeholders to `[[${vars.x}]]` (EMAIL HTML, TemplateMode.HTML) / `[(${vars.x})]` (TEXT for SMS/PUSH/subject/title), placeholders inside `href`/`src`/`alt`/`title` attributes rewritten to `th:<attr>="|…${vars.x}…|"` (inline `[[…]]` does not work in attributes); rendered `href` must be absolute http/https/mailto and `src` https or `data:image/*`, else `VALIDATION_ERROR` naming the variable (research R8 fix H1); compiled cache key `template_code:version` (Caffeine `templates`) (make T079 pass)
- [X] T084 [US1] Publish template module API `backend/src/main/java/com/pnh/template/TemplateApi.java` (`findActive(code)` cached `templateByCode`, `validateVariables`, `render(code, version, vars)` → RenderedContent{subject,title,body})
- [X] T085 [P] [US1] Implement `backend/src/main/java/com/pnh/coreapi/domain/RecipientValidator.java` (libphonenumber, default region VN; email regex + ≤ 254; token ≤ 4096) (make T080 pass)
- [X] T086 [P] [US1] Create request DTOs in `backend/src/main/java/com/pnh/coreapi/adapter/in/web/dto/`: `SendRequest` (client_request_id 1..100, user_id 1..100, channel_type, recipient_target ≤ 4096, template_code ≤ 100, variables values ≤ 2000, category, otp_value ≤ 20, expires_at, content{subject ≤ 255, title ≤ 255, body, data}), `SendAccepted` — JSON snake_case per contract
- [X] T087 [US1] Implement `backend/src/main/java/com/pnh/coreapi/application/IntakeValidator.java`: exactly-one-of template/content (`CONTENT_MODE_CONFLICT`), per-channel required fields via ChannelCapabilities (EMAIL subject+body, SMS body, PUSH title+body), length limits (SMS 1,600; PUSH payload ≤ 4 KB; EMAIL subject ≤ 255), `allow_direct_content`, direct EMAIL HTML sanitized with the OWASP Java HTML Sanitizer policy of research R28 (`backend/src/main/java/com/pnh/shared/web/HtmlSanitizerPolicy.java`; record `CONTENT_SANITIZED` timeline event when output differs), template existence/status/channel match/missing variables, `UNSUPPORTED_CHANNEL` via ChannelRegistry
- [X] T088 [US1] Implement `backend/src/main/java/com/pnh/coreapi/application/IdempotencyService.java`: Redisson `RBucket.setIfAbsent("idem:{clientId}:{clientRequestId}", notificationId, 5 min)` fast path; authoritative `INSERT INTO pnh_request_keys … ON CONFLICT DO NOTHING RETURNING` in `adapter/out/RequestKeyRepository.java`; returns existing notification on conflict; tolerates Redis outage
- [X] T089 [US1] Implement `backend/src/main/java/com/pnh/coreapi/application/ClientRateLimiter.java`: Redisson `RRateLimiter` `rl:{clientId}` OVERALL rate = rate_limit_per_min per minute; fallback Bucket4j local bucket capacity `rate_limit_per_min / pnh.ratelimit.replica-count` when Redis unavailable; `RATE_LIMITED` with retry-after seconds
- [X] T090 [US1] Implement `backend/src/main/java/com/pnh/coreapi/application/SubmitNotificationUseCase.java`: rate limit → validate → idempotency key + render NOW via `TemplateApi.render(code, activeVersion, vars)` (template mode) or sanitized direct content (IntakeValidator) → if category OTP: `OtpContentProtector.protect(...)` (added in US3; masked content + `payload_encrypted`) → `TrackingApi.createReceived` (status RECEIVED, content_mode, category from template or request, rendered subject/title/body, `template_version`) in one transaction (rendering at acceptance — research R8/fix I1) → publish `NotificationAccepted(notificationId, userId, channel, category)` via ApplicationEventPublisher (event registry)
- [X] T091 [US1] Implement `backend/src/main/java/com/pnh/coreapi/adapter/in/web/SendController.java` (`POST /api/v1/notifications/send`, scope SEND, 202 new / 200 + `Idempotent-Replay: true` duplicate) (make T070–T074, T078 pass)
- [X] T092 [US1] Implement dispatcher in `backend/src/main/java/com/pnh/dispatcher/application/DispatchListener.java`: `@Async("standardExecutor") @TransactionalEventListener` + `@Transactional(REQUIRES_NEW)` on `NotificationAccepted` → (NO rendering: content already stored at acceptance) → transition PROCESSED → publish `NotificationReadyForDelivery(notificationId, channel, category)`; ensures `ChannelRegistry.activeProvider` else FAILED `CHANNEL_NOT_CONFIGURED`
- [X] T093 [US1] Implement `backend/src/main/java/com/pnh/provider/application/DeliveryOrchestrator.java`: listener on `NotificationReadyForDelivery`; if status already SENDING → record `RESUBMISSION_SKIPPED` and complete (no call, FR-039); else commit SENDING + `sending_started_at` in its own transaction BEFORE calling provider; execute via ProviderResilienceFactory decorators; per attempt record `ATTEMPT_FAILED`; success → conditional `SENDING→SENT` storing provider_message_id; if a callback already set DELIVERED/FAILED, store only provider_key/provider_message_id and publish no SENT result (fix I4); permanent/exhausted → FAILED with error code/message (make T075, T077 pass)
- [X] T094 [P] [US1] Implement SMTP adapter `backend/src/main/java/com/pnh/provider/email/SmtpEmailProvider.java` (Spring `JavaMailSenderImpl` built from ChannelConfig settings host/port/tls/from + secret password; HTML body; capabilities supportsHtml=true, supportsDeliveryCallback=false; 4xx SMTP/IO → transient, 5xx permanent auth → `PROVIDER_AUTH_FAILED`) + `backend/src/test/java/com/pnh/provider/email/SmtpEmailProviderContractTest.java` extending the kit
- [X] T095 [P] [US1] Implement generic SMS adapter `backend/src/main/java/com/pnh/provider/sms/GenericHttpSmsProvider.java` (POST to configured endpoint with api key/secret, sender/brandname, always sends `request_id` = notification_id; 5xx/429/timeout transient; 4xx `INVALID_NUMBER` → `INVALID_RECIPIENT`; returns message_id) + `backend/src/test/java/com/pnh/provider/sms/GenericHttpSmsProviderContractTest.java`
- [X] T096 [P] [US1] Implement FCM adapter `backend/src/main/java/com/pnh/provider/push/FcmPushProvider.java` (FCM HTTP v1 with service-account JSON secret, base URL overridable for WireMock; title/body/data; `UNREGISTERED`/`INVALID_ARGUMENT` → permanent `INVALID_RECIPIENT`; capabilities supportsDeliveryCallback=false, payload ≤ 4 KB) + `backend/src/test/java/com/pnh/provider/push/FcmPushProviderContractTest.java`
- [X] T097 [US1] Add per-notification MDC + metrics in DeliveryOrchestrator and SubmitNotificationUseCase (`pnh_notifications_total{channel,status}`, attempt latency timer) in `backend/src/main/java/com/pnh/provider/application/DeliveryMetrics.java` and create spans `pnh.intake` (SubmitNotificationUseCase), `pnh.dispatch` (DispatchListener), `pnh.provider.send` (DeliveryOrchestrator) (make T076 pass)

**Checkpoint**: US1 independently functional — quickstart Q1, Q2, Q6 pass.

---

## Phase 4: User Story 2 - Track delivery and receive results (Priority: P1)

**Goal**: Clients query status by client_request_id and receive one result message per outcome on `pnh.notification.result` (key `user_id`); verified provider callbacks move SENT → DELIVERED/FAILED.

**Independent Test**: quickstart Q3, Q7 — track own request; other client's id → 404; result messages with `event_id`, `sequence`; signed callback → DELIVERED; bad signature → 401, no change.

### Tests for User Story 2 ⚠️

- [X] T098 [P] [US2] Contract test `backend/src/test/java/com/pnh/contract/TrackContractTest.java` (`GET /api/v1/notifications/track/{client_request_id}` 200/404 per client-api.openapi.yaml)
- [X] T099 [P] [US2] Contract test `backend/src/test/java/com/pnh/contract/ResultMessageContractTest.java`: consumed messages validate against `contracts/kafka/result.schema.json`; key = `user_id`; headers `client_id`, `channel_type`, `status`, `event_id`
- [X] T100 [P] [US2] Integration test `backend/src/test/java/com/pnh/tracking/integration/ResultStreamIT.java`: SENT then DELIVERED produce 2 messages in order with sequence 1,2; FAILED produces one; duplicate callback produces no extra message; Kafka broker paused then resumed → message still published (registry)
- [X] T101 [P] [US2] Integration test `backend/src/test/java/com/pnh/tracking/integration/SmsCallbackIT.java`: generic HMAC callback (`X-PNH-Signature: sha256=<hex HMAC-SHA256(secret, timestamp + "." + raw_body)>`, `X-PNH-Timestamp` skew ≤ 5 min) → DELIVERED; bad signature → 401 and `CALLBACK_IGNORED`; unknown message_id → 204 no change; DELIVERED callback on FAILED `DELIVERY_STATE_UNKNOWN` → DELIVERED + new result (FR-041); on other FAILED → ignored; callback carrying `request_id`=notification_id while status is still SENDING (provider call stubbed to block before SENT commit) → DELIVERED immediately; the subsequent SENT commit only stores `provider_message_id`, status stays DELIVERED, and no SENT result is published (results: one DELIVERED only)
- [X] T102 [P] [US2] Integration test `backend/src/test/java/com/pnh/provider/sms/AndroidSmsGatewayIT.java`: send via WireMock-emulated gateway API returns message id; webhook `sms:delivered` → DELIVERED, `sms:failed` → FAILED `PROVIDER_REJECTED`, `sms:sent` → timeline only; invalid signature → 401
- [X] T103 [P] [US2] Contract test `backend/src/test/java/com/pnh/contract/ProviderCallbackContractTest.java`: requests/responses of `POST /api/v1/callbacks/sms/{provider_key}` and `POST /api/v1/callbacks/sms/android-sms-gateway` (204, 401) validated against contracts/provider-callbacks.openapi.yaml; SendGrid path excluded (deferred)

### Implementation for User Story 2

- [X] T104 [US2] Implement `backend/src/main/java/com/pnh/tracking/application/TrackQueryService.java` + `adapter/in/web/TrackController.java` (scope TRACK; lookup via `pnh_request_keys` scoped to caller's client_id; 404 `NOT_FOUND` otherwise; response fields per `TrackResponse`; OTP content never returned) (make T098 pass)
- [X] T105 [US2] Implement result externalization `backend/src/main/java/com/pnh/tracking/application/NotificationResultEvent.java` annotated `@Externalized("pnh.notification.result::#{userId()}")` with fields event_id (UUID), notification_id, client_id, client_request_id, user_id, channel_type, status, error_code, error_message (masked), sequence, timestamp; header mapping in `backend/src/main/java/com/pnh/tracking/adapter/out/ResultHeadersConfig.java`; emitted from StatusTransitionService on SENT/DELIVERED/FAILED (make T099, T100 pass)
- [X] T106 [US2] Implement callback processing `backend/src/main/java/com/pnh/tracking/application/DeliveryReportService.java`: match first by hub notification id echoed by the provider (`request_id` / gateway message id), then by `(provider_key, provider_message_id)`; verified DELIVERED/FAILED accepted from SENDING or SENT (fix I4), Redisson lock `lock:notif:{id}` (10 s) + conditional update, forward-only except FR-041, `CALLBACK_RECEIVED`/`CALLBACK_IGNORED` events, idempotent
- [X] T107 [US2] Implement generic SMS callback `backend/src/main/java/com/pnh/tracking/adapter/in/web/SmsCallbackController.java` (`POST /api/v1/callbacks/sms/{provider_key}`) + `backend/src/main/java/com/pnh/provider/sms/HmacCallbackVerifier.java` (secret `callback_hmac_secret` from channel config) (make T101 pass)
- [X] T108 [US2] Implement Android gateway adapter `backend/src/main/java/com/pnh/provider/sms/AndroidSmsGatewayProvider.java` (provider_key `ANDROID_SMS_GATEWAY`; REST send with basic auth from config; pass the hub notification_id as the gateway message id when its send API accepts a client-supplied id (check the docs; record in research R14) so webhooks match before SENT is committed, otherwise use the returned gateway message id; capabilities supportsDeliveryCallback=true) and webhook `POST /api/v1/callbacks/sms/android-sms-gateway` in `backend/src/main/java/com/pnh/tracking/adapter/in/web/AndroidSmsGatewayWebhookController.java` with signature verification per the gateway's webhook docs (verify header names against current docs before coding; record them in research.md R14) (make T102 pass)
- [X] T109 [P] [US2] Create helper `deploy/local/scripts/sms-callback.sh <notification_id> <DELIVERED|FAILED>` computing the HMAC signature and posting to the generic callback endpoint; also `deploy/local/scripts/kafka-sign.sh <client_id> <signing_key> <message.json>` printing the `pnh-signature` header value (contracts/kafka/README.md)

**Checkpoint**: US1 + US2 work independently — quickstart Q3, Q7 pass.

---

## Phase 5: User Story 3 - Deliver one-time passwords (OTP) via SMS and Email (Priority: P1)

**Goal**: OTP notifications (template or direct) are prioritized, never delivered after `expires_at`, and the OTP value is masked everywhere except the provider payload.

**Independent Test**: quickstart Q4 — OTP via SMS/Email arrives with code; console/track/logs show `******`; expired-before-attempt → FAILED `OTP_EXPIRED`; OTP latency under STANDARD backlog.

### Tests for User Story 3 ⚠️

- [X] T110 [P] [US3] Integration test `backend/src/test/java/com/pnh/dispatcher/integration/OtpExpiryIT.java`: OTP without `expires_at` → 422 `OTP_EXPIRY_REQUIRED`; direct OTP without `otp_value` → `OTP_VALUE_REQUIRED`; provider delayed past expiry → FAILED `OTP_EXPIRED`, no attempt after expiry; template-mode OTP with `expiry_variable=expired_minutes` and `variables.expired_minutes="5"` but no `expires_at` → accepted with expires_at = acceptance + 5 min (original technical-input payload works unchanged); explicit `expires_at` overrides; `expired_minutes` = "abc"/"0"/"1441" → 422 `VALIDATION_ERROR`; OTP template without `expiry_variable` and no `expires_at` → `OTP_EXPIRY_REQUIRED`; use an injected `Clock`, no sleeps
- [X] T111 [P] [US3] Integration test `backend/src/test/java/com/pnh/dispatcher/integration/OtpMaskingIT.java`: template OTP (`otp_variable` `otp_code`) and direct OTP (`otp_value`) → stored `compiled_content` contains `******` not the code; `payload_encrypted` set while non-terminal and NULL after SENT/FAILED; `/track` and result message contain no code; captured log output contains no code; immediately after the 202 and BEFORE the dispatcher runs (dispatcher listener paused in test), the persisted `compiled_content`, `subject`, `title` already contain `******` and never the code (plaintext never written)
- [X] T112 [P] [US3] Integration test `backend/src/test/java/com/pnh/dispatcher/integration/OtpPriorityIT.java` (deterministic, fix C4): provider stub blocks STANDARD sends on a latch; enqueue 2,000 STANDARD then 20 OTP notifications; assert all 20 OTPs reach SENDING (recorded order of `SENDING` events) while STANDARD backlog is still ≥ 1,900 and none of them waits for a STANDARD slot (separate executor + bulkhead); no wall-clock latency assertions — the p95 < 5 s target (SC-012) is measured in the performance suite
- [X] T113 [P] [US3] Integration test `backend/src/test/java/com/pnh/dispatcher/integration/OtpResendIT.java`: same OTP request id twice → one send; new request ids → each sent (resend limits are client's responsibility)

### Implementation for User Story 3

- [X] T114 [US3] Extend IntakeValidator in `backend/src/main/java/com/pnh/coreapi/application/IntakeValidator.java`: category from template (template mode) or request (direct, default STANDARD); OTP expiry per FR-029/R27 (acceptance time = `clock.instant()`): request `expires_at` (must be future) else, for template mode, derive from template `expiry_variable` (integer 1..1440 minutes, else `VALIDATION_ERROR`) as acceptance time (injected `Clock`) + minutes, else `OTP_EXPIRY_REQUIRED`; direct OTP requires `otp_value` ≤ 20 (`OTP_VALUE_REQUIRED`); OTP templates must contain `${otp_variable}`
- [X] T115 [US3] Implement OTP content protection `backend/src/main/java/com/pnh/shared/security/OtpContentProtector.java` (shared OPEN module so coreapi can call it without a reverse module dependency) and invoke it in `backend/src/main/java/com/pnh/coreapi/application/SubmitNotificationUseCase.java` for category OTP right after rendering and BEFORE `TrackingApi.createReceived`, inside the acceptance transaction (research R15 placement): produce masked subject/title/body (OTP value → `******`) stored as `compiled_content`/`subject`/`title`; encrypt the real content into `payload_encrypted` with EnvelopeEncryptor; register the value in `SensitiveValues` before any logging; DeliveryOrchestrator decrypts for the provider call; StatusTransitionService nulls `payload_encrypted` on terminal status (make OtpMaskingIT pass)
- [X] T116 [US3] Split event types for priority: `OtpNotificationAccepted` / `OtpNotificationReadyForDelivery` in `backend/src/main/java/com/pnh/coreapi/…` and `…/dispatcher/…`; OTP listeners bound to `otpExecutor`, STANDARD to `standardExecutor`; per-provider Bulkhead reserving capacity for OTP in ProviderResilienceFactory (make T112 pass)
- [X] T117 [US3] Add expiry gate in `backend/src/main/java/com/pnh/provider/application/DeliveryOrchestrator.java`: before every attempt (initial and retry) check `expires_at`; elapsed → FAILED `OTP_EXPIRED` without calling provider (make T110 pass)
- [X] T118 [US3] Add metric `pnh_dispatch_latency_seconds{category}` (accepted → SENDING) in `backend/src/main/java/com/pnh/provider/application/DeliveryMetrics.java`

**Checkpoint**: All P1 stories complete — MVP+ (send, track, OTP).

---

## Phase 6: User Story 4 - Asynchronous bulk ingestion (Priority: P2)

**Goal**: Clients publish requests to `pnh.notification.ingest` / `pnh.notification.ingest.otp` (key `user_id`); same validation/idempotency; invalid → FAILED result or DLT.

**Independent Test**: publish batch with duplicates + one invalid → each valid unique delivered once, duplicates skipped, invalid yields FAILED result with validation error_code.

### Tests for User Story 4 ⚠️

- [X] T119 [P] [US4] Contract test `backend/src/test/java/com/pnh/contract/IngestMessageContractTest.java`: sample messages validated against `contracts/kafka/ingest.schema.json` (oneOf template/content; OTP direct requires otp_value + expires_at)
- [X] T120 [P] [US4] Integration test `backend/src/test/java/com/pnh/coreapi/integration/KafkaIngestIT.java`: valid message → delivered + result; duplicate → single notification; invalid with readable client/request id → FAILED result with validation code and notification recorded; unparseable or unknown client → DLT with `pnh-error-code` header; missing or invalid `pnh-signature` (wrong key, tampered value, other client's key) → DLT `UNAUTHORIZED` (messages signed via test helper `backend/src/test/java/com/pnh/support/KafkaSigner.java`, no SASL needed); un-keyed message accepted; OTP lane topic processed by OTP path; message missing `user_id` and `recipient_target` but validly signed → FAILED notification stored with NULLs, `/track` by client_request_id returns FAILED `VALIDATION_ERROR`, result message has `user_id: null` and validates against result.schema.json; resending the same request id → no second notification
- [X] T121 [P] [US4] Integration test `backend/src/test/java/com/pnh/coreapi/integration/IngestAckIT.java`: consumer crash between consume and commit → message redelivered and absorbed by idempotency (no duplicate send)

### Implementation for User Story 4

- [X] T122 [US4] Implement `backend/src/main/java/com/pnh/coreapi/adapter/in/kafka/IngestListener.java`: `@KafkaListener` on `pnh.notification.ingest` and (separate container, higher concurrency) `pnh.notification.ingest.otp`; parse JSON to SendRequest; resolve ACTIVE client from `client_id` header and verify `pnh-signature` = `v1=` HMAC-SHA256(decrypted `kafka_signing_key`, client_id.client_request_id.hex(sha256(value))) in constant time (`backend/src/main/java/com/pnh/coreapi/adapter/in/kafka/IngestSignatureVerifier.java`; skipped only when `pnh.kafka.signature-required=false`); call SubmitNotificationUseCase; manual ack after commit
- [X] T123 [US4] Implement `backend/src/main/java/com/pnh/coreapi/application/IngestFailureHandler.java`: validation failure with valid signature and readable `client_request_id` → insert request key + FAILED notification storing only the fields that passed validation (others NULL, per data-model ¹), error code/message, publish FAILED result keyed by `user_id` or `client_id:client_request_id` when user_id is unreadable; otherwise publish raw record to `pnh.notification.ingest.DLT` with headers `pnh-error-code`, `pnh-error-message`; publish `IngestRejected(clientId)` event for alerting
- [X] T124 [US4] Kafka consumer error handling config in `backend/src/main/java/com/pnh/shared/infra/KafkaConfig.java` (DefaultErrorHandler with no retry for deserialization errors → DLT)

**Checkpoint**: US4 independently testable.

---

## Phase 7: User Story 5 - Respect end-user channel preferences (Priority: P2)

**Goal**: Users (via clients with `USER_DELEGATE`) enable/disable channels; STANDARD non-critical notifications to disabled channels FAIL `USER_OPTED_OUT`; OTP and `is_critical` templates bypass with `OPT_OUT_OVERRIDDEN`.

**Independent Test**: quickstart Q5.

### Tests for User Story 5 ⚠️

- [X] T125 [P] [US5] Contract test `backend/src/test/java/com/pnh/contract/PreferencesContractTest.java` (`GET/PUT /api/v1/users/{user_id}/preferences`)
- [X] T126 [P] [US5] Integration test `backend/src/test/java/com/pnh/dispatcher/integration/OptOutIT.java`: no row → enabled; EMAIL disabled → STANDARD FAILED `USER_OPTED_OUT`, nothing sent; OTP template → sent + `OPT_OUT_OVERRIDDEN`; `is_critical` template → sent; direct STANDARD → not sent; re-enable → sent; preference update writes config audit entry with actor `clientId:userId` and version+1

### Implementation for User Story 5

- [X] T127 [P] [US5] Create `backend/src/main/java/com/pnh/dispatcher/domain/UserChannelPreference.java` (entity extends AuditableConfigEntity; UNIQUE(user_id, channel_type); absent ⇒ enabled) and `adapter/out/UserPreferenceRepository.java`
- [X] T128 [US5] Implement `backend/src/main/java/com/pnh/dispatcher/application/PreferenceService.java` (Caffeine `userPreferences` 60 s + invalidation bus; publishes ConfigChanged entity_type `USER_PREFERENCE`) and `adapter/in/web/PreferenceController.java` (scope USER_DELEGATE; channel keys validated against ChannelRegistry)
- [X] T129 [US5] Add opt-out rule to `backend/src/main/java/com/pnh/dispatcher/application/DispatchListener.java` (and OTP listener): disabled + STANDARD + not critical → FAILED `USER_OPTED_OUT`; disabled + (OTP or `is_critical`) → continue, set `opt_out_overridden=true`, timeline `OPT_OUT_OVERRIDDEN` (make T126 pass)

---

## Phase 8: User Story 7 - Template management (Priority: P2)

**Goal**: Operators (ADMIN, TEMPLATE_EDITOR) create/edit/preview/deactivate templates; rich-text Email editor highlights `${name}`; SMS/Push plain text with character count; optimistic locking.

**Independent Test**: quickstart Q11 steps 1–3 — create Email template with two placeholders, preview, activate, send with it.

### Tests for User Story 7 ⚠️

- [X] T130 [P] [US7] Contract test `backend/src/test/java/com/pnh/contract/TemplateConsoleContractTest.java` (all `/console/api/templates*` operations per console-api.openapi.yaml incl. 409 `TEMPLATE_CODE_EXISTS`, 409 `VERSION_CONFLICT` with `current`, 422 `INVALID_PLACEHOLDER`)
- [X] T131 [P] [US7] Integration test `backend/src/test/java/com/pnh/template/integration/TemplateManagementIT.java`: create/update/deactivate/activate; SUPPORT_VIEWER → 403; deactivate keeps row; edit after acceptance doesn't change already-rendered content (template_version recorded); update evicts caches on a second app context via invalidation bus; audit log entries CREATE/UPDATE/DEACTIVATE
- [X] T132 [P] [US7] Frontend tests `frontend/src/features/templates/templates.test.tsx`: list filters; editor highlights `${name}` and lists required variables; SMS editor shows character count and segments; preview renders values; 409 shows conflict dialog

### Implementation for User Story 7

- [X] T133 [US7] Implement `backend/src/main/java/com/pnh/template/application/TemplateCommandService.java`: create (unique code `^[A-Z0-9_]{3,100}$`, placeholders validated, `required_variables` extracted, OTP requires `otp_variable` present in body, optional `expiry_variable` must be a declared placeholder (OTP only), EMAIL body HTML sanitized with the R28 placeholder-safe policy on save (placeholders swapped for opaque tokens, sanitized, restored), placeholders only allowed in text and in `href`/`src`/`alt`/`title` attributes (`INVALID_PLACEHOLDER` otherwise), EMAIL needs subject ≤ 255, PUSH needs title ≤ 255, `external_template_ref` required when channel capability `requiresExternalTemplate`), update with version check, deactivate/activate, publishes ConfigChanged(TEMPLATE) + cache invalidation
- [X] T134 [US7] Implement `backend/src/main/java/com/pnh/template/application/TemplatePreviewService.java` (render unsaved content with sample variables; returns subject/title/body, character_count, sms_segments (GSM-7 160/153, UCS-2 70/67), missing_variables)
- [X] T135 [US7] Implement `backend/src/main/java/com/pnh/template/adapter/in/web/TemplateConsoleController.java` (`/console/api/templates`, `/{code}`, `/{code}/activate`, `/preview`; `@PreAuthorize` ADMIN or TEMPLATE_EDITOR) (make T130, T131 pass)
- [X] T136 [P] [US7] Implement Tiptap placeholder extension `frontend/src/features/templates/PlaceholderHighlight.ts` (decoration for `\$\{[a-zA-Z_][a-zA-Z0-9_]{0,63}\}`, invalid `${…}` underlined red) and `InsertVariableMenu.tsx`
- [X] T137 [US7] Implement `frontend/src/features/templates/TemplateListPage.tsx` (mantine-datatable: code, name, channel, category, critical, status, updated_by/at; filters q/channel/status; create button)
- [X] T138 [US7] Implement `frontend/src/features/templates/TemplateEditorPage.tsx` (Mantine form; EMAIL → `@mantine/tiptap` RichTextEditor + subject; SMS/PUSH → Textarea with live character count/segments, PUSH title; category/is_critical/otp_variable/expiry_variable fields (OTP only); external_template_ref JSON field shown only for channels that require it; sends `version`; handles 409 via ProblemAlert) and `TemplatePreviewModal.tsx` (make T132 pass)

---

## Phase 9: User Story 8 - Delivery Audit investigation (Priority: P2)

**Goal**: ADMIN/SUPPORT_VIEWER search notifications by client_request_id, user_id, recipient_target, channel, status, client, date range; open detail with content (OTP masked) and full timeline.

**Independent Test**: quickstart Q11 step 5 — find a failed-after-retries notification by recipient and see retries + provider error.

### Tests for User Story 8 ⚠️

- [X] T139 [P] [US8] Contract test `backend/src/test/java/com/pnh/contract/NotificationSearchContractTest.java` (`GET /console/api/notifications`, `/{id}`)
- [X] T140 [P] [US8] Integration test `backend/src/test/java/com/pnh/tracking/integration/NotificationSearchIT.java`: each filter alone and combined; newest first; pagination; date range > 31 days without client_request_id → 422; list shows `recipient_target_masked`; detail shows full recipient, masked OTP, timeline incl. ATTEMPT_FAILED with provider error; TEMPLATE_EDITOR → 403
- [X] T141 [P] [US8] Frontend tests `frontend/src/features/notifications/notifications.test.tsx` (filters → query params, empty state message, timeline renders retries and errors)

### Implementation for User Story 8

- [X] T142 [US8] Implement `backend/src/main/java/com/pnh/tracking/application/NotificationSearchService.java` (partition-pruned queries using created_at range; filters; masking via Masking) and `adapter/in/web/NotificationConsoleController.java` (`@PreAuthorize` ADMIN or SUPPORT_VIEWER) (make T139, T140 pass)
- [X] T143 [US8] Implement `frontend/src/features/notifications/NotificationSearchPage.tsx` (filter form, mantine-datatable newest first with pagination, masked recipient column, status badges, empty state "No notifications match these filters")
- [X] T144 [US8] Implement `frontend/src/features/notifications/NotificationDetailPage.tsx` (fields per `NotificationDetail`, Mantine `Timeline` of events with attempt numbers, provider error code/message, provider_response JSON collapsible) (make T141 pass)

---

## Phase 10: User Story 11 - Incident alerts to administrators (Priority: P2)

**Goal**: Stuck notifications are failed without resend; incident rules email the admin-managed recipient list with cooldown and "resolved" mails; alert history visible.

**Independent Test**: quickstart Q13 — stuck SENDING after kill → FAILED `DELIVERY_STATE_UNKNOWN`, no resend; one alert email to active recipients; none during cooldown.

### Tests for User Story 11 ⚠️

- [X] T145 [P] [US11] Integration test `backend/src/test/java/com/pnh/tracking/integration/StuckSweeperIT.java`: SENDING older than stuck-timeout → FAILED `DELIVERY_STATE_UNKNOWN` + `SWEPT` + FAILED result, provider never called; RECEIVED/PROCESSED older than processing-timeout → FAILED `PROCESSING_TIMEOUT`; non-terminal OTP past expires_at → `OTP_EXPIRED`; startup fails if stuck-timeout ≤ max attempt duration; batch of 500 per run; advance time with `PnhClock` OFFSET/FIXED (no sleeps, no manual `updated_at` edits)
- [X] T146 [P] [US11] Integration test `backend/src/test/java/com/pnh/alerting/integration/AlertEvaluationIT.java`: 10 stuck failures in 10 min → one FIRED email (GreenMail) to active recipients only, body without OTP/full recipients; repeat within cooldown → none; condition clears → one RESOLVED; no recipients → event `NO_RECIPIENTS`; email provider down → `SEND_FAILED` and retried next run; Redis down → counts from Postgres fallback; CB open ≥ 2 min → PROVIDER_UNAVAILABLE alert; failure rate ≥ 30% with ≥ 50 samples → CHANNEL_FAILURE_RATE; 20 DLT records → INGEST_REJECTED_SPIKE; windows and the 30-min cooldown are exercised by advancing `PnhClock` (no sleeps); cooldown decision taken from `pnh_alert_events` (durable source), Redis key only as cache
- [X] T147 [P] [US11] Contract test `backend/src/test/java/com/pnh/contract/AlertConsoleContractTest.java` (`/console/api/alerts/recipients*`, `/rules*`, `/events` incl. 409 RECIPIENT_EXISTS, VERSION_CONFLICT; non-ADMIN → 403)
- [X] T148 [P] [US11] Frontend tests `frontend/src/features/alerts/alerts.test.tsx` (add recipient validation, deactivate toggle, rule edit bounds window 1..1440 / cooldown 5..1440, history table)

### Implementation for User Story 11

- [X] T149 [US11] Implement `backend/src/main/java/com/pnh/tracking/application/StuckNotificationSweeper.java` (every 60 s via DistributedLockService; queries per R23 using partial index; conditional transitions; publish `NotificationStuckFailed(channel, notificationId)`; validates `pnh.delivery.stuck-timeout` > 4 × 5 s + 7 s at startup) (make T145 pass) — current time only from the injected `Clock` (cutoffs computed in Java and bound as SQL parameters; no SQL `now()`)
- [X] T150 [P] [US11] Create alerting domain in `backend/src/main/java/com/pnh/alerting/domain/`: `AlertRecipient` (email VARCHAR(254) unique lower-cased, valid format; extends AuditableConfigEntity), `AlertRule` (rule_code PK in {STUCK_DELIVERY_SPIKE, PROVIDER_UNAVAILABLE, CHANNEL_FAILURE_RATE, INGEST_REJECTED_SPIKE}; window_minutes 1..1440; cooldown_minutes 5..1440; threshold ≥ 0), `AlertEvent` (kind FIRED|RESOLVED, send_status SENT|SEND_FAILED|NO_RECIPIENTS) + repositories in `adapter/out/`
- [X] T151 [US11] Implement signal collection `backend/src/main/java/com/pnh/alerting/application/AlertSignalCollector.java`: listeners for NotificationStuckFailed, ProviderAvailabilityChanged, NotificationOutcome (per-channel success/failure), IngestRejected → Redisson scored sets `alert:win:{rule}:{channel}` (TTL window+1 min); Postgres query fallback when Redis unavailable — current time only from the injected `Clock` (cutoffs computed in Java and bound as SQL parameters; no SQL `now()`)
- [X] T152 [US11] Implement `backend/src/main/java/com/pnh/alerting/application/AlertEvaluator.java` (every 60 s, lock-guarded; per rule+channel compute value vs threshold; cooldown via Redis key `alert:cooldown:{rule}:{channel}` with durable check against latest `pnh_alert_events`; FIRED/RESOLVED transitions; writes AlertEvent) — current time only from the injected `Clock` (cutoffs computed in Java and bound as SQL parameters; no SQL `now()`)
- [X] T153 [US11] Implement `backend/src/main/java/com/pnh/alerting/application/AlertMailer.java` using the active EMAIL ChannelProvider directly (not the notification pipeline; bypasses preferences/idempotency/rate limit), optional fallback SMTP from `pnh.alerting.fallback-smtp.*`; subject `[PNH][{rule}] {channel} – {value} in {window} min`; body includes threshold, sample notification ids and console link `/notifications?status=FAILED&channel_type=…`; never OTP/full recipients (make T146 pass)
- [X] T154 [US11] Implement `backend/src/main/java/com/pnh/alerting/adapter/in/web/AlertConsoleController.java` (`/console/api/alerts/recipients` CRUD + `/test`, `/rules` list/update, `/events` search; ADMIN only; ConfigChanged ALERT_RECIPIENT/ALERT_RULE; cache invalidation `alertSettings`) (make T147 pass)
- [X] T155 [US11] Implement `frontend/src/features/alerts/AlertsPage.tsx` with tabs: Recipients (table, add form with email validation, active switch, remove, "Send test alert"), Rules (editable rows with threshold/window/min_sample/cooldown + version), History (mantine-datatable of AlertEvent) (make T148 pass)

---

## Phase 11: User Story 6 - In-app inbox for Push notifications (Priority: P3)

**Goal**: Users list their PUSH STANDARD notifications with unread count and mark one/many as read.

**Independent Test**: quickstart Q10.

### Tests for User Story 6 ⚠️

- [X] T156 [P] [US6] Contract test `backend/src/test/java/com/pnh/contract/InboxContractTest.java` (`GET /api/v1/users/{user_id}/inbox`, `PATCH /api/v1/notifications/{id}/read`, `POST /api/v1/notifications/bulk-read` incl. `maxItems: 500` → 422 `BULK_LIMIT_EXCEEDED`)
- [X] T157 [P] [US6] Integration test `backend/src/test/java/com/pnh/inbox/integration/InboxIT.java`: only PUSH STANDARD items listed newest first with unread_count; mark read sets read_at, repeat preserves first read_at; bulk with mixed ids → updated_count + not_found_ids; other user's id / SMS / Email / OTP id → 404; `X-User-Id` required; scope USER_DELEGATE required

### Implementation for User Story 6

- [X] T158 [P] [US6] Create `backend/src/main/java/com/pnh/inbox/domain/ReadState.java` (notification_id PK, user_id, is_read, read_at first-read preserved, notification_created_at) and `adapter/out/ReadStateRepository.java`
- [X] T159 [US6] Implement `backend/src/main/java/com/pnh/inbox/application/InboxProjector.java` (`@ApplicationModuleListener` on NotificationOutcome SENT where channel PUSH and category STANDARD → insert unread ReadState)
- [X] T160 [US6] Implement `backend/src/main/java/com/pnh/inbox/application/InboxService.java` (list via partial index join for title/body/data, unread_count, markRead idempotent, bulkMarkRead ≤ 500 unique ids) and `adapter/in/web/InboxController.java` (make T156, T157 pass)

---

## Phase 12: User Story 9 - Operations dashboard (Priority: P3)

**Goal**: Volume by hour/day per channel and success/failure rates per channel (success = SENT+DELIVERED, failure = FAILED, in-progress excluded).

**Independent Test**: quickstart Q12 — dashboard numbers equal SQL counts for seeded data.

### Tests for User Story 9 ⚠️

- [X] T161 [P] [US9] Integration test `backend/src/test/java/com/pnh/tracking/integration/DashboardStatsIT.java`: seeded notifications across channels/statuses/hours → rollup recompute exact; `/console/api/dashboard/volume` HOUR and DAY buckets; `/channel-rates` math; recompute is idempotent; contract validation against console-api.openapi.yaml
- [X] T162 [P] [US9] Frontend tests `frontend/src/features/dashboard/dashboard.test.tsx` (granularity switch, range picker, rate cards per channel)

### Implementation for User Story 9

- [X] T163 [US9] Implement `backend/src/main/java/com/pnh/tracking/application/StatsRollupJob.java` (every 60 s lock-guarded; recompute current and previous hour into `pnh_notification_stats_hourly` with `INSERT … ON CONFLICT DO UPDATE`, counting latest status per notification) — current time only from the injected `Clock` (cutoffs computed in Java and bound as SQL parameters; no SQL `now()`)
- [X] T164 [US9] Implement `backend/src/main/java/com/pnh/tracking/adapter/in/web/DashboardController.java` (`/console/api/dashboard/volume`, `/channel-rates`; all roles) (make T161 pass)
- [X] T165 [US9] Implement `frontend/src/features/dashboard/DashboardPage.tsx` (DatePicker range, HOUR/DAY SegmentedControl, `@mantine/charts` BarChart/AreaChart stacked by channel, per-channel cards with success/failure rate and counts) (make T162 pass)

---

## Phase 13: User Story 10 - Channel provider configuration (Priority: P3)

**Goal**: ADMIN manages provider settings/secrets per channel without restart; secrets write-only/masked; test connection; changes audited; config audit log viewable.

**Independent Test**: quickstart Q11 step 4 — update SMTP password (masked), test connection to Mailpit/Mailtrap, audit entry shows `***changed***`.

### Tests for User Story 10 ⚠️

- [X] T166 [P] [US10] Contract test `backend/src/test/java/com/pnh/contract/ChannelConfigContractTest.java` (`/console/api/channel-configs*`, `/console/api/config-change-log`; secrets never present in responses except `{has_value, masked}`)
- [X] T167 [P] [US10] Integration test `backend/src/test/java/com/pnh/provider/integration/ChannelConfigManagementIT.java`: create/update with version; omitted secret keeps old value; secrets stored encrypted (raw column not containing plaintext); update takes effect for next delivery without restart (provider client rebuilt, CB reset) on both app contexts; one active config per channel; invalid settings (bad URL, malformed service-account JSON) → 422 field errors; test connection SMTP/FCM/SMS results recorded in last_test_*; non-ADMIN → 403; audit entries with `***changed***`
- [X] T168 [P] [US10] Frontend tests `frontend/src/features/channel-configs/channelConfigs.test.tsx` and `frontend/src/features/config-change-log/configChangeLog.test.tsx`

### Implementation for User Story 10

- [X] T169 [US10] Implement `backend/src/main/java/com/pnh/provider/application/ChannelConfigCommandService.java` (create/update with per-provider settings validators for SMTP {host, port, tls, from}, FCM {project_id, base_url?}, ANDROID_SMS_GATEWAY {base_url, mode local|cloud}, GENERIC_HTTP_SMS {endpoint, sender}; write-only secrets merged; encrypt; enforce single active; publish ChannelConfigChanged + ConfigChanged(CHANNEL_CONFIG) with secret keys flagged)
- [X] T170 [US10] Implement hot reload in `backend/src/main/java/com/pnh/provider/application/ProviderClientCache.java` (rebuild provider clients and reset circuit breaker on ChannelConfigChanged across replicas via invalidation bus)
- [X] T171 [US10] Implement `backend/src/main/java/com/pnh/provider/application/ConnectionTester.java` delegating to `ChannelProvider.testConnection` (SMTP connect+auth, FCM OAuth token fetch / dry-run, SMS gateway health/ping) and `adapter/in/web/ChannelConfigController.java` (ADMIN only) (make T166, T167 pass)
- [X] T172 [US10] Implement `backend/src/main/java/com/pnh/admin/adapter/in/web/ConfigChangeLogController.java` (`GET /console/api/config-change-log` filters entity_type incl. ALERT_RECIPIENT/ALERT_RULE, entity_id, actor, date range; ADMIN only)
- [X] T173 [US10] Implement `frontend/src/features/channel-configs/ChannelConfigsPage.tsx` (per channel card, provider_key select, dynamic settings form per provider, secret inputs showing `********`/"set" with "replace" action, JSON file upload for FCM service account, active switch, Test connection button with result) (make T168 pass)
- [X] T174 [US10] Implement `frontend/src/features/config-change-log/ConfigChangeLogPage.tsx` (mantine-datatable with filters, changed_fields diff view old → new)

---

## Phase 14: Polish & Cross-Cutting Concerns

- [X] T175 [P] Zalo readiness check (R25): `backend/src/test/java/com/pnh/provider/integration/ChannelExtensibilityIT.java` registers test-only `FakeChannelProvider` (channel `FAKE`) and proves send→SENT→result end to end with no core changes; capability `requiresExternalTemplate=true` rejects direct content with `DIRECT_CONTENT_NOT_ALLOWED`; add `backend/src/main/java/com/pnh/provider/zalo/README.md` describing the ZNS adapter plan (OAuth token refresh, `external_template_ref.zns_template_id`, phone recipient, webhooks)
- [X] T176 [P] Integration test `backend/src/test/java/com/pnh/tracking/integration/RetentionJobIT.java`: partitions older than 12 months (injected `Clock`) are detached/dropped while newer ones stay; `pnh_request_keys` rows older than 12 months deleted in batches; lock-guarded single run; with clock mode OFFSET +2 years and `pnh.retention.skip-when-clock-overridden=true` (default) nothing is deleted and `pnh_retention_skipped_total{reason="clock_override"}` increments; with the flag `false` retention follows the overridden clock
- [X] T177 [P] Retention job `backend/src/main/java/com/pnh/tracking/adapter/out/RetentionJob.java` (monthly, lock-guarded: detach/drop history/events partitions older than 12 months; delete `pnh_request_keys` older than 12 months in batches) (make T176 pass) — current time only from the injected `Clock` (cutoffs computed in Java and bound as SQL parameters; no SQL `now()`); if clock mode ≠ SYSTEM and `pnh.retention.skip-when-clock-overridden=true` (default) → log WARN, increment `pnh_retention_skipped_total{reason="clock_override"}`, delete nothing (research R29 fix H3)
- [X] T178 [P] Crash-safety test `backend/src/test/java/com/pnh/tracking/integration/CrashRecoveryIT.java` (quickstart Q8: stop app context mid-load, restart, all accepted notifications terminal ≤ 3 min, no provider receives a notification twice)
- [X] T179 [P] Redis degradation test `backend/src/test/java/com/pnh/shared/integration/RedisOutageIT.java` (quickstart Q14) and multi-replica cache invalidation test `backend/src/test/java/com/pnh/shared/integration/CacheInvalidationIT.java` (quickstart Q15)
- [X] T180 [P] Load test script `deploy/local/k6/send-load.js` (RATE, DURATION env; 80% EMAIL mix; thresholds p95 < 1 s) and partition-balance check notes in `deploy/local/k6/README.md` (SC-001, SC-005, SC-014); add k6 `thresholds`: `http_req_duration{name:send}: ['p(95)<1000']`, `http_req_failed: ['rate<0.001']`, sustained 500 req/s (SC-001/SC-005)
- [X] T181 [P] Performance suite (fix G1, kept out of the deterministic `check` gate): Maven profile `performance` in `backend/pom.xml` (`build-helper-maven-plugin` adds test source root `src/performanceTest/java`; a dedicated failsafe execution runs only `**/perf/**`, excluded from the default build), run with `./mvnw -Pperformance verify`, test `backend/src/performanceTest/java/com/pnh/perf/SloPerformanceTest.java` against Testcontainers + WireMock fast providers measuring and asserting: SC-004 99% of 5,000 notifications reach SENT/FAILED ≤ 30 s after acceptance (from `created_at` vs terminal timeline event); SC-007 result message consumed ≤ 5 s after the status change (timeline time vs consumer receive time, 100% coverage); SC-010 `/console/api/dashboard/volume` + `/channel-rates` for a seeded 30-day range respond < 3 s; SC-012 OTP accepted→SENDING p95 < 5 s with a 2,000 STANDARD backlog; SC-017 alert email received ≤ 3 min after a seeded threshold crossing (real scheduler); writes a JSON report to `backend/build/reports/performance/`
- [X] T182 [P] Add nightly CI workflow `.github/workflows/performance.yml` running `./mvnw -B -Pperformance verify` and `k6 run deploy/local/k6/send-load.js` against the compose stack; failing SLO thresholds fail the job and attach reports
- [X] T183 [P] Playwright E2E, one spec per primary screen flow (constitution II) in `frontend/e2e/`: `auth.spec.ts` (login per role, nav visibility, 403 page), `dashboard.spec.ts` (range + HOUR/DAY switch renders charts and per-channel rates), `templates.spec.ts` (create EMAIL with `${name}`, preview, 409 conflict dialog, deactivate), `notifications.spec.ts` (search by recipient → detail timeline with retries/error), `channel-configs.spec.ts` (masked secret, replace secret, test connection), `config-change-log.spec.ts` (filter by entity_type, diff shows `***changed***`), `alerts.spec.ts` (add/deactivate recipient, edit rule, send test alert, history row) — run against backend `local` profile + compose stack
- [X] T184 Security hardening: response headers (CSP for SPA, HSTS), actuator exposure limited to health/prometheus, verify no secrets/OTP in logs via `backend/src/test/java/com/pnh/shared/integration/LogLeakIT.java` scanning captured logs during a full OTP + config-change flow
- [X] T185 Serve built console from backend (copy `frontend/dist` into `backend/src/main/resources/static` via a `maven-resources-plugin` `copy-resources` execution (phase `prepare-package`) in Maven profile `prod`; optionally `frontend-maven-plugin` builds the SPA first) and SPA fallback controller `backend/src/main/java/com/pnh/admin/adapter/in/web/SpaForwardController.java`
- [X] T186 [P] Integration test `backend/src/test/java/com/pnh/admin/integration/ProvisioningCliIT.java` (FR-032/034, fix U3): `create-client` prints the generated client secret and Kafka signing key exactly once and stores only bcrypt hash / encrypted key; `update-client` (rate limit, scopes, allow_direct_content), `rotate-client-secret`, `rotate-kafka-key`, `deactivate-client`, `create-operator` (role ADMIN|TEMPLATE_EDITOR|SUPPORT_VIEWER), `reset-operator-password`, `deactivate-operator` each write a `pnh_config_audit_log` entry with actor `cli:<os-user>` and bump `version`; invalid client_id (`^[a-z0-9-_]{3,50}$`) or role rejected with non-zero exit code
- [X] T187 Implement provisioning CLI `backend/src/main/java/com/pnh/admin/adapter/in/cli/ProvisioningCommands.java` (activated with profile `cli`: `java -jar pnh.jar --spring.profiles.active=cli <command> [--options]`, no web server/listeners started) delegating to `ClientProvisioningService` (`backend/src/main/java/com/pnh/coreapi/application/ClientProvisioningService.java`) and `OperatorProvisioningService` (`backend/src/main/java/com/pnh/admin/application/OperatorProvisioningService.java`), which publish `ConfigChanged`; document usage in `docs/operations.md`; local seed migration (T046) stays dev-only and exempt (make T186 pass)
- [X] T188 [P] Documentation: `README.md` (architecture overview, module map, run locally, Mailtrap/Android SMS Gateway/FCM setup), `docs/operations.md` (alerts, stuck policy, Redis fallbacks, partition maintenance)
- [X] T189 Run full quickstart.md validation Q1–Q17 and record results in `specs/001-notification-hub/checklists/quickstart-run.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)** → **Foundational (Phase 2)** → user stories → **Polish (Phase 14)**.
- Foundational BLOCKS all stories.

### User Story Dependencies

| Story | Depends on | Notes |
|-------|-----------|-------|
| US1 (P1) | Foundational | MVP; uses seeded templates/channel configs |
| US2 (P1) | US1 (needs notifications flowing) | callbacks + results |
| US3 (P1) | US1 | extends intake/dispatcher/orchestrator |
| US4 (P2) | US1 (+US3 for OTP lane test) | reuses SubmitNotificationUseCase |
| US5 (P2) | US1 | dispatcher rule; OTP bypass test needs US3 |
| US7 (P2) | Foundational (template read model from US1 T081–T084) | console only |
| US8 (P2) | US1 (data to search) | console only |
| US11 (P2) | US1, US2 (results), US4 for INGEST rule signal | alert rule INGEST_REJECTED_SPIKE testable after US4 |
| US6 (P3) | US1 (PUSH SENT) | |
| US9 (P3) | US1 | |
| US10 (P3) | Foundational (config read side) | hot reload exercised with US1 adapters |

### Within Each User Story

- Tests first (must fail) → domain/entities → application services → adapters/controllers → frontend.
- Same-file tasks are sequential (e.g., T087 → T114 IntakeValidator; T093 → T117 DeliveryOrchestrator; T092 → T129 DispatchListener).

### Parallel Opportunities

- Phase 1: T003–T008 in parallel after T002.
- Phase 2: T010–T034 tests in parallel; T036–T040 in parallel; T049–T055 in parallel (T051 after T049/T050); T062, T065, T067, T069 in parallel.
- After US1: US2, US3, US7, US8, US9, US10 can proceed in parallel by different developers (US7/US8/US9/US10 are mostly console + read paths).
- Inside stories: all test tasks marked [P]; adapters T094/T095/T096 in parallel.

---

## Parallel Example: User Story 1

```bash
# Tests first (parallel):
Task: "T070 Contract test SendContractTest.java"
Task: "T071 SendTemplateEmailIT.java"
Task: "T072 IdempotencyIT.java"
Task: "T073 DirectContentIT.java"
Task: "T074 IntakeValidationIT.java"
Task: "T075 DeliveryResilienceIT.java"
Task: "T077 NoResendAfterCrashIT.java"
Task: "T078 RateLimitIT.java"
Task: "T079 PlaceholderParserTest/TemplateRendererTest"
Task: "T080 RecipientValidatorTest.java"

# Then independent building blocks (parallel):
Task: "T081 Template entity + repository"
Task: "T082 PlaceholderParser"
Task: "T085 RecipientValidator"
Task: "T086 Request DTOs"
Task: "T094 SmtpEmailProvider"
Task: "T095 GenericHttpSmsProvider"
Task: "T096 FcmPushProvider"
```

## Parallel Example: User Story 11

```bash
Task: "T145 StuckSweeperIT.java"
Task: "T146 AlertEvaluationIT.java"
Task: "T147 AlertConsoleContractTest.java"
Task: "T148 alerts.test.tsx"
# then
Task: "T149 StuckNotificationSweeper"   # tracking module
Task: "T150 alerting domain entities"    # alerting module (parallel with T149)
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1 Setup → Phase 2 Foundational.
2. Phase 3 US1 → validate quickstart Q1, Q2, Q6 → demo (REST send, idempotency, 3 channels, retry/CB, no resend after crash).

### Incremental Delivery

1. MVP (US1) → US2 (tracking/results/callbacks) → US3 (OTP) = all P1.
2. P2: US4 (Kafka) → US5 (preferences) → US7 (templates UI) → US8 (audit log UI) → US11 (stuck sweeper + alerts).
3. P3: US6 (inbox) → US9 (dashboard) → US10 (channel config UI).
4. Polish: extensibility proof (Zalo readiness), retention, crash/Redis/load tests, E2E, hardening.

### Parallel Team Strategy

- Backend dev A: US1 → US3 → US4; Backend dev B: US2 → US5 → US11; Full-stack dev C: US7 → US8 → US10; Dev D: US6 → US9 → Polish E2E.

---

## Notes

- [P] = different files, no dependency on incomplete tasks.
- Every task cites exact paths; constraints quoted from data-model.md / contracts must be implemented verbatim.
- Commit after each task or logical group; keep `./mvnw verify` and `pnpm test` green at every checkpoint.
- Do not log, return or persist OTP values or secrets in clear text anywhere (constitution IV).
- Never call `LocalDate.now()`, `LocalDateTime.now()`, `Instant.now()` etc. directly: inject the `Clock` bean and use `X.now(clock)`; tests use `Clock.fixed`/`PnhClock` instead of sleeps (constitution VI v1.2.0, enforced by `ClockUsageArchTest`).
