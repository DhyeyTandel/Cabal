#!/usr/bin/env bash
# Build Cabal on this Mac and ship it to the server over SSH.
#
#   deploy/deploy.sh            build, test, upload, restart (rolls back if unhealthy)
#   deploy/deploy.sh --setup    first time: also install Java/PostgreSQL, the cabal user,
#                               database, secrets and systemd unit on the server
#   deploy/deploy.sh --seed     after deploying, load the demo data if there are no plans
#   deploy/deploy.sh --osrm     after deploying, self-host OSRM on the server (Docker, Bengaluru
#                               crop, localhost only), switch Cabal to it and re-plan stored
#                               plans onto roads; takes a few minutes, and refreshes map data
#                               if it is already set up (see deploy/setup-osrm.sh)
#
# Flags combine: deploy/deploy.sh --setup --seed --osrm
# Target host: DEPLOY_HOST (required, e.g. user@your-server; set it in your shell
# profile, not in this repo). You will be asked for the server's sudo password once.
set -euo pipefail

HOST=${DEPLOY_HOST:?set DEPLOY_HOST to user@host of the server, for example in your shell profile}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
REMOTE=/tmp/cabal-release
SETUP=0
SEED=""
OSRM=0
for arg in "$@"; do
  case "$arg" in
    --setup) SETUP=1 ;;
    --seed) SEED="--seed" ;;
    --osrm) OSRM=1 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

cd "$ROOT"
echo "==> building and testing"
JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17} ./mvnw -q -B clean package
JAR=$(ls target/cab-router-*.jar | grep -v -- '-plain' | head -1)
echo "    $(basename "$JAR"), $(du -h "$JAR" | cut -f1)"

echo "==> uploading to $HOST"
ssh "$HOST" "rm -rf $REMOTE && mkdir -p $REMOTE"
scp -q "$JAR" "$HOST:$REMOTE/cabal.jar"
scp -q deploy/cabal.service deploy/setup-server.sh deploy/install-release.sh deploy/setup-osrm.sh scripts/demo.sh "$HOST:$REMOTE/"

if ((SETUP)); then
  ssh -t "$HOST" "sudo bash $REMOTE/setup-server.sh && sudo bash $REMOTE/install-release.sh $SEED"
else
  ssh -t "$HOST" "sudo bash $REMOTE/install-release.sh $SEED"
fi
if ((OSRM)); then
  # After the install, so the new jar (which knows about road geometry) is what gets re-planned.
  ssh -t "$HOST" "sudo bash $REMOTE/setup-osrm.sh"
fi
ssh "$HOST" "rm -rf $REMOTE"
echo "==> done"
