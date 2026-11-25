#!/usr/bin/env bash
# VinSpeed standard (devops/standards/README.md): rebuild and restart ONLY this service in the local
# stack (devops/compose.local.yaml), the other containers keep running.
#   ./deploy/local/redeploy.sh
# Requires the local stack (./devops/local/up.sh) to have been started once.
set -euo pipefail
SERVICE=notification                     # service name in devops/compose.local.yaml
REPO="$(cd "$(dirname "$0")/../.." && pwd)"     # repo root
MODULE="$REPO/backend"                     # directory holding pom.xml
DEVOPS="$(cd "$REPO/.." && pwd)/devops"         # vinspeed/devops
skip="-DskipTests -DskipITs -Djacoco.skip=true -Dspotless.check.skip=true -q -B"

[[ -f "$DEVOPS/compose.local.yaml" ]] || { echo "devops/compose.local.yaml not found at $DEVOPS"; exit 1; }
cd "$MODULE"
mvn=mvn; [[ -x ./mvnw ]] && mvn=./mvnw
# iCloud-synced folders can leave "X 2.class" duplicates in target/: always build from a clean target
echo "▶ building $SERVICE"; rm -rf target && $mvn $skip package
[[ -f "$DEVOPS/local/.env" ]] && { set -a; . "$DEVOPS/local/.env"; set +a; }
echo "▶ restarting $SERVICE"
docker compose -f "$DEVOPS/compose.local.yaml" up -d --build --no-deps --wait "$SERVICE"
echo "✔ $SERVICE redeployed"
