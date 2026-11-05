#!/usr/bin/env bash
# Prints the pnh-signature header value for an ingest message (contracts/kafka/README.md):
#   v1=hex(HMAC-SHA256(signing_key, client_id + "." + client_request_id + "." + hex(SHA-256(value))))
# Usage: kafka-sign.sh <client_id> <signing_key> <message.json>
set -euo pipefail
CLIENT_ID=${1:?client_id required}
KEY=${2:?signing_key required}
FILE=${3:?message.json required}
REQUEST_ID=$(jq -r '.client_request_id' "$FILE")
VALUE_HASH=$(openssl dgst -sha256 -hex < "$FILE" | sed 's/^.* //')
SIG=$(printf '%s.%s.%s' "$CLIENT_ID" "$REQUEST_ID" "$VALUE_HASH" | openssl dgst -sha256 -hmac "$KEY" -hex | sed 's/^.* //')
echo "v1=$SIG"
