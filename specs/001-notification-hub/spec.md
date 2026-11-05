# Feature Specification: Project Notification Hub (PNH)

**Feature Branch**: `001-notification-hub`

**Created**: 2026-11-03

**Status**: Draft

**Input**: User description: "SYSTEM SPECIFICATION: PROJECT NOTIFICATION HUB — a centralized hub that accepts notification requests from registered client systems (synchronously and asynchronously), renders them from managed templates, routes them over SMS, Email and Push channels while honoring end-user channel preferences, tracks every notification through its delivery lifecycle, reports results back to clients, lets end users mark in-app notifications as read, and provides an operator console (dashboard, template management, channel configuration, Delivery Audit and Config Change Log)." Full technical input (stack, schema, API/stream contracts) is preserved in [technical-input.md](./technical-input.md) for the planning phase.

## Clarifications

### Session 2026-11-03

- Q: Can some notification types bypass a user's channel opt-out? → A: Yes — OTP templates and templates an operator flags "critical" bypass opt-out; the override is recorded (Option B).
- Q: Which notifications are "in-app" and is an inbox in scope? → A: Only Push notifications form the in-app inbox, with an inbox listing and unread count (Option A).
- Q: Operator console roles? → A: Three roles — Admin, Template Editor, Support Viewer (Option C) — with a low-effort v1: screen/action-level enforcement, roles assigned outside the console; fine-grained permissions deferred to a later phase.
- Q: Does the hub generate/verify OTPs? → A: No — the client generates the OTP and controls expiry, resend count and wrong-entry limits; the hub only delivers (Option A).
- Added by user: OTP delivery via SMS and Email as a first-class flow; Zalo planned as a future channel.
- Added by user: Requests support two content modes — template mode (template code + variables only) and direct-content mode (no template; client supplies the full message content).
- Added by user: All configuration records carry created-by, updated-by and a version number for audit trail.
- Added by user: Stream messages carry a routing key so load can be partitioned; partitioning by user is preferred over channel to avoid imbalance.
- Q: What if the hub crashes after calling a provider but before recording the outcome? → A: The notification must not stay stuck and must not be re-sent; after a timeout it is marked FAILED, and if such failures spike within a time window, admins are alerted by email. Admins maintain a list of alert-recipient emails for all incident alerts.
- Q: Which SMS/Email providers for v1? → A: A free SMS gateway and a sandbox/mock email service (e.g., Mailtrap) for development and testing.
- Q: Zalo? → A: Pending; the channel integration point must be ready so Zalo is added as one more provider adapter with minimal change.
- Added by user (technical): distributed cache and locking support (recorded in technical-input.md).
- Terminology: **Delivery Audit** = searching notifications and their delivery timeline (US8; Admin, Support Viewer). **Config Change Log** = history of configuration changes (FR-034; Admin only). The term "audit log" alone is not used.
- Added by user: the system date/time must be overridable to any date (for testing and non-production environments); all time-dependent behavior must use the single system time source.

## User Scenarios & Testing *(mandatory)*

### Actors

- **Client System**: an internal or partner application registered with the hub that submits notification requests and consumes delivery results.
- **End User (Recipient)**: the person who receives a notification and can manage their channel preferences and in-app read state (via a client application acting on their behalf).
- **Operator**: staff using the operator console to manage templates, channel settings, and to monitor and investigate deliveries.
- **Channel Provider**: an external SMS gateway, email service, or push notification service that delivers messages and may report delivery outcomes back.

### User Story 1 - Send a notification reliably, by template or direct content (Priority: P1)

A Client System submits a notification request containing its own unique request identifier, the target user, the channel, the recipient address, and the message in one of two modes: **template mode** (a template code plus variable values only) or **direct-content mode** (no template code; the client supplies the full content itself — e.g., subject and body for Email, text for SMS, title and body for Push). The hub authenticates the client, rejects duplicates, renders the template with the variables, and delivers the message through the chosen channel's provider, retrying transient failures automatically.

**Why this priority**: This is the core value of the hub — without reliable, deduplicated delivery nothing else matters. It alone is a viable MVP (one channel, one template, one client).

**Independent Test**: Register one client and one active template, submit a request, and verify the recipient receives exactly one correctly-rendered message; resubmit the same request and verify no second message is sent.

**Acceptance Scenarios**:

1. **Given** an active client and an active Email template "OTP_VERIFICATION" with placeholders for an OTP code and expiry minutes (designated as the template's validity variable), **When** the client submits a valid request with those variables, **Then** the hub immediately acknowledges acceptance with a notification identifier, derives the OTP expiry from the expiry-minutes value, and the recipient receives an email whose subject and body contain the supplied values.
2. **Given** a request that was already accepted, **When** the same client resubmits a request with the same request identifier, **Then** the hub does not send another message and responds with the original notification identifier and its current status.
3. **Given** two different clients, **When** both submit requests using the same request identifier value, **Then** both are treated as distinct notifications (identifiers are unique per client).
4. **Given** the channel provider responds with a transient error (temporary outage, timeout), **When** the hub attempts delivery, **Then** it retries up to 3 times with increasing delays, and the notification ends as SENT if a retry succeeds or FAILED with the provider's error if all retries are exhausted.
5. **Given** the provider rejects the message with a permanent error (e.g., invalid phone number), **When** the hub attempts delivery, **Then** it does not retry and marks the notification FAILED with the provider's error code and message.
6. **Given** a request with invalid credentials, an unknown/inactive template, a template whose channel differs from the requested channel, missing template variables, or a malformed recipient address for the channel, **When** it is submitted, **Then** the hub rejects it immediately with a specific, actionable error and sends nothing.
7. **Given** no template code, **When** a client submits an Email request with a subject and HTML body, an SMS request with text, or a Push request with title and body, **Then** the hub sends exactly that content (no placeholder substitution) and records it as the notification's content.
8. **Given** a request that contains both a template code and direct content, or neither, or direct content missing a field the channel requires (e.g., Email without subject), **When** it is submitted, **Then** the hub rejects it with a specific validation error.
9. **Given** a direct-content request declared as OTP, **When** it is processed, **Then** it receives the same OTP handling (priority, expiry, masking of the supplied OTP value, opt-out bypass) as a template-based OTP.
10. **Given** a client has exceeded its per-minute request allowance, **When** it submits another request, **Then** the hub rejects it with a rate-limit error indicating when it may retry.

---

### User Story 2 - Track delivery and receive results (Priority: P1)

A Client System needs to know what happened to each notification. It can look up the current status by its own request identifier at any time, and it also receives an asynchronous result message each time a notification reaches an outcome (sent, delivered, failed), so it does not need to poll.

**Why this priority**: Clients integrating notifications for business flows (OTP, transactional alerts) cannot operate without knowing outcomes; tracking is part of the minimum viable contract.

**Independent Test**: Submit a notification, query its status by request identifier and observe it progress; subscribe to the result stream and verify one result message per outcome with matching identifiers.

**Acceptance Scenarios**:

1. **Given** an accepted notification, **When** the client queries by its request identifier, **Then** it receives the current status, the error code (if any), and the time of the last status change.
2. **Given** a client queries a request identifier it never submitted (or one belonging to another client), **When** the lookup runs, **Then** the hub responds "not found" and reveals no other client's data.
3. **Given** a notification transitions to SENT, DELIVERED, or FAILED, **When** the transition is recorded, **Then** a result message containing the notification identifier, the client's request identifier, the status, the error code (or empty), and a timestamp is published to the client-facing result stream.
4. **Given** a provider later reports via callback that a sent message was delivered (or bounced), **When** the callback is received and verified as authentic, **Then** the notification status is updated to DELIVERED (or FAILED with the provider's reason) and a new result message is published.
5. **Given** a provider callback arrives that cannot be authenticated or does not match a known notification, **When** it is received, **Then** it is rejected/ignored and logged without changing any notification.

---

### User Story 3 - Deliver one-time passwords (OTP) via SMS and Email (Priority: P1)

A Client System (e.g., a login or payment service) needs to send a one-time password to a user over SMS or Email. OTP messages are time-critical and sensitive: they must go out faster than ordinary notifications, must never arrive after the code has expired, and the code itself must not be exposed to operators or in logs. The same flow must later work over Zalo without changes for clients beyond choosing the new channel.

**Why this priority**: OTP is the most time- and security-critical use case of the hub; a late or leaked OTP directly breaks login/payment flows.

**Independent Test**: Send an OTP via SMS and via Email with a 5-minute validity; verify each arrives quickly with the correct code, the code is masked in the operator console, and an OTP whose validity has elapsed before sending is not delivered.

**Acceptance Scenarios**:

1. **Given** an active OTP template for SMS and one for Email, **When** a client submits an OTP request with the code and its validity period, **Then** the message is delivered on the requested channel with the code and validity rendered correctly, ahead of non-OTP notifications waiting in the queue.
2. **Given** an OTP request whose validity period elapses before a delivery attempt succeeds (e.g., during retries or provider outage), **When** the next attempt would occur, **Then** the hub stops trying and marks it FAILED with an "OTP expired" error, so the user never receives a stale code.
3. **Given** a delivered OTP notification, **When** an operator views it in the Delivery Audit or the content appears in any system log, **Then** the OTP code is masked (e.g., `******`), while the rest of the content and lifecycle remain visible.
4. **Given** a client resubmits the same OTP request identifier (e.g., user double-clicks "Send code"), **When** it is received, **Then** no second OTP message is sent (standard deduplication applies); a genuine resend uses a new request identifier.
5. **Given** a client sends several OTP requests for the same user with different request identifiers (resends), **When** they are received, **Then** the hub delivers each one; limiting resends is the client's responsibility.
6. **Given** the hub later adds Zalo as a channel, **When** a client submits an OTP request with channel ZALO and a Zalo OTP template, **Then** it follows the identical OTP flow (priority, expiry, masking, tracking, results) with no change to the request format.

---

### User Story 4 - Asynchronous bulk ingestion (Priority: P2)

High-volume Client Systems submit notification requests by publishing them to the hub's ingestion stream instead of calling it synchronously, using the same request format. The hub processes them with the same validation, deduplication, and delivery guarantees.

**Why this priority**: Needed for high-throughput producers and batch campaigns, but the synchronous path already delivers core value.

**Independent Test**: Publish a batch of requests (including duplicates and one invalid request) to the ingestion stream; verify each valid unique request is delivered once, duplicates are skipped, and the invalid one produces a FAILED result message with a validation error.

**Acceptance Scenarios**:

1. **Given** a valid request published to the ingestion stream, **When** the hub consumes it, **Then** it is processed identically to a synchronous request and its outcome is reported on the result stream.
2. **Given** an invalid request on the ingestion stream (bad template, missing variables, unknown client), **When** the hub consumes it, **Then** nothing is sent, and if the client and request identifier can be determined, a FAILED result with a validation error code is published; otherwise the message is set aside for operator review.
3. **Given** the hub restarts unexpectedly while requests are mid-processing, **When** it comes back up, **Then** every accepted request still completes processing exactly once — none are lost and none are sent twice.

---

### User Story 5 - Respect end-user channel preferences (Priority: P2)

An End User can opt in or out of each channel (SMS, Email, Push). Before delivering, the hub checks the recipient's preference for the requested channel and does not send over a channel the user has disabled.

**Why this priority**: Required for user trust and regulatory compliance (consent), but the hub can deliver value before preference management exists by defaulting everyone to enabled.

**Independent Test**: Disable Email for a user, submit a STANDARD Email notification and verify no email is sent and it is recorded with an opt-out reason; then submit an OTP Email notification and verify it is delivered.

**Acceptance Scenarios**:

1. **Given** a user with no stored preference for a channel, **When** a notification for that channel is processed, **Then** it is treated as enabled and delivered.
2. **Given** a user who has disabled the Email channel, **When** a STANDARD Email notification from a non-critical template (or direct content) for that user is processed, **Then** no email is sent and the notification ends as FAILED with error code indicating the user opted out, and the client receives that result.
4. **Given** a user who has disabled the SMS channel, **When** a notification using an OTP template or a template flagged "critical" (e.g., security alert) is processed for SMS, **Then** it is delivered despite the opt-out, and the timeline records that the opt-out was overridden because of the template's category/flag.
3. **Given** a user re-enables a previously disabled channel, **When** a new notification for that channel arrives, **Then** it is delivered normally.

---

### User Story 6 - In-app inbox for Push notifications (Priority: P3)

Push notifications double as the user's in-app inbox. An End User, inside a client application, can list their Push notifications (newest first, paginated) with an unread count, and mark a single notification or several at once as read. The hub stores read state per notification so all of the user's devices show consistent read/unread state. SMS, Email and OTP notifications do not appear in the inbox.

**Why this priority**: Improves end-user experience but is not required for delivering messages.

**Independent Test**: For a user with three Push notifications and one Email, verify the inbox lists only the three Push items with unread count 3; mark one read individually and two in bulk; verify the unread count becomes 0 and another user's notification cannot be marked.

**Acceptance Scenarios**:

1. **Given** an unread notification belonging to the user, **When** the user marks it as read, **Then** it is recorded as read with the time it was read.
2. **Given** a notification already marked read, **When** it is marked read again, **Then** the operation succeeds and the original read time is preserved.
3. **Given** a list of notification identifiers, **When** the user bulk-marks them read, **Then** all identifiers that belong to that user are marked read, and the response reports how many were updated and which identifiers were not found or not owned.
4. **Given** a notification belonging to another user, **When** a user attempts to mark it read, **Then** the request is refused as not found and no state changes.
5. **Given** a user with Push notifications, **When** they open their inbox, **Then** they see their Push notifications newest first, paginated, each with title/body, time and read/unread state, plus the total unread count.
6. **Given** an SMS, Email or OTP notification, **When** a user tries to mark it read, **Then** it is refused as not found, because only Push notifications have inbox read state.

---

### User Story 7 - Template management (Priority: P2)

An Operator creates, views, edits, deactivates, and deletes notification templates in the operator console. Each template has a unique code, a name, a target channel, a body (and a subject for Email), and named placeholders. Email bodies are authored in a visual rich-text editor where placeholders are visually distinguished from normal text.

**Why this priority**: Templates must exist before anything can be sent; initially they could be seeded, but self-service authoring is essential for operations.

**Independent Test**: Create an Email template with two placeholders in the console, preview it with sample values, activate it, and successfully send a notification using it.

**Acceptance Scenarios**:

1. **Given** the template list, **When** an operator creates a template with a code that already exists, **Then** creation is rejected with a "code already in use" message.
2. **Given** an Email template being edited, **When** the operator inserts a placeholder such as `${name}`, **Then** it is visually highlighted in the editor and listed as a required variable.
3. **Given** a template with placeholders, **When** the operator previews it with sample values, **Then** they see the fully rendered subject and body exactly as recipients would.
4. **Given** an SMS or Push template, **When** the operator edits it, **Then** a plain-text editor is used and the character count is shown against the channel's length limit.
5. **Given** a template referenced by past notifications, **When** an operator deletes it, **Then** it is deactivated (not removed) so historical records remain intact, and new requests using it are rejected.
6. **Given** an operator edits an active template, **When** they save, **Then** notifications accepted afterwards use the new content, while already-rendered notifications keep their original content.

---

### User Story 8 - Delivery Audit investigation (Priority: P2)

An Operator investigating a complaint ("I never got my OTP") searches the delivery history by the client's request identifier, user identifier, or recipient address, opens a notification, and sees its full lifecycle as a timeline of status changes with timestamps, retry attempts, and any error details returned by the provider.

**Why this priority**: Essential for support and operations once the hub is in production.

**Independent Test**: Send a notification that fails after retries; find it in the Delivery Audit by recipient address and confirm the timeline shows each status change, each retry, and the provider's final error.

**Acceptance Scenarios**:

1. **Given** historical notifications, **When** an operator searches by any combination of request identifier, user identifier, recipient address, channel, status, client, and date range, **Then** matching notifications are listed (newest first, paginated) with key fields.
2. **Given** a notification in the results, **When** the operator opens it, **Then** they see the rendered content, recipient, channel, template, client, and a chronological timeline of every status transition including retries and provider error codes/messages.
3. **Given** a search with no matches, **When** it runs, **Then** an empty state explains that nothing matched the filters.

---

### User Story 9 - Operations dashboard (Priority: P3)

An Operator opens the dashboard to see sending volume over time (by hour or by day) and the success versus failure rate for each channel, to spot outages and trends at a glance.

**Why this priority**: Valuable for monitoring, but the Delivery Audit already enables investigation.

**Independent Test**: With a known set of seeded notifications across channels and outcomes, verify the dashboard's volume chart and per-channel success/failure rates match the seeded data.

**Acceptance Scenarios**:

1. **Given** notifications over the last 7 days, **When** the operator selects a daily granularity, **Then** a chart shows the number of notifications per day, broken down by channel.
2. **Given** the operator selects an hourly granularity for a single day, **When** the chart renders, **Then** it shows the count per hour.
3. **Given** a selected time range, **When** the dashboard loads, **Then** each channel shows its success rate and failure rate, where success = SENT or DELIVERED and failure = FAILED, excluding notifications still in progress.

---

### User Story 10 - Channel provider configuration (Priority: P3)

An Operator configures connection settings for each channel provider (e.g., email server host/port/credentials, push service account credentials file, SMS gateway endpoint and secret) in the console, without redeploying the system.

**Why this priority**: Providers can initially be configured at deployment time; console management is an operational convenience with security sensitivity.

**Independent Test**: Update the SMS gateway endpoint and secret in the console, run the built-in connection test, and confirm the next SMS uses the new settings.

**Acceptance Scenarios**:

1. **Given** saved provider settings, **When** an operator views them, **Then** secret values (passwords, API secrets, credential files) are masked and never displayed in full again.
2. **Given** an operator replaces a secret, **When** they save, **Then** the new value takes effect for subsequent deliveries without a system restart, and the change (who, when, which fields — not the secret value) is recorded.
3. **Given** new settings, **When** the operator runs "test connection", **Then** the hub reports whether it could authenticate with the provider and, if not, the provider's error.
4. **Given** invalid input (e.g., malformed credentials file, invalid URL), **When** the operator saves, **Then** the save is rejected with a field-specific error.

---

### User Story 11 - Incident alerts to administrators (Priority: P2)

Admins maintain a list of email addresses that receive incident alerts. When something goes wrong — notifications stuck without a known outcome and timed out, a provider becoming unavailable, a channel's failure rate spiking, or rejected stream messages piling up — the hub emails everyone on the list, without flooding them with repeated alerts for the same ongoing incident.

**Why this priority**: Without alerts, outages are discovered by end users first; the stuck-notification policy only works if someone is told when it fires at scale.

**Independent Test**: Add two recipients, simulate 20 stuck notifications within 10 minutes, and verify both recipients receive one alert describing the incident; a further spike within the cooldown sends no duplicate alert.

**Acceptance Scenarios**:

1. **Given** an Admin on the alert settings screen, **When** they add, deactivate or remove recipient emails, **Then** the list is saved (with audit trail) and used for the next alert; invalid email formats are rejected.
2. **Given** a notification that has been waiting for a provider outcome longer than the configured timeout (because the hub crashed or lost track after the provider call), **When** the stuck-check runs, **Then** it is marked FAILED with error "delivery state unknown", is NOT sent again, and the client receives the FAILED result.
3. **Given** stuck-timeout failures for a channel exceed the configured threshold within the configured window (default: 10 within 10 minutes), **When** the threshold is crossed, **Then** all active recipients get one email naming the incident type, channel, count, window, and a link/filter to the affected notifications in the Delivery Audit.
4. **Given** a provider's circuit has been open (provider unavailable) for longer than the configured duration, or a channel's failure rate exceeds the configured threshold over the window, or rejected ingest messages exceed the threshold, **When** detected, **Then** an alert is sent for that incident type.
5. **Given** an alert was sent for an incident type and channel, **When** the condition persists during the cooldown period (default 30 minutes), **Then** no duplicate alert is sent; when the condition clears, a single "resolved" email is sent.
6. **Given** no active recipients are configured, **When** an incident occurs, **Then** the incident is still recorded in the alert history visible to Admins.

---

### Edge Cases

- **Duplicate with different content**: A client resubmits an existing request identifier with different payload — the hub returns the original notification and does not send the new content (the first request wins).
- **Concurrent duplicates**: Two identical requests arrive at the same instant — exactly one is accepted and sent; the other receives the original's identifier.
- **Missing or extra variables**: Missing required placeholder values → rejected at intake; extra unused variables → ignored.
- **Unsafe variable content**: Variable values containing markup in Email templates are escaped so they cannot inject HTML/scripts into the message; direct-content Email HTML is sanitized (unsafe elements removed, not rejected) and the timeline notes that sanitization changed the content.
- **Content exceeding channel limits**: Rendered SMS exceeding the configured maximum length or Push exceeding the provider payload limit → FAILED with a content-too-long error, not silently truncated.
- **Crash after provider call**: The hub calls the provider, then crashes before recording SENT. On recovery the notification is never re-sent; after the stuck timeout it becomes FAILED ("delivery state unknown"). If the provider later reports via callback that it was actually delivered, the notification is corrected to DELIVERED and a new result is sent (the only permitted move out of FAILED).
- **Stuck before any provider call** (RECEIVED/PROCESSED left behind by a crash): processing resumes automatically after restart because no message was sent yet; if still unfinished after the processing timeout, it is marked FAILED ("processing timeout") and not sent.
- **Alert channel itself failing**: If the email provider used for alerts is down, the alert is recorded in alert history with its send failure and retried on the next evaluation; alerts are never routed through user opt-out rules.
- **Provider outage**: When a provider's failure rate exceeds 50% over a 10-second window, the hub temporarily stops calling that provider, fails fast instead of piling up waiting requests, and probes periodically to resume; other channels are unaffected.
- **Delivery callback arrives before "sent" is recorded** (provider reports delivery while the hub is still finishing the send) → a verified callback moves the notification straight to DELIVERED (or FAILED); the later "sent" acknowledgement only stores the provider reference and never moves the status back or emits a SENT result. Callbacks are matched by the hub's own notification identifier passed to the provider whenever the provider supports it. Duplicate callbacks never produce duplicate result messages.
- **Template edited or deactivated after acceptance** → no effect on the notification: its content was rendered at acceptance from the then-active template version.
- **Client deactivated** → all new requests from that client are rejected; in-flight notifications complete normally.
- **Result stream temporarily unavailable** → result messages are retained and published once it recovers; none are lost.
- **Invalid push token** reported by the provider (unregistered device) → FAILED with a specific "invalid recipient" error so the client can clean up the token.
- **Bulk mark-read with very large lists** → requests above a maximum (e.g., 500 identifiers) are rejected with a clear limit error.

## Requirements *(mandatory)*

### Functional Requirements

**Intake & client access**

- **FR-001**: System MUST accept notification requests from registered client systems through both a synchronous request interface and an asynchronous ingestion stream, using the same request format: client request identifier, user identifier, channel (SMS, EMAIL, PUSH), recipient address, and either (a) a template code with variable values, or (b) direct content without a template code.
- **FR-001a**: In direct-content mode, the request MUST carry the content fields the channel needs (Email: subject and body, HTML allowed; SMS: text; Push: title and body, optional data payload) and MAY declare a category (STANDARD by default, or OTP). A direct-content OTP request MUST supply the OTP value separately so the hub can mask it; direct-content STANDARD requests MUST NOT bypass user opt-out (only OTP does).
- **FR-001b**: A request MUST contain exactly one of template code or direct content; requests with both or neither MUST be rejected.
- **FR-002**: System MUST authenticate every client request using the client's registered credentials and reject requests from unknown or inactive clients.
- **FR-003**: System MUST enforce a per-client request allowance per minute (default 1,000, configurable per client) and reject excess requests with a rate-limit error.
- **FR-004**: System MUST require a client request identifier on every request and guarantee that, per client, each identifier results in at most one notification and at most one delivery attempt sequence; repeat submissions MUST return the original notification identifier and current status.
- **FR-005**: System MUST validate at intake: in template mode, template exists and is active, template channel matches the requested channel, and all required placeholders have values; in direct-content mode, all content fields required by the channel are present and within channel length limits; in both modes, and the recipient address is well-formed for the channel (phone number for SMS, email address for EMAIL, device token for PUSH); invalid requests MUST be rejected with a specific error code and message.
- **FR-006**: System MUST acknowledge accepted synchronous requests immediately with the notification identifier and status RECEIVED, without waiting for provider delivery.

**Rendering & routing**

- **FR-007**: In template mode, System MUST render the template subject (Email) and body by substituting named placeholders with the supplied variable values, escaping values in HTML content; in direct-content mode, System MUST use the supplied content as-is. In both modes the final content MUST be stored with the notification, along with which mode was used. Template rendering MUST happen when the request is accepted (before acknowledging), so the content always reflects the template version active at acceptance time, even if the template is edited or deactivated afterwards. Direct-content Email HTML MUST be sanitized against an allowlist of safe tags and attributes (scripts, event handlers, forms, iframes and `javascript:` URLs removed) before it is stored or sent.
- **FR-008**: System MUST check the user's preference for the requested channel before sending; channels without a stored preference are treated as enabled; for STANDARD, non-critical templates, disabled channels MUST NOT be sent to and the notification MUST be recorded as FAILED with an opt-out error code. Notifications using OTP templates or templates flagged "critical" by an operator MUST bypass the opt-out, and the override MUST be recorded in the notification's timeline.
- **FR-009**: System MUST route each notification to the provider integration for its channel; each provider integration MUST be isolated so a failure or outage in one channel does not affect the others.

**Delivery reliability**

- **FR-010**: System MUST retry delivery up to 3 times with exponentially increasing delays for transient failures (provider server errors, timeouts, network errors) and MUST NOT retry permanent failures (invalid recipient, rejected content, authentication failure).
- **FR-011**: System MUST temporarily stop sending to a provider whose failure rate exceeds 50% within a 10-second window, fail affected attempts fast with a provider-unavailable error (counting toward retries), and automatically resume once trial requests succeed.
- **FR-012**: System MUST durably record every accepted request and every hand-off between internal processing stages so that, after an unexpected restart, all in-progress notifications resume and complete with no loss and no duplicate sends.

**Tracking & results**

- **FR-013**: System MUST track each notification through the lifecycle RECEIVED → PROCESSED → SENDING → SENT → DELIVERED, or FAILED at any stage, and MUST never move a notification back to an earlier status (sole exception: FR-041).
- **FR-014**: System MUST record every status transition (including each retry attempt) with timestamp, attempt number, and any provider error code and message, forming the notification's timeline.
- **FR-015**: System MUST allow a client to retrieve the current status, error code, and last-updated time of its own notification by client request identifier, and MUST NOT expose other clients' notifications.
- **FR-016**: System MUST publish a result message (notification identifier, client request identifier, status, error code, timestamp) to the client-facing result stream whenever a notification reaches SENT, DELIVERED, or FAILED. Delivery to clients is at-least-once: each outcome is published as exactly one logical result carrying a unique event identifier and a per-notification sequence number, so a client that receives the same result more than once (e.g., after a broker retry) can discard the repeat by event identifier and ignore stale updates by sequence. SENT may be skipped: when a provider reports delivery (or failure) before the hub has recorded the send acknowledgement, the client receives only the DELIVERED (or FAILED) result for that notification.
- **FR-017**: System MUST accept delivery-status callbacks from providers, verify their authenticity, map them to the corresponding notification, update its status, and reject or log unverifiable or unmatched callbacks without side effects.

**End-user features**

- **FR-018**: System MUST let end users enable or disable each channel (SMS, EMAIL, PUSH) for themselves, with the change taking effect for notifications processed afterwards.
- **FR-018a**: System MUST give each end user an inbox listing their own Push notifications (excluding OTP), newest first and paginated, with read/unread state per item and a total unread count; SMS and Email notifications are not part of the inbox.
- **FR-019**: System MUST let an end user mark one of their own Push (inbox) notifications as read, recording the read time; repeated marks MUST be idempotent and preserve the first read time.
- **FR-020**: System MUST let an end user mark up to 500 of their own notifications as read in one operation, returning the count updated and the identifiers not found or not owned.

**Operator console**

- **FR-021**: Operators MUST be able to create, view, edit, deactivate, and preview templates; template codes MUST be unique; templates referenced by history MUST be deactivated rather than physically deleted.
- **FR-022**: The Email template editor MUST support rich-text (HTML) formatting and MUST visually distinguish placeholders of the form `${name}` from normal text; SMS/Push templates MUST use plain text with a visible character count.
- **FR-023**: Operators MUST be able to view and update provider connection settings per channel (email server settings, push service credentials, SMS gateway endpoint and secret), with secrets masked on display, changes effective without restart, a "test connection" action, and an audit record of each change that excludes secret values.
- **FR-024**: Operators MUST be able to search notification history by client request identifier, user identifier, recipient address, channel, status, client, and date range, with paginated results, and open a detail view with the rendered content and lifecycle timeline including provider error details.
- **FR-025**: Operators MUST be able to view a dashboard showing notification volume over a selectable time range at hourly or daily granularity (per channel), and success/failure rates per channel as defined in User Story 9.
- **FR-026**: The operator console MUST require operator sign-in and MUST support three roles, enforced at screen/action level in v1:
  - **Admin**: full access, including channel provider configuration (secrets), templates, dashboard, Delivery Audit and the Config Change Log (FR-034).
  - **Template Editor**: create, edit, preview and deactivate templates; view dashboard; no access to channel configuration.
  - **Support Viewer**: read-only dashboard and Delivery Audit; no template edits, no channel configuration.
  Actions outside a user's role MUST be hidden in the console and refused by the system. Role assignment in v1 is managed by administrators outside the console (e.g., configuration/seed data); fine-grained permissions and in-console user/role management are deferred to a later phase.
**Stuck notifications & incident alerting**

- **FR-039**: A notification MUST be recorded as "send in progress" (SENDING) before the provider is called; after an unexpected restart, notifications already in SENDING MUST NOT be sent to the provider again.
- **FR-040**: A periodic check MUST mark as FAILED (error `DELIVERY_STATE_UNKNOWN`) every notification that has remained in SENDING longer than the stuck timeout (default 5 minutes, configurable, always longer than the maximum retry duration), and as FAILED (error `PROCESSING_TIMEOUT`) every notification still in RECEIVED or PROCESSED after the processing timeout (default 15 minutes); OTP notifications are failed no later than their expiry. Such notifications are never sent afterwards, and a FAILED result is published.
- **FR-041**: A verified provider callback reporting delivery for a notification FAILED with `DELIVERY_STATE_UNKNOWN` MUST correct it to DELIVERED and publish a new result; no other transition out of FAILED is allowed.
- **FR-042**: Admins MUST be able to manage the list of alert recipient emails (add, deactivate, remove) in the console; the list is a configuration record subject to FR-032..FR-035.
- **FR-043**: The hub MUST evaluate incident rules at least every minute and email all active recipients when a rule fires. Rules (thresholds, windows and cooldowns editable by Admins, with defaults): stuck-timeout failures per channel (≥ 10 in 10 min), provider unavailable (circuit open ≥ 2 min), channel failure rate (≥ 30% over 10 min with ≥ 50 notifications), rejected ingest messages (≥ 20 in 10 min).
- **FR-044**: For each rule and channel, the hub MUST send at most one alert per cooldown period (default 30 minutes) while the condition persists, and one "resolved" email when it clears.
- **FR-045**: Every fired alert MUST be recorded in an alert history (rule, channel, measured value, threshold, time, recipients, send outcome) viewable by Admins. Alert emails MUST bypass user preferences and MUST NOT contain OTP values, secrets or full recipient addresses.

**Stream partitioning**

- **FR-036**: Every message on the ingestion stream and the result stream MUST carry a routing key equal to the user identifier, so load spreads evenly across parallel processing units and all messages concerning the same user (and therefore the same notification) are processed and delivered in order. Channel and client MUST be carried as message metadata for filtering, not used as the routing key, to avoid hot spots from uneven channel volumes.
- **FR-037**: Ingestion messages that arrive without a routing key MUST still be accepted and processed; the hub MUST apply the user-identifier key to every message it publishes itself.
- **FR-038**: Result messages for one notification MUST reach clients in status order (e.g., SENT before DELIVERED when both are emitted; SENT may be absent per FR-016).

**OTP & channel extensibility**

- **FR-028**: Templates MUST carry a category (OTP or STANDARD); OTP notifications MUST be processed and delivered with higher priority than STANDARD notifications.
- **FR-029**: Every OTP notification MUST have an expiry time chosen by the client, given either explicitly (`expires_at`) or — for template-mode OTP — derived from a validity-in-minutes variable that the template designates (e.g., `expired_minutes`), computed from the acceptance time; an explicit expiry takes precedence. Requests where neither is available MUST be rejected. The hub MUST NOT attempt delivery (initial or retry) after the expiry has elapsed and MUST mark such notifications FAILED with an "OTP expired" error code.
- **FR-030**: OTP codes MUST be masked in the operator console, in tracking responses, and in all logs; the unmasked code exists only in the message sent to the provider.
- **FR-031**: The set of channels MUST be extensible: adding a new channel (planned: Zalo) MUST require only a new provider integration, its configuration, and templates for that channel — without changing the request format, deduplication, tracking, result reporting, preferences, or console screens other than adding the channel as an option.

**System time source**

- **FR-046**: All time-dependent behavior (timestamps, OTP expiry, timeouts, retention, dashboard windows, alert windows/cooldowns) MUST use one system time source. In non-production environments, Admins MUST be able to set that source to any date/time (fixed or offset from real time) and reset it, at startup by configuration or at runtime; the change MUST apply to all running instances, be recorded in the configuration audit trail, and be clearly indicated in the operator console. In production the override MUST be disabled.

**Configuration audit trail**

- **FR-032**: Every configuration record — clients, templates, user channel preferences, channel provider configurations, and operator accounts — MUST record who created it, when, who last updated it, when, and a version number that increases by one on every change.
- **FR-033**: Updates to configuration records MUST be checked against the version the editor last saw; if the record changed in the meantime, the update MUST be rejected with a "modified by someone else" error and the latest version shown, so no change is silently overwritten.
- **FR-034**: Every create, update, deactivate or delete of a configuration record MUST append an audit entry (record type and identifier, action, actor, timestamp, resulting version, changed fields with old and new values — secrets recorded only as "changed", never as values); audit entries are append-only and viewable by Admins in the **Config Change Log** screen.
- **FR-035**: The actor recorded MUST be the signed-in operator for console changes, the client system (and user identifier) for preference changes made on a user's behalf, and a system identity for automated changes.

- **FR-027**: Recipient addresses (phone numbers, emails) MUST be partially masked in the console's list views and shown in full only in the detail view.

### Key Entities

- **Client**: A registered system allowed to submit notifications. Attributes: identifier, name, credentials (stored securely), per-minute request allowance, status (active/inactive), whether direct-content mode is allowed, created/updated by and at, version.
- **Template**: A reusable message definition. Attributes: unique code, name, channel, category (OTP/STANDARD), "critical" flag (bypasses user opt-out), subject (Email only), body with named placeholders, status (active/inactive), created/updated by and at, version.
- **User Channel Preference**: Whether a given end user accepts notifications on a given channel. One record per user per channel; absent means enabled. Carries created/updated by and at, version.
- **Notification**: A single message request and its outcome. Attributes: system identifier, owning client, client request identifier (unique per client), user, recipient address, channel, template used, content mode (template/direct), rendered or supplied content, category (OTP/STANDARD), current status, retry count, last error code and message, created/updated times.
- **Notification Status Event**: One entry in a notification's timeline — status, timestamp, attempt number, provider response/error details. Many per notification.
- **Read State**: Whether an end user has read a given Push (inbox) notification and when. One per Push notification, owned by the notification's user.
- **Channel Provider Configuration**: Connection settings for a channel's provider (non-secret parameters plus masked secrets), created/updated by and at, version, and its change history.
- **Delivery Result Message**: The outbound record sent to clients for each outcome — notification identifier, client request identifier, status, error code, timestamp.
- **Operator**: A console user with sign-in identity and exactly one role (Admin, Template Editor, Support Viewer); created/updated by and at, version.
- **Alert Recipient**: An email address that receives incident alerts; active/inactive; configuration record with audit fields.
- **Alert Rule**: An incident type with enabled flag, threshold, window and cooldown; configuration record with audit fields.
- **Alert Event**: History entry of a fired or resolved alert — rule, channel, measured value, threshold, time, recipients, send outcome.
- **Configuration Audit Entry**: Append-only record of a change to any configuration record — record type/id, action, actor, time, resulting version, changed fields (old/new, secrets redacted).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 95% of valid synchronous send requests are acknowledged to the client in under 1 second at expected peak load.
- **SC-002**: Zero duplicate messages are delivered for repeated or concurrent submissions of the same client request identifier (verified by a duplicate-storm test of 10,000 repeated submissions).
- **SC-003**: Zero accepted notifications are lost across an unplanned system restart during peak load (every accepted notification reaches a final status).
- **SC-004**: 99% of notifications reach SENT or FAILED within 30 seconds of acceptance when providers are healthy.
- **SC-005**: The hub sustains at least 500 accepted notifications per second across all channels without exceeding SC-001.
- **SC-006**: When one channel's provider is fully unavailable, delivery success and latency for the other channels remain within normal bounds, and the affected channel resumes automatically within 1 minute of provider recovery.
- **SC-007**: Clients receive a result message for 100% of notifications that reach SENT, DELIVERED, or FAILED, within 5 seconds of the status change.
- **SC-008**: An operator can locate a specific notification and identify the reason it failed in under 1 minute using the Delivery Audit.
- **SC-009**: An operator can create, preview, and activate a new Email template with placeholders in under 5 minutes without technical assistance.
- **SC-010**: Dashboard figures match the underlying notification records exactly for any selected range, and the dashboard loads in under 3 seconds for a 30-day range.
- **SC-012**: 95% of OTP notifications are handed to the provider within 5 seconds of acceptance, even while a STANDARD backlog exists; 0 OTP messages are delivered after their validity period.
- **SC-014**: Under a mixed load where one channel carries 80% of traffic, no single processing partition handles more than twice the average partition load.
- **SC-016**: 100% of notifications in SENDING longer than the stuck timeout are resolved to FAILED within 2 minutes after the timeout, and none of them is sent twice.
- **SC-017**: Admins on the recipient list receive an alert email within 3 minutes of an incident threshold being crossed, with no more than one alert per incident type and channel per cooldown period.
- **SC-015**: 100% of configuration changes can be traced to an actor, time and version; concurrent edits to the same record never silently overwrite each other.
- **SC-013**: Zalo (or any new channel) can be added without changing the client request format or any existing client integration.
- **SC-011**: No provider secret is ever displayed in full in the console or written to logs after it is saved.

## Assumptions

- **Idempotency scope**: Although a minimum 5-minute duplicate window was mentioned, request identifiers are treated as unique per client for the full retention period, because clients also use them to look up status; a repeat at any time returns the original notification.
- **Channel is chosen by the client**: Each request names exactly one channel; the hub does not pick or fall back to an alternative channel. "Routing" means directing the request to that channel's provider after preference checks. Multi-channel fan-out and fallback are out of scope for v1.
- **One template per channel**: A template code belongs to a single channel; sending the same message on multiple channels requires separate templates and requests.
- **Status semantics**: SENT = accepted by the provider; DELIVERED = provider confirmed delivery to the device/inbox (only for providers that report it). Notifications on channels without delivery reports may remain at SENT as their final success state.
- **Client registration** is performed by system administrators outside the operator console in v1 (e.g., provisioning scripts); no client-management screen is in scope.
- **End-user operations** (preferences, mark-read) are invoked by registered client applications on behalf of their authenticated users; the hub trusts the user identity asserted by an authenticated client.
- **Retention**: Notification history and timelines are retained for 12 months, then purged or archived.
- **Content limits**: SMS maximum rendered length defaults to 1,600 characters (multi-part); Push payload limits follow the provider's documented maximum.
- **Template versioning**: Editing a template increments its version and is captured in the configuration audit trail (FR-034) with changed fields; already-rendered notifications keep their content. Restoring/rolling back to an earlier template version from the console is out of scope for v1.
- **Scheduling, campaigns/bulk audience targeting, quiet hours, localization of templates, and per-user rate limiting** are out of scope for v1.
- **Dependencies**: For development and testing, a free SMS gateway and a sandbox email service (e.g., Mailtrap, which captures emails without delivering them to real inboxes) are used, plus a push notification project; production providers are configured later through channel configuration without code changes as long as they fit the supported provider types; client systems can produce to and consume from the hub's ingestion and result streams.
- **OTP ownership (confirmed)**: The client system generates the OTP code, owns its expiry, enforces resend limits and wrong-entry limits, and verifies it. The hub only delivers the code: it renders it, prioritizes it, masks it, refuses to deliver after the client-supplied validity elapses (FR-029), and reports the outcome. OTP generation, verification, resend throttling and attempt counting are out of scope for the hub.
- **Direct-content mode** is allowed for all active clients by default; administrators can disable it per client (e.g., for partner clients) to reduce phishing/abuse risk. Client-supplied HTML in direct-content Email is sanitized against a safe allowlist before sending (FR-007); beyond that, the client is responsible for its content.
- **Hot users**: Partitioning by user can still concentrate load if one user identifier receives disproportionate volume (e.g., a shared system account); this is considered rare and acceptable for v1.
- **OTP channel fallback** (e.g., SMS fails → automatically send via Email) is out of scope for v1; the client may submit a new request on another channel.
- **Zalo** (e.g., Zalo ZNS / Official Account messaging) is pending; v1 delivers SMS, Email, Push only. The channel integration point (FR-031) is defined in v1 — including per-channel capabilities such as whether direct content is allowed and whether provider-side pre-approved templates are required — so Zalo is added as one more provider adapter plus configuration.
- **Time override safety**: Setting the system time to any date must not stop the hub from accepting notifications (storage for that period is prepared before the change takes effect, otherwise the change is refused), and while the time is overridden the automatic deletion of old data is suspended by default so that moving the date forward cannot erase existing data.
- **Time override scope**: Overriding the system time affects the hub's own logic and stored timestamps only; external systems (message broker record times, cache expiry in Redis, provider timestamps, circuit-breaker windows) keep real time.
- **Stuck-notification trade-off**: Marking a SENDING notification as FAILED after a crash means a message that actually reached the user may be reported FAILED (corrected later only if the provider sends a delivery callback); this is preferred over the risk of sending twice.
- **Implementation constraints** (technology stack, module structure, data model, interface contracts) supplied with the request are recorded in [technical-input.md](./technical-input.md) and are binding inputs for planning.
