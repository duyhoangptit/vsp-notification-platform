# Zalo ZNS adapter — plan (not implemented in v1)

Research R25: Zalo is added as a **new adapter only** — no schema, contract or core-module change.
`ChannelExtensibilityIT` proves the path with a test-only channel (send → SENT → result, and external-template
channels rejecting direct content).

## What to build

1. `ZaloZnsProvider implements ChannelProvider` in this package (nested Modulith module `provider.zalo`,
   `package-info.java` with `@ApplicationModule`).
   - `channelType()` = `ZALO`, `providerKey()` = `ZALO_ZNS`.
   - `capabilities()`: `RecipientType.ZALO_USER` (phone number in E.164), `requiresExternalTemplate=true`,
     `supportsDirectContent=false`, `supportsDeliveryCallback=true`.
   - `send()`: POST the ZNS "send template message" API with `template_id` from the template's
     `external_template_ref.zns_template_id` and `template_data` built from the request variables.
     The rendered body is kept as the hub-side record of what was sent.
   - Error mapping: 5xx / rate limits → `TransientProviderException`; invalid phone / user not following OA →
     `PermanentProviderException(INVALID_RECIPIENT)`; template not approved → `PROVIDER_REJECTED`.
2. OAuth: the OA access token expires; keep `app_id`, `refresh_token`, `secret_key` as channel-config secrets and
   refresh inside the adapter (cache the access token per `configId:version`, refresh before expiry; R21).
3. `validateSettings()` for `{ oa_id, base_url? }` and the secrets above; `testConnection()` = token refresh.
4. Delivery webhooks: `POST /api/v1/callbacks/zalo/{provider_key}` controller in this package — verify the
   ZNS signature (`mac` = HMAC-SHA256 of the payload with the OA secret), map `user_received_message` → DELIVERED,
   failures → FAILED, then call `TrackingApi.applyDeliveryReport(...)`.
5. Console: add a `ZALO_ZNS` entry to `frontend/src/features/channel-configs/providerForms.ts`; the template editor
   already exposes `external_template_ref` for channels that require it.
6. Tests: extend `ChannelProviderContractTest` (`ZaloZnsProviderContractTest`) with a WireMock ZNS stub.
