#!/usr/bin/env bash
# Runs ON THE VPS (GitHub Actions copies it next to compose.yaml and calls it over SSH).
#   ./deploy.sh <image-tag>
# Pulls the images for <image-tag>, starts them and waits for the healthchecks.
# If the new release does not become healthy, the previous tag is restored.
set -euo pipefail

NEW_TAG="${1:?usage: deploy.sh <image-tag>}"
cd "$(dirname "$0")"

[[ -f .env ]]    || { echo "missing .env (cp env.example .env)"; exit 1; }

# Backend config/secrets: rendered from Vault when its AppRole config exists (see README "Secrets từ Vault"),
# otherwise the hand-written app.env is used as is. A Vault error stops here, before anything changes.
VAULT_ENV="${PNH_VAULT_ENV:-/etc/pnh/vault.env}"
VAULT_DIR="$(dirname "$VAULT_ENV")"
ENV_CHANGED=false
if [[ -d "$VAULT_DIR" && ! -x "$VAULT_DIR" ]] || [[ -e "$VAULT_ENV" && ! -r "$VAULT_ENV" ]]; then
  # set up but unreadable for this user: never fall back silently to a stale app.env
  echo "!! cannot access ${VAULT_ENV} as $(id -un) (owner/permissions?) — deploy aborted, nothing changed"
  exit 1
elif [[ -f "$VAULT_ENV" ]]; then
  echo "==> loading app.env from Vault"
  [[ -f app.env ]] && cp -p app.env app.env.prev   # last applied values, restored if this release is unhealthy
  ./vault-env.sh "$VAULT_ENV" app.env || { echo "!! Vault: deploy aborted, nothing changed"; exit 1; }
  if [[ -f app.env.prev ]] && ! cmp -s app.env app.env.prev; then ENV_CHANGED=true; fi
else
  echo "::warning::${VAULT_ENV} not found — using existing app.env"
fi
[[ -f app.env ]] || { echo "missing app.env (cp app.env.example app.env, or set up Vault)"; exit 1; }

PREV_TAG="$(sed -n 's/^IMAGE_TAG=//p' .env)"

set_tag() {
  if grep -q '^IMAGE_TAG=' .env; then
    sed -i.bak "s/^IMAGE_TAG=.*/IMAGE_TAG=$1/" .env && rm -f .env.bak   # GNU + BSD sed
  else
    echo "IMAGE_TAG=$1" >> .env
  fi
}

up() {
  docker compose pull --quiet backend frontend
  docker compose run --rm kafka-init   # idempotent (--if-not-exists)
  docker compose up -d --remove-orphans --wait --wait-timeout 300
}

echo "==> deploying ${NEW_TAG} (previous: ${PREV_TAG:-none})"
set_tag "$NEW_TAG"

if up; then
  echo "${PREV_TAG}" > .previous-tag
  docker image prune -f >/dev/null
  echo "==> ${NEW_TAG} is healthy"
  docker compose ps
  exit 0
fi

echo "!! ${NEW_TAG} failed to become healthy — backend logs:"
docker compose logs --tail 200 backend || true

if $ENV_CHANGED; then
  # Vault still holds the new values: fix them there, then deploy again
  echo "==> restoring the previous app.env (new values from Vault left the release unhealthy)"
  cp -p app.env.prev app.env
fi
if [[ -n "${PREV_TAG}" ]] && { [[ "${PREV_TAG}" != "${NEW_TAG}" ]] || $ENV_CHANGED; }; then
  echo "==> rolling back to ${PREV_TAG}"
  set_tag "$PREV_TAG"
  up || echo "!! rollback to ${PREV_TAG} also failed"
fi
exit 1
