# Kafka Contracts

| Topic | Direction | Key | Headers | Payload schema | Partitions (min) |
|-------|-----------|-----|---------|----------------|------------------|
| `pnh.notification.ingest` | client → hub (STANDARD) | `user_id` | `client_id` (required), `pnh-signature` (required), `channel_type`, `category`, `content-type: application/json` | [ingest.schema.json](./ingest.schema.json) | 12 |
| `pnh.notification.ingest.otp` | client → hub (OTP lane) | `user_id` | same | [ingest.schema.json](./ingest.schema.json) | 6 |
| `pnh.notification.result` | hub → clients | `user_id` | `client_id`, `channel_type`, `status`, `event_id` | [result.schema.json](./result.schema.json) | 12 |
| `pnh.notification.ingest.DLT` | hub internal | original key | original headers + `pnh-error-code`, `pnh-error-message` | original bytes | 3 |

Rules (FR-036..FR-038, research R5):

- **Routing key = `user_id`**. Never key by channel (≤ 4 distinct values → hot partitions).
  Producers SHOULD set it; un-keyed ingest messages are still accepted.
- **Ingest semantics** = identical to `POST /api/v1/notifications/send` (same validation,
  idempotency on `client_id + client_request_id`). Messages are acknowledged only after the intake
  transaction commits (at-least-once consumption; duplicates absorbed by idempotency).
- **Client identity** (fix U2): headers `client_id` and `pnh-signature` are required.
  `pnh-signature = "v1=" + hex(HMAC-SHA256(kafka_signing_key, client_id + "." + client_request_id + "." + hex(SHA-256(value))))`,
  where `kafka_signing_key` is issued per client at provisioning. Unknown/inactive client, missing or invalid
  signature → DLT with `UNAUTHORIZED`. Broker SASL/ACLs (write allowed only to registered client principals) are
  configured in deployment as an additional layer.
- **Invalid ingest message**: if the signature is valid and `client_request_id` is readable, the hub records a
  FAILED notification (fields that failed validation may be null — data-model ¹) and publishes a `FAILED` result with
  the validation `error_code` (key = `user_id` if readable, else `client_id:client_request_id`; `user_id` may be null); otherwise the raw
  message goes to the DLT for operator review.
- **Results** are produced once per outcome (SENT, DELIVERED, FAILED) via the transactional event
  registry and delivered at-least-once (spec FR-016, research R26). Consumers MUST de-duplicate on `event_id` and may use
  `sequence` (monotonic per notification) to ignore out-of-order/stale updates. A notification may produce
  DELIVERED (or FAILED) without a preceding SENT when the provider reports before the send acknowledgement
  is recorded (spec FR-016). All results of one
  notification share a key, so they arrive in order within a partition.
- **Filtering**: each client consumes the shared result topic with its own consumer group and filters on
  the `client_id` header (a per-client topic can be added later without changing the payload).
- **Compatibility**: payloads evolve additively only; consumers must ignore unknown fields.
