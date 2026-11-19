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

# --- stubs for the compose path: docker logs its argv; `compose up` fails when app.env holds BAD=1 ----------
# shellcheck disable=SC2016  # the stubs' $2 / $* are meant for the generated scripts, not this shell
stub() { # stub DIR RENDERED_LINE — replaces vault-env.sh (writes the line) and puts a fake docker on PATH
  local d="$1"
  printf '#!/bin/sh\necho "%s" > "$2"\n' "$2" > "$d/vault-env.sh"; chmod +x "$d/vault-env.sh"
  mkdir -p "$d/bin"
  printf '#!/bin/sh\necho "$*" >> "%s/docker.log"\nif [ "$1 $2" = "compose up" ] && grep -q BAD=1 app.env; then exit 1; fi\nexit 0\n' "$d" > "$d/bin/docker"
  chmod +x "$d/bin/docker"
  echo "GOOD=1" > "$d/app.env"; chmod 600 "$d/app.env"
  : > "$d/vault.env"; chmod 600 "$d/vault.env"
}
run_deploy() { (cd "$1" && PATH="$1/bin:$PATH" PNH_VAULT_ENV="$1/vault.env" ./deploy.sh "$2" 2>&1); }

# new values from Vault + healthy → app.env replaced, previous kept as app.env.prev
d="$(setup vault_ok)"; stub "$d" "NEW=1"
if ! out="$(run_deploy "$d" new)"; then fail vault_ok "exit != 0: $out"
elif [[ "$(cat "$d/app.env")" != "NEW=1" ]]; then fail vault_ok "app.env not replaced"
elif [[ "$(cat "$d/app.env.prev" 2>/dev/null)" != "GOOD=1" ]]; then fail vault_ok "no app.env.prev"
elif ! grep -qx "IMAGE_TAG=new" "$d/.env"; then fail vault_ok "IMAGE_TAG not set"
else pass vault_ok; fi

# bad secret, same tag (README "change a secret" flow) → unhealthy → app.env restored and brought up again
d="$(setup bad_secret_same_tag)"; stub "$d" "BAD=1"
if out="$(run_deploy "$d" old)"; then fail bad_secret_same_tag "exit 0"
elif [[ "$(cat "$d/app.env")" != "GOOD=1" ]]; then fail bad_secret_same_tag "app.env not restored: $(cat "$d/app.env")"
elif [[ "$(grep -c '^compose up' "$d/docker.log")" -ne 2 ]]; then fail bad_secret_same_tag "expected 2 'compose up', log: $(cat "$d/docker.log")"
else pass bad_secret_same_tag; fi

# bad secret + new tag → rollback uses the previous tag AND the previous app.env
d="$(setup bad_secret_new_tag)"; stub "$d" "BAD=1"
if out="$(run_deploy "$d" new)"; then fail bad_secret_new_tag "exit 0"
elif [[ "$(cat "$d/app.env")" != "GOOD=1" ]]; then fail bad_secret_new_tag "app.env not restored"
elif ! grep -qx "IMAGE_TAG=old" "$d/.env"; then fail bad_secret_new_tag "tag not rolled back: $(cat "$d/.env")"
else pass bad_secret_new_tag; fi

# Vault config dir exists but is not accessible to the deploy user → loud abort, not a silent fallback
d="$(setup unreadable_dir)"; echo "OLD=1" > "$d/app.env"; mkdir "$d/pnh"; : > "$d/pnh/vault.env"; chmod 000 "$d/pnh"
if out="$(cd "$d" && PNH_VAULT_ENV="$d/pnh/vault.env" ./deploy.sh new 2>&1)"; then fail unreadable_dir "exit 0"
elif ! grep -q "cannot access" <<<"$out"; then fail unreadable_dir "unexpected output: $out"
elif ! grep -qx "IMAGE_TAG=old" "$d/.env"; then fail unreadable_dir "IMAGE_TAG changed"
else pass unreadable_dir; fi
chmod 700 "$d/pnh"

echo "---"
if [[ "$FAILS" -ne 0 ]]; then echo "$FAILS FAILED"; exit 1; fi
echo "ALL PASS"
