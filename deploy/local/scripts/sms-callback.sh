#!/usr/bin/env bash
# Posts a signed generic SMS delivery report to the local hub (provider-callbacks.openapi.yaml).
# Usage: sms-callback.sh <notification_id> <DELIVERED|FAILED> [message_id]
# Env: PNH (default http://localhost:8080), PROVIDER_KEY (GENERIC_HTTP_SMS), CALLBACK_SECRET (local seed secret)
set -euo pipefail
NOTIFICATION_ID=${1:?notification_id required}
STATUS=${2:?DELIVERED or FAILED required}
MESSAGE_ID=${3:-$NOTIFICATION_ID}
PNH=${PNH:-http://localhost:8080}
PROVIDER_KEY=${PROVIDER_KEY:-GENERIC_HTTP_SMS}
SECRET=${CALLBACK_SECRET:-local-callback-secret}
TS=$(date +%s)
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
BODY=$(printf '{"message_id":"%s","request_id":"%s","status":"%s","reported_at":"%s"}' "$MESSAGE_ID" "$NOTIFICATION_ID" "$STATUS" "$NOW")
SIG=$(printf '%s.%s' "$TS" "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/^.* //')
curl -sS -o /dev/null -w '%{http_code}\n' -X POST "$PNH/api/v1/callbacks/sms/$PROVIDER_KEY" \
  -H 'Content-Type: application/json' -H "X-PNH-Timestamp: $TS" -H "X-PNH-Signature: sha256=$SIG" --data "$BODY"
