#!/usr/bin/env bash
# Tests for the Vault step of deploy/prod/deploy.sh (no Docker needed: every case stops before compose).
#   bash deploy/prod/tests/deploy.test.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FAILS=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1: $2"; FAILS=$((FAILS + 1)); }

setup() { # fresh deploy dir with deploy.sh + vault-env.sh, .env at tag "old"
  local d="$WORK/$1"; mkdir -p "$d"
  cp "$HERE/../deploy.sh" "$HERE/../vault-env.sh" "$d/"
  echo "IMAGE_TAG=old" > "$d/.env"
  echo "$d"
}

# vault config present but Vault unreachable → abort before touching IMAGE_TAG / app.env
d="$(setup vault_fail)"
echo "OLD=1" > "$d/app.env"
printf 'VAULT_ADDR=http://127.0.0.1:1\nVAULT_ROLE_ID=r\nVAULT_SECRET_ID=s\nVAULT_KV_PATH=kv/pnh/prod\n' > "$d/vault.env"
chmod 600 "$d/vault.env"
if out="$(cd "$d" && PNH_VAULT_ENV="$d/vault.env" ./deploy.sh new 2>&1)"; then
  fail vault_fail "exit 0"
elif ! grep -qx "IMAGE_TAG=old" "$d/.env"; then
  fail vault_fail "IMAGE_TAG changed: $(cat "$d/.env")"
elif [[ "$(cat "$d/app.env")" != "OLD=1" ]]; then
  fail vault_fail "app.env changed"
elif ! grep -q "deploy aborted" <<<"$out"; then
  fail vault_fail "no abort message: $out"
else
  pass vault_fail
fi

# no vault config and no app.env → clear error, IMAGE_TAG untouched
d="$(setup no_app_env)"
if out="$(cd "$d" && PNH_VAULT_ENV="$d/absent.env" ./deploy.sh new 2>&1)"; then
  fail no_app_env "exit 0"
elif ! grep -qx "IMAGE_TAG=old" "$d/.env"; then
  fail no_app_env "IMAGE_TAG changed"
elif ! grep -q "missing app.env" <<<"$out"; then
  fail no_app_env "unexpected output: $out"
else
  pass no_app_env
fi

echo "---"
if [[ "$FAILS" -ne 0 ]]; then echo "$FAILS FAILED"; exit 1; fi
echo "ALL PASS"
