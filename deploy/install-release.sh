#!/usr/bin/env bash
# Runs on the ThinkPad as root, called by deploy.sh. Swaps in the new jar, restarts the
# service, and rolls back to the previous jar if the new one is not healthy within 90 s.
#
# Usage: install-release.sh [--seed]
#   --seed  if the database has no plans yet, load the demo data through the API
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ENV_FILE=/etc/cabal/cabal.env
SEED=${1:-}

if [[ ! -f "$ENV_FILE" ]]; then
  echo "$ENV_FILE is missing; run deploy.sh --setup first" >&2
  exit 1
fi
# shellcheck disable=SC1090
source "$ENV_FILE"
BASE="http://127.0.0.1:$PORT"

healthy() {
  for _ in $(seq 1 90); do
    if curl -fsS "$BASE/healthz" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

# The unit may have changed in this release.
install -o root -g root -m 0644 "$HERE/cabal.service" /etc/systemd/system/cabal.service
systemctl daemon-reload

if [[ -f /opt/cabal/cabal.jar ]]; then
  cp -p /opt/cabal/cabal.jar /opt/cabal/cabal.jar.prev
fi
install -o root -g cabal -m 0640 "$HERE/cabal.jar" /opt/cabal/cabal.jar

echo "==> restarting cabal"
systemctl restart cabal
if healthy; then
  echo "==> healthy on $BASE"
else
  echo "==> new release is not healthy; last log lines:" >&2
  journalctl -u cabal -n 40 --no-pager >&2 || true
  if [[ -f /opt/cabal/cabal.jar.prev ]]; then
    echo "==> rolling back to the previous jar" >&2
    install -o root -g cabal -m 0640 /opt/cabal/cabal.jar.prev /opt/cabal/cabal.jar
    systemctl restart cabal
    healthy && echo "==> previous release restored" >&2
  fi
  exit 1
fi

if [[ "$SEED" == "--seed" ]]; then
  if [[ "$(curl -fsS "$BASE/api/plans" | jq length)" == "0" ]]; then
    echo "==> seeding demo data"
    API="$BASE/api" API_KEY="$CABAL_API_KEY" bash "$HERE/demo.sh" >/dev/null
    echo "==> seeded: $(curl -fsS "$BASE/api/plans" | jq length) plans"
  else
    echo "==> plans already exist; not seeding"
  fi
fi

echo "==> memory in use: $(systemctl show cabal -p MemoryCurrent --value | awk '{printf "%.0f MB", $1/1048576}') (cap 512 MB)"
