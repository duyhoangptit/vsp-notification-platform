#!/usr/bin/env bash
# Tests for deploy/prod/vault-env.sh against a throwaway Vault dev server (Docker).
#   bash deploy/prod/tests/vault-env.test.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SUT="$HERE/../vault-env.sh"
IMAGE="${VAULT_TEST_IMAGE:-hashicorp/vault:1.20}"
WORK="$(mktemp -d)"
CID=""
FAILS=0

cleanup() {
  if [[ -n "$CID" ]]; then docker rm -f "$CID" >/dev/null 2>&1 || true; fi
  rm -rf "$WORK"
}
trap cleanup EXIT

pass() { echo "PASS $1"; }
fail() { echo "FAIL $1: $2"; FAILS=$((FAILS + 1)); }

# --- Vault dev server -------------------------------------------------------
CID="$(docker run -d --cap-add=IPC_LOCK -e VAULT_DEV_ROOT_TOKEN_ID=root -p 127.0.0.1::8200 "$IMAGE" server -dev)"
ADDR="http://$(docker port "$CID" 8200/tcp | head -1)"
for _ in $(seq 1 30); do
  curl -sf "$ADDR/v1/sys/health" >/dev/null 2>&1 && break
  sleep 1
done

api() { # api METHOD PATH [JSON]
  curl -sf -X "$1" -H "X-Vault-Token: root" ${3:+--data "$3"} "$ADDR/v1/$2"
}

api POST sys/auth/approle '{"type":"approle"}' >/dev/null
api POST sys/mounts/kv1 '{"type":"kv","options":{"version":"1"}}' >/dev/null
api POST sys/mounts/kv2 '{"type":"kv","options":{"version":"2"}}' >/dev/null
POLICY='path "kv1/pnh/*" { capabilities = ["read"] }
path "kv2/data/pnh/*" { capabilities = ["read"] }'
api PUT sys/policies/acl/pnh "$(jq -n --arg p "$POLICY" '{policy:$p}')" >/dev/null
api POST auth/approle/role/pnh '{"token_policies":"pnh","token_ttl":"5m"}' >/dev/null
ROLE_ID="$(api GET auth/approle/role/pnh/role-id | jq -r .data.role_id)"
SECRET_ID="$(api POST auth/approle/role/pnh/secret-id | jq -r .data.secret_id)"

REQ='{"PNH_DB_PASSWORD":"db-pw","PNH_REDIS_PASSWORD":"redis-pw","PNH_MASTER_KEY":"bWFzdGVy"}'
# shellcheck disable=SC2016  # literal $c on purpose: must be written verbatim
TRICKY='a=b $c #d "e"'
with() { jq -cn --argjson r "$REQ" --argjson extra "$1" '$r + $extra'; }  # required keys + extra
QUOTE="it's"
OK="$(with "$(jq -cn --arg t "$TRICKY" --arg q "$QUOTE" '{PNH_X:$t, PNH_S:$q, PNH_E:""}')")"
api POST kv2/data/pnh/ok "{\"data\":$OK}" >/dev/null
api POST kv1/pnh/ok "$OK" >/dev/null
api POST kv2/data/pnh/missing '{"data":{"PNH_DB_PASSWORD":"x","PNH_REDIS_PASSWORD":"y"}}' >/dev/null
api POST kv2/data/pnh/nl "{\"data\":$(with '{"PNH_Y":"a\nb"}')}" >/dev/null
api POST kv2/data/pnh/badkey "{\"data\":$(with '{"pnh-lower":"x"}')}" >/dev/null
api POST kv2/data/pnh/backslash "{\"data\":$(with '{"PNH_T":"x\\\\"}')}" >/dev/null
api POST kv2/data/pnh/bsquote "{\"data\":$(with "$(jq -cn --arg v "a\\'b" '{PNH_T:$v}')")}" >/dev/null

# cfg FILE KV_PATH [ADDR] [SECRET_ID] [MODE]
cfg() {
  printf 'VAULT_ADDR=%s\nVAULT_ROLE_ID=%s\nVAULT_SECRET_ID=%s\nVAULT_KV_PATH=%s\n' \
    "${3:-$ADDR}" "$ROLE_ID" "${4:-$SECRET_ID}" "$2" > "$1"
  chmod "${5:-600}" "$1"
}
mode_of() { stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1"; }
sha() { shasum -a 256 "$1" | cut -d' ' -f1; }
accessors() { curl -sf -X LIST -H "X-Vault-Token: root" "$ADDR/v1/auth/token/accessors" | jq '.data.keys | length'; }

compose_env() { # print PNH_* env a container gets from env file $1 via docker compose
  local d; d="$(mktemp -d "$WORK/compose.XXXX")"
  cp "$1" "$d/app.env"
  printf 'services:\n  t:\n    image: alpine:3.20\n    env_file: [app.env]\n    command: ["sh", "-c", "env | grep ^PNH_"]\n' > "$d/compose.yaml"
  (cd "$d" && docker compose -p "vaultenvtest$$" run --rm -q t 2>/dev/null)
}

# expect_fail NAME KV_PATH STDERR_SUBSTRING [ADDR] [SECRET_ID] [MODE]
expect_fail() {
  local name="$1" out="$WORK/$1.env" c="$WORK/$1.cfg" err="$WORK/$1.err"
  cfg "$c" "$2" "${4:-}" "${5:-}" "${6:-}"
  echo "OLD=1" > "$out"; local before; before="$(sha "$out")"
  if "$SUT" "$c" "$out" 2>"$err"; then fail "$name" "exit 0, expected failure"; return; fi
  [[ "$(sha "$out")" == "$before" ]] || { fail "$name" "output file changed"; return; }
  grep -q -- "$3" "$err" || { fail "$name" "stderr lacks '$3': $(cat "$err")"; return; }
  pass "$name"
}

expect_ok() { # NAME KV_PATH
  local name="$1" out="$WORK/$1.env" c="$WORK/$1.cfg"
  cfg "$c" "$2"
  if ! "$SUT" "$c" "$out" 2>"$WORK/$1.err"; then fail "$name" "exit != 0: $(cat "$WORK/$1.err")"; return; fi
  # what the container actually receives through Compose env_file (default format)
  local got; got="$(compose_env "$out")"
  grep -qxF "PNH_X=$TRICKY" <<<"$got" || { fail "$name" "PNH_X mangled by compose: $(grep PNH_X <<<"$got")"; return; }
  grep -qxF "PNH_S=$QUOTE" <<<"$got" || { fail "$name" "PNH_S mangled by compose: $(grep PNH_S <<<"$got")"; return; }
  grep -qxF "PNH_E=" <<<"$got" || { fail "$name" "PNH_E missing"; return; }
  grep -qxF "PNH_MASTER_KEY=bWFzdGVy" <<<"$got" || { fail "$name" "missing PNH_MASTER_KEY"; return; }
  [[ "$(mode_of "$out")" == 600 ]] || { fail "$name" "mode $(mode_of "$out")"; return; }
  pass "$name"
}

# --- cases --------------------------------------------------------------------
before_acc="$(accessors)"
expect_ok v2_ok kv2/pnh/ok
if [[ "$(accessors)" == "$before_acc" ]]; then pass token_revoked; else fail token_revoked "token accessors $before_acc -> $(accessors)"; fi
expect_ok v1_ok kv1/pnh/ok

expect_fail missing_required kv2/pnh/missing "PNH_MASTER_KEY"
expect_fail newline_value kv2/pnh/nl "newline"
expect_fail bad_key kv2/pnh/badkey "invalid key"
expect_fail trailing_backslash kv2/pnh/backslash "backslash"
expect_fail backslash_quote kv2/pnh/bsquote "backslash"
expect_fail unreachable kv2/pnh/ok "cannot reach" "http://127.0.0.1:1"
expect_fail bad_secret_id kv2/pnh/ok "login failed" "" "00000000-0000-0000-0000-000000000000"
expect_fail perm_644 kv2/pnh/ok "must be mode 600" "" "" 644
expect_fail not_found kv2/pnh/nope "no secret"

api PUT sys/seal >/dev/null
expect_fail sealed kv2/pnh/ok "sealed"

if compgen -G "$WORK/*.tmp*" >/dev/null; then fail no_tmp_left "$(ls "$WORK"/*.tmp*)"; else pass no_tmp_left; fi

echo "---"
if [[ "$FAILS" -ne 0 ]]; then echo "$FAILS FAILED"; exit 1; fi
echo "ALL PASS"
