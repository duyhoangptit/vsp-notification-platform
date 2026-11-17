#!/usr/bin/env bash
# Runs ON THE VPS (GitHub Actions copies it next to compose.yaml and calls it over SSH).
#   ./deploy.sh <image-tag>
# Pulls the images for <image-tag>, starts them and waits for the healthchecks.
# If the new release does not become healthy, the previous tag is restored.
set -euo pipefail

NEW_TAG="${1:?usage: deploy.sh <image-tag>}"
cd "$(dirname "$0")"

[[ -f .env ]]    || { echo "missing .env (cp env.example .env)"; exit 1; }
[[ -f app.env ]] || { echo "missing app.env (cp app.env.example app.env)"; exit 1; }

PREV_TAG="$(sed -n 's/^IMAGE_TAG=//p' .env)"

set_tag() {
  if grep -q '^IMAGE_TAG=' .env; then
    sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=$1/" .env
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

if [[ -n "${PREV_TAG}" && "${PREV_TAG}" != "${NEW_TAG}" ]]; then
  echo "==> rolling back to ${PREV_TAG}"
  set_tag "$PREV_TAG"
  up || echo "!! rollback to ${PREV_TAG} also failed"
fi
exit 1
