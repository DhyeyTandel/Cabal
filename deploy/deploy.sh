#!/usr/bin/env bash
# Build Cabal on this Mac and ship it to the ThinkPad over SSH (Tailscale).
#
#   deploy/deploy.sh            build, test, upload, restart (rolls back if unhealthy)
#   deploy/deploy.sh --setup    first time: also install Java/PostgreSQL, the cabal user,
#                               database, secrets and systemd unit on the server
#   deploy/deploy.sh --seed     after deploying, load the demo data if there are no plans
#
# Flags combine: deploy/deploy.sh --setup --seed
# Target host: DEPLOY_HOST (default thinkpad@thinkpad-server). You will be asked for the
# server's sudo password once.
set -euo pipefail

HOST=${DEPLOY_HOST:-thinkpad@thinkpad-server}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
REMOTE=/tmp/cabal-release
SETUP=0
SEED=""
for arg in "$@"; do
  case "$arg" in
    --setup) SETUP=1 ;;
    --seed) SEED="--seed" ;;
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
scp -q deploy/cabal.service deploy/setup-server.sh deploy/install-release.sh scripts/demo.sh "$HOST:$REMOTE/"

if ((SETUP)); then
  ssh -t "$HOST" "sudo bash $REMOTE/setup-server.sh && sudo bash $REMOTE/install-release.sh $SEED"
else
  ssh -t "$HOST" "sudo bash $REMOTE/install-release.sh $SEED"
fi
ssh "$HOST" "rm -rf $REMOTE"
echo "==> done"
