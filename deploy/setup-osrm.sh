#!/usr/bin/env bash
# Self-host OSRM for Cabal on the server (Ubuntu or Debian, systemd, Docker already installed).
# Run as root; deploy.sh --osrm does this for you. Safe to run again: re-running downloads
# fresh map data, rebuilds the routing graph and replaces the container.
#
# What it does:
#   1. checks Docker is present (it never installs Docker) and installs osmium-tool if missing
#   2. downloads Geofabrik's South India extract, crops it to Bengaluru, deletes the download
#   3. preprocesses the crop with the pinned OSRM image (extract, partition, customize: MLD)
#   4. runs one container, `cabal-osrm`, on 127.0.0.1:5000 only, capped at 768 MB
#   5. waits for a real test route, then sets TRAVEL_MODEL=osrm and OSRM_URL in
#      /etc/cabal/cabal.env and restarts the cabal service
#   6. re-plans every stored plan so its routes follow roads and carry map geometry
#
# What it never does: touch any other container or service, publish a port beyond
# localhost, change the firewall or the cloudflared config, or print the API key.
#
# The preprocessing happens in a staging directory, so the running OSRM keeps answering
# until the new graph is ready; the swap itself takes a few seconds.
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "run as root (sudo)" >&2
  exit 1
fi

WORK=/var/lib/cabal-osrm
ENV_FILE=/etc/cabal/cabal.env
IMAGE=ghcr.io/project-osrm/osrm-backend:v26.10.0-debian
CONTAINER=cabal-osrm
PBF_URL=https://download.geofabrik.de/asia/india/southern-zone-latest.osm.pbf
BBOX=77.30,12.75,77.95,13.35   # west,south,east,north: Bengaluru and its surroundings
OSRM_PORT=5000

log() { printf '\n==> %s\n' "$*"; }
die() { echo "$*" >&2; exit 1; }

log "Checks"
command -v docker >/dev/null || die "docker is not installed; install it first (this script does not do that)"
docker info >/dev/null 2>&1 || die "docker is installed but its daemon is not reachable"
[[ -f "$ENV_FILE" ]] || die "$ENV_FILE is missing; run deploy.sh --setup first"

if ! command -v osmium >/dev/null; then
  log "Installing osmium-tool"
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq osmium-tool
fi
for tool in curl jq; do
  command -v "$tool" >/dev/null || die "$tool is missing; run deploy.sh --setup first"
done

install -d -o root -g root -m 0755 "$WORK"
cd "$WORK"

log "Map data"
# -C - resumes a partial download; curl exits 33 when the file is already complete.
rc=0
curl -fL --retry 3 -C - -o southern-zone-latest.osm.pbf "$PBF_URL" || rc=$?
if ((rc != 0 && rc != 33)); then
  die "download failed (curl exit $rc)"
fi
echo "downloaded $(du -h southern-zone-latest.osm.pbf | cut -f1)"

rm -rf staging
mkdir staging
osmium extract --bbox "$BBOX" --overwrite -o staging/bengaluru.osm.pbf southern-zone-latest.osm.pbf
rm -f southern-zone-latest.osm.pbf
echo "cropped to Bengaluru: $(du -h staging/bengaluru.osm.pbf | cut -f1)"

log "Preprocessing with $IMAGE"
docker pull -q "$IMAGE" >/dev/null
osrm() { docker run --rm -v "$WORK:/data" "$IMAGE" "$@"; }
osrm osrm-extract -p /opt/car.lua /data/staging/bengaluru.osm.pbf
osrm osrm-partition /data/staging/bengaluru.osrm
osrm osrm-customize /data/staging/bengaluru.osrm

log "Swapping in the new graph"
# Only our own container is touched.
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
rm -f "$WORK"/bengaluru.osrm*
mv staging/bengaluru.osrm* "$WORK"/
rm -rf staging

if ss -ltnH "( sport = :$OSRM_PORT )" | grep -q .; then
  die "port $OSRM_PORT is already in use by something else; stop it or pick another port"
fi

docker run -d --name "$CONTAINER" --restart unless-stopped \
  -p 127.0.0.1:$OSRM_PORT:5000 --memory 768m \
  -v "$WORK:/data" "$IMAGE" \
  osrm-routed --algorithm mld --max-table-size 100 /data/bengaluru.osrm >/dev/null

log "Waiting for a test route"
# MG Road to Manyata Tech Park (lng,lat order).
TEST_URL="http://127.0.0.1:$OSRM_PORT/route/v1/driving/77.6089,12.9756;77.6206,13.0475?overview=false"
ok=0
for _ in $(seq 1 60); do
  if curl -fsS "$TEST_URL" 2>/dev/null | jq -e '.code == "Ok"' >/dev/null 2>&1; then
    ok=1
    break
  fi
  sleep 1
done
if ((!ok)); then
  echo "OSRM did not answer a test route within 60 s; container logs:" >&2
  docker logs --tail 40 "$CONTAINER" >&2 || true
  exit 1
fi
echo "OSRM answers: $(curl -fsS "$TEST_URL" | jq -r '.routes[0] | "\(.distance | round) m, \(.duration | round) s"')"

log "Pointing Cabal at it"
# Rewrite in place with cat so the file keeps its owner and mode.
tmp=$(mktemp)
grep -v -e '^TRAVEL_MODEL=' -e '^OSRM_URL=' "$ENV_FILE" > "$tmp" || true
if [[ -s "$tmp" && -n "$(tail -c1 "$tmp")" ]]; then
  echo >> "$tmp"
fi
{
  echo "TRAVEL_MODEL=osrm"
  echo "OSRM_URL=http://127.0.0.1:$OSRM_PORT"
} >> "$tmp"
cat "$tmp" > "$ENV_FILE"
rm -f "$tmp"

# shellcheck disable=SC1090
source "$ENV_FILE"
BASE="http://127.0.0.1:$PORT"
systemctl restart cabal
healthy=0
for _ in $(seq 1 90); do
  if curl -fsS "$BASE/healthz" >/dev/null 2>&1; then
    healthy=1
    break
  fi
  sleep 1
done
if ((!healthy)); then
  echo "cabal is not healthy after the restart; last log lines:" >&2
  journalctl -u cabal -n 40 --no-pager >&2 || true
  exit 1
fi
echo "cabal healthy on $BASE"

log "Re-planning stored plans onto the road network"
# The key goes to curl on stdin as a config line, so it never appears in ps output.
api() { printf 'header = "X-API-Key: %s"\n' "$CABAL_API_KEY" | curl -fsS -K - "$@"; }
count=0
for id in $(curl -fsS "$BASE/api/plans" | jq -r '.[].id'); do
  api -X POST -o /dev/null "$BASE/api/plans/$id/replan"
  count=$((count + 1))
done
echo "re-planned $count plans"

log "Done"
docker stats --no-stream --format 'OSRM container memory: {{.MemUsage}} ({{.MemPerc}})' "$CONTAINER"
