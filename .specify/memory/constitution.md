<!--
Sync Impact Report
- Version change: 1.1.0 → 1.2.0 (MINOR: materially expanded guidance)
- Modified principles:
  - VI. Code Quality, Simplicity & Extensibility — added mandatory time-source rule
    (all current date/time via an injected `java.time.Clock` bean; direct `now()` calls forbidden;
    enforced by architecture test)
- Added sections: none
- Removed sections: none
- Templates requiring updates: none (read at runtime)
- Follow-up TODOs: none
-->

# Project Notification Hub (PNH) Constitution

## Core Principles

### I. Clean Architecture & Module Boundaries

- Each business module (core-api, template, dispatcher, provider-*, tracking, and future modules)
  MUST be a self-contained module with an explicit public API; other modules MUST NOT reference
  its internal packages.
- Inside a module, dependencies MUST point inward: domain → application (use cases/ports) →
  adapters (web, messaging, persistence, external providers). Domain code MUST NOT depend on
  frameworks, persistence, messaging, or provider SDKs.
- Cross-module communication MUST go through published application events or a module's
  published API — never through shared tables or another module's repositories.
- External providers (SMS, Email, Push, and future channels such as Zalo) MUST be integrated
  behind a port so a provider can be added or replaced by writing a new adapter only.
- Module boundaries MUST be verified automatically in the build (module structure /
  architecture tests); a boundary violation fails the build.

Rationale: isolation keeps channel failures and changes local, and makes the system extensible
without rewriting core flows.

### II. Test-First & Layered Testing (NON-NEGOTIABLE)

- New behavior MUST start with a failing test (Red → Green → Refactor). Bug fixes MUST start
  with a test that reproduces the bug.
- Required test layers:
  - Unit tests for domain and use-case logic, with no framework or I/O.
  - Module tests per module verifying its events and published API in isolation.
  - Integration tests against real infrastructure (database, message broker) using disposable
    containers — not in-memory substitutes — for persistence, idempotency, and messaging.
  - Contract tests for every external contract: REST endpoints, ingest/result stream payloads,
    and provider callbacks.
- Line coverage of domain and application layers MUST be ≥ 80%; critical paths (idempotency,
  status transitions, retry/expiry, opt-out rules, masking) MUST have explicit tests.
- Tests MUST be deterministic: no reliance on wall-clock sleeps, shared state, or test order.
- Frontend features MUST have component tests for logic-bearing components and at least one
  end-to-end test per primary screen flow.

Rationale: delivery guarantees (exactly-once, no loss, correct status) can only be trusted when
proven by repeatable tests against real infrastructure.

### III. Reliability by Design

- Every inbound request MUST be idempotent on its client request identifier; every consumer of
  events or stream messages MUST be safe to receive the same message more than once.
- State changes and the events that announce them MUST be persisted atomically (event
  publication registry / outbox); no event may be published only in memory.
- Notification status MUST only move forward along the defined lifecycle; regressions MUST be
  rejected by the domain model.
- Only transient errors MAY be retried, with bounded attempts and exponential backoff; calls to
  external providers MUST have timeouts and circuit breakers.
- An external side effect (e.g., a provider call) MUST be preceded by a durable "in progress"
  marker; work interrupted after that marker MUST NOT be replayed automatically. It MUST be
  resolved by a bounded timeout to an explicit failure state and surfaced through alerting.
- Partitioning/ordering keys MUST be chosen for even load distribution (user identifier by
  default), never for low-cardinality values such as channel.

Rationale: a notification hub is only useful if clients can rely on "sent once, never lost,
status always true."

### IV. Security & Sensitive Data Protection

- Every endpoint MUST be authenticated (client credentials for system clients, sign-in for
  operators) and authorized by least privilege (operator roles enforced server-side, not only in
  the UI).
- Secrets (provider credentials, client secrets) MUST be stored encrypted or hashed, MUST never
  be returned in full after saving, and MUST never appear in logs, events, or error messages.
- OTP values MUST be masked everywhere except the message handed to the provider; recipient
  addresses MUST be masked in list views and logs.
- User-supplied values rendered into HTML MUST be escaped; provider callbacks MUST be verified
  before they change any state.
- Dependencies MUST be scanned for known vulnerabilities in CI; high/critical findings block
  release unless an exception is recorded.

Rationale: the hub handles OTPs, contact data, and provider credentials — a leak is a direct
security incident for every client system.

### V. Observability & Auditability

- Logs MUST be structured and carry correlation identifiers (notification ID, client request ID,
  client ID) end to end across modules and asynchronous hops.
- Each channel/provider MUST expose metrics for volume, success/failure, latency, retries, and
  circuit-breaker state; distributed tracing MUST cover request → dispatch → provider call.
- Every notification status transition MUST be recorded with timestamp and error details.
- Every configuration record MUST carry created_by, updated_by, and a version (optimistic
  locking), and every configuration change MUST be written to an append-only audit log.

Rationale: operators must be able to answer "what happened to this message, and who changed
what" without reading code.

### VI. Code Quality, Simplicity & Extensibility

- Code MUST pass automated formatting, linting, and static analysis with zero new violations.
- Prefer the simplest design that satisfies the spec (YAGNI); added complexity (new module,
  new infrastructure, new abstraction layer) MUST be justified in the plan's Complexity Tracking.
- Names MUST reflect the domain language of the spec (notification, template, channel,
  preference, client request identifier).
- Public contracts (REST under `/api/v1`, stream payloads) MUST evolve backward-compatibly;
  breaking changes require a new version and a migration note.
- Database changes MUST be made through versioned, forward-only migrations.
- Time source: application code MUST obtain the current date/time only from the injected
  `java.time.Clock` bean (e.g. `LocalDateTime.now(clock)`, `clock.instant()`). Calls such as
  `LocalDate.now()`, `LocalDateTime.now()`, `Instant.now()`, `ZonedDateTime.now()`,
  `OffsetDateTime.now()`, `System.currentTimeMillis()`, `new Date()` or `Clock.system*()` outside
  the single clock configuration class are forbidden, and application SQL MUST NOT use
  `now()`/`CURRENT_TIMESTAMP` for business logic (timestamps are passed as parameters). The rule
  is enforced by an automated architecture test. Rationale: deterministic tests and the ability to
  run non-production environments at any chosen date.

Rationale: a long-lived integration hub is maintained by many hands; consistency and small,
explicit designs keep change cheap.

## Technology & Architecture Constraints

- Backend: Java 21, Spring Boot 4.x, Spring Modulith; Clean Architecture within each module.
- Frontend: React 19 with Mantine UI (v7+), TypeScript.
- Persistence: PostgreSQL as system of record (including event publication registry).
- Messaging: Apache Kafka for ingestion and result streams, keyed by user identifier.
- Caching & coordination: Caffeine for in-process caches; Redis accessed via Redisson for
  distributed cache invalidation, distributed locks, rate limiting and short-lived idempotency
  fast-paths. Redis MUST NOT be the system of record: losing Redis may degrade performance or
  accuracy of limits but MUST NOT lose or duplicate notifications.
- Resilience: Resilience4j (retry, circuit breaker, time limiter) for every provider call.
- Introducing a technology outside this list MUST be proposed in a plan with rationale and
  approved via the amendment process if it becomes a project-wide standard.

## Development Workflow & Quality Gates

- Work follows the Spec Kit flow: specify → clarify → plan → tasks → implement. Every plan MUST
  include a Constitution Check against principles I–VI, and violations MUST be either fixed or
  justified in Complexity Tracking.
- Every change goes through a pull request with at least one reviewer approval; reviewers MUST
  verify test-first evidence, module-boundary compliance, and absence of secret/OTP leakage.
- CI gates (all MUST pass before merge): build, formatting/lint/static analysis, unit + module +
  integration + contract tests, coverage threshold, architecture/module verification,
  dependency vulnerability scan.
- Feature work happens on a feature branch; main MUST always be releasable.

## Governance

- This constitution supersedes other practices and conventions for this project. Where a guide
  conflicts with it, the constitution wins.
- Amendments are made via `/speckit-constitution`, reviewed in a pull request, and recorded with
  an updated version and amendment date. Changes that affect existing code MUST include a
  migration/remediation plan.
- Versioning follows semantic versioning: MAJOR for removing or redefining a principle, MINOR
  for adding a principle/section or materially expanding guidance, PATCH for clarifications and
  wording.
- Compliance is checked at plan time (Constitution Check), at review time (PR checklist), and
  automatically in CI where a rule is machine-verifiable.

**Version**: 1.2.0 | **Ratified**: 2026-11-03 | **Last Amended**: 2026-11-03
