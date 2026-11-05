# Quickstart Validation Run (T189)

**Date**: 2026-11-05 · **Build**: backend `./mvnw verify` green (102 unit + 122 integration tests, JaCoCo gate met),
frontend lint/typecheck/23 unit tests, Playwright E2E 8/8 (T183).
**Stack**: `deploy/local/compose.yaml` on moved host ports (`PNH_PG_PORT=15432 PNH_KAFKA_PORT=19092
PNH_REDIS_PORT=16379`; other services on defaults), backend profile `local` (Maven `spring-boot:run`, later
`target/pnh.jar` for two instances), console via Vite on :5173. `kcat` was not installed: the Kafka CLI inside the
`kafka` container was used instead.

## Results

| # | Scenario | Result | Evidence |
|---|----------|--------|----------|
| Q1 | Template send + idempotency | PASS | 202 `RECEIVED` → replay 200 `Idempotent-Replay: true`, same `notification_id`; Mailpit exactly one "Hello An" |
| Q2 | Direct content + validation | PASS | SMS direct 202, WireMock body verbatim; template+content 422 `CONTENT_MODE_CONFLICT`; EMAIL without subject 422 `MISSING_CONTENT_FIELD`; missing variable 422 `MISSING_VARIABLES [name]`; `<script>`/`onclick`/`javascript:` removed (Mailpit HTML `<p>Hi l</p>`), timeline `CONTENT_SANITIZED`; template edited right after 202 → email still uses the accepted content |
| Q3 | Tracking + result stream | PASS | `/track` → `SENT`; one result keyed `user_99`, `sequence: 1`, `event_id`; unknown request id 404; headers exactly `client_id, channel_type, status, event_id` (after fix 1) |
| Q4 | OTP priority, expiry, masking | PASS | (0) `expired_minutes=5` → `expires_at` = acceptance + 5 min; (1) `654321` reached WireMock, console/track show `******`, 0 hits in backend log, encrypted payload cleared after SENT; (2) 503 + 10 s delay, expiry +3 s → one attempt (`PROVIDER_TIMEOUT`) then `FAILED OTP_EXPIRED`, no attempt after expiry; (3) covered by `SloPerformanceTest` SC-012 (OTP p95 accept→SENDING 314 ms under a STANDARD backlog) |
| Q5 | Opt-out + critical bypass | PASS | preferences `EMAIL=false`; STANDARD → `FAILED USER_OPTED_OUT`, nothing in Mailpit; OTP → `SENT`, timeline `OPT_OUT_OVERRIDDEN` |
| Q6 | Retry + circuit breaker | PASS | 2×503 then 200 → `SENT`, `retry_count=2`, 2× `ATTEMPT_FAILED`; 400 → `FAILED INVALID_RECIPIENT` (documented mapping of `INVALID_NUMBER`), no retry; always-503 at 20 SMS/s × 15 s → `resilience4j_circuitbreaker_state{GENERIC_HTTP_SMS}=open`, 300 SMS `PROVIDER_UNAVAILABLE` (circuit open), 15 parallel emails `SENT`; stub removed → closed after 8 s, new SMS `SENT` |
| Q7 | Delivery callbacks | PASS | signed DELIVERED → `DELIVERED`, result `sequence: 2`; re-post → no new result (`CALLBACK_IGNORED`); bad signature → 401, no state change, `CALLBACK_IGNORED invalid signature` (+ WARN log after fix 7) |
| Q8 | Crash safety | PASS | 2,000 emails via `pnh.notification.ingest`, `kill -9` at 356 accepted (3 RECEIVED, 5 SENDING), restart 10 s; at +3 min all 2,000 accepted, 1,995 `SENT`, `event_publication` incomplete = 0; the 5 in-flight → `RESUBMISSION_SKIPPED`, then after the 5 min stuck timeout `FAILED DELIVERY_STATE_UNKNOWN` + `SWEPT`; 0 notifications entered SENDING twice |
| Q9 | Load | PASS (1 min) | demo-client limit raised with the CLI; k6 500/s → 30,001 requests at 496/s, p95 ack 60.6 ms, 0 failed, 0 dropped. Result topic (key `user_id`, 80 % EMAIL) partition max/mean 1.08 (< 2×). Shortened from 5 min on a laptop; the 5 min run is the `performance` CI job |
| Q10 | Inbox | PASS | 3 PUSH + 1 EMAIL → 3 items, `unread_count: 3`; PATCH read sets `read_at`, repeat returns the same; bulk-read (2 push + email + random) → `updated_count: 2`, `not_found_ids` [email, random]; other user → 404 |
| Q11 | Console roles, templates, audit | PASS | E2E specs (auth, templates incl. 409 dialog, channel-configs, config-change-log, notifications) 8/8 three runs; editor/support `GET /console/api/channel-configs` → 403; `CHANNEL_CONFIG UPDATE` by admin with `password: ***changed***` |
| Q12 | Dashboard | PASS | after the backlog drained, `/dashboard/channel-rates` (24 h) equals SQL exactly: EMAIL 26,033/6, PUSH 1,518/0, SMS 4,483/304, in progress 0 |
| Q13 | Stuck + incident alert | PASS | `stuck-timeout=1m`, rule 5/10 min; 10 SMS in SENDING, `kill -9`, restart: WireMock got no new request; all 10 `FAILED DELIVERY_STATE_UNKNOWN` + `SWEPT` ~77 s after the kill; 10 FAILED results; one email "SMS – 10 in 10 min" with ids only (no numbers/content), history FIRED/SENT; repeat within 30 min → no second alert; late DELIVERED callback → `DELIVERED`, `sequence: 2`; add oncall@ / deactivate ops@ / test alert → only oncall@ received it; audit ALERT_RECIPIENT CREATE/DEACTIVATE. Note: `RESUBMISSION_SKIPPED` appears only when resubmission (2 min) precedes the sweep — quickstart text updated |
| Q14 | Redis degradation | PASS | Redis stopped up to 150 s during 50 req/s: 15,001 requests, 0 failed; health `DEGRADED` (HTTP 200), `pnh_redis_fallback_active=1`; duplicate during outage → 200 replay; jobs ran under the Postgres advisory lock after 3 failures; Redis back → `UP`, fallback 0 |
| Q15 | Cache invalidation across replicas | PASS | two instances (8080/8081): template edited via 8080, next send via 8081 used the new subject |
| Q16 | Channel SPI readiness | PASS | `*ProviderContractTest`: SMTP, generic SMS, FCM, FAKE 6/6 each; `ChannelExtensibilityIT` 2/2 (FAKE end to end, `DIRECT_CONTENT_NOT_ALLOWED`) |
| Q17 | System time override | PASS | FIXED 2027-01-01T08:00Z seen by the 2nd instance after 53 ms; send → `created_at` 08:00Z, OTP `expires_at` 08:05Z; OFFSET +10 min → `expires_at` 5 real minutes ahead rejected as past (sweeper under the overridden clock: `StuckSweeperIT`); audit `CLOCK_OVERRIDE UPDATE` by admin; DELETE → SYSTEM; `prod` + override-enabled → startup fails "must not be true with profile 'prod'"; prod defaults → endpoint unavailable (`ClockOverrideIT.disabledOverrideIs404`) |

## Defects found and fixed during this run

| # | Defect | Fix | Regression test |
|---|--------|-----|-----------------|
| 1 | Result messages carried Spring type headers (`__TypeId__` with an internal class name, `spring_json_header_types`) beyond the Kafka contract | Converter that maps only the four contract headers as plain strings (`KafkaConfig.resultMessageConverter`) | `ResultMessageContractIT` asserts the exact header set |
| 2 | Backend could not start with profile `local`: Flyway treated `${otp_code}` in seed SQL as a placeholder | `spring.flyway.placeholder-replacement: false` | startup (no migration uses placeholders) |
| 3 | Health `http-mapping` replaced Boot's defaults: `DOWN`/`OUT_OF_SERVICE` returned 200 | explicit mapping (503 for DOWN/OUT_OF_SERVICE, 200 for UP/DEGRADED) | `HealthStatusMappingIT` |
| 4 | Connection-pool deadlock under load: alert-signal listeners ran after commit on the delivery thread while Modulith opened a second transaction per event | plain listener + after-commit synchronization, no event publication | `AlertEvaluationIT.signalsAreRecordedAfterCommitWithoutEventPublications`, `RedisOutageIT` |
| 5 | Channel-config connection test bumped the optimistic `version` (false 409 for editors, gaps in audit versions) and held a DB transaction during the network call | bulk update of `last_test_*` only, no transaction around the provider call | `ChannelConfigManagementIT` asserts unchanged version |
| 6 | Template editor reset the form when the rich-text editor became ready (typed edits lost) | form reset only on new server data; editor content synced separately | E2E `templates.spec.ts` (second tab edit + 409) |
| 7 | Unmatched/unverifiable provider callbacks left no trace (FR-017 "logged") | WARN `CALLBACK_IGNORED` for every ignored callback | — (log only; callback behaviour covered by `SmsCallbackIT`) |
| 8 | Provisioning CLI never exited (non-daemon threads; exit code unused); its 50-connection pool exhausted local Postgres next to a running hub | `main()` exits with the command's code under profile `cli`; CLI pool 2 | manual: exit 0 / 1 verified |
| 9 | `CHANNEL_FAILURE_RATE` sent "resolved" while the rate was still 87.5 % (volume below `min_sample`) | `min_sample` only guards firing | `AlertEvaluationIT.failureRateResolvesOnlyWhenTheRateClearsNotWhenVolumeDrops` |
| 10 | Alert windows counted signals stamped after the clock's `now` (clock moved back) | windows end at the Clock's now (Redis, local, DB fallback) | `AlertEvaluationIT` (7 tests, time-travelling) |
| 11 | Console sessions were in memory per instance: on 2–4 replicas behind a load balancer operators got random 401s | Spring Session JDBC (Flyway `V6__console_sessions.sql`) | `ConsoleSessionSharingIT` (login on A, valid on B, logout ends it) |
| 12 | Advisory-lock fallback for jobs was silent | WARN on entry, INFO on exit | — (log only) |

Environment/doc adjustments: Mailpit retention raised (`MP_MAX_MESSAGES=20000`, default 500 hid Q8 counts); local
Postgres `max_connections=200` for two instances + CLI; quickstart Q9 (raise client limit), Q13 (sweep vs
resubmission order) and prerequisites (`kcat` alternative) updated; `docs/operations.md` (sessions, health mapping,
CLI exit codes, connection sizing, advisory-lock log).
