#!/usr/bin/env bash
# One-time setup of Cabal on the server (Ubuntu or Debian, systemd). Run as root; deploy.sh
# --setup does this for you. Safe to run again: existing secrets and databases are kept.
#
# What it does:
#   1. installs a Java runtime, PostgreSQL, curl and jq if they are missing
#   2. creates a locked-down `cabal` system user and /opt/cabal
#   3. creates a `cabrouter` database owned by a `cabal` role
#   4. writes /etc/cabal/cabal.env (database credentials, API key, port), readable only
#      by root and the cabal group
#   5. installs and enables the systemd unit (deploy.sh starts it once a jar is in place)
#
# What it never does: touch Docker, containers or any other service and its data, open
# firewall ports, or change the cloudflared config.
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "run as root (sudo)" >&2
  exit 1
fi

HERE=$(cd "$(dirname "$0")" && pwd)
ENV_FILE=/etc/cabal/cabal.env

log() { printf '\n==> %s\n' "$*"; }

log "Packages"
missing=()
command -v java >/dev/null || missing+=(java)
command -v curl >/dev/null || missing+=(curl)
command -v jq >/dev/null || missing+=(jq)
dpkg -s postgresql >/dev/null 2>&1 || missing+=(postgresql)
if ((${#missing[@]})); then
  apt-get update -qq
  pkgs=(curl jq postgresql)
  if ! command -v java >/dev/null; then
    # Cabal targets Java 17; any newer LTS runtime runs it.
    if apt-cache show openjdk-21-jre-headless >/dev/null 2>&1; then
      pkgs+=(openjdk-21-jre-headless)
    else
      pkgs+=(default-jre-headless)
    fi
  fi
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "${pkgs[@]}"
fi
java -version 2>&1 | head -1

# Ubuntu's cluster creation picks the first free port, so if something else (for
# example a container) already holds 5432, this cluster lands on 5433. Read it back
# rather than assume.
PGPORT=$(pg_lsclusters --no-header | awk '$4 == "online" {print $3; exit}')
if [[ -z "$PGPORT" ]]; then
  echo "no online PostgreSQL cluster found; check: pg_lsclusters" >&2
  exit 1
fi
echo "PostgreSQL cluster on port $PGPORT"

log "User and directories"
if ! id cabal >/dev/null 2>&1; then
  useradd --system --home-dir /opt/cabal --shell /usr/sbin/nologin cabal
fi
install -d -o root -g cabal -m 0750 /opt/cabal
install -d -o root -g cabal -m 0750 /etc/cabal

log "Secrets"
if [[ -f "$ENV_FILE" ]]; then
  echo "keeping existing $ENV_FILE"
  # shellcheck disable=SC1090
  source "$ENV_FILE"
else
  DB_PASSWORD=$(openssl rand -hex 24)
  CABAL_API_KEY=$(openssl rand -hex 24)
  PORT=8080
  # Take the first free port from 8080 so nothing already running is disturbed.
  while ss -ltnH "( sport = :$PORT )" | grep -q .; do
    PORT=$((PORT + 1))
    if ((PORT > 8099)); then
      echo "no free port between 8080 and 8099" >&2
      exit 1
    fi
  done
  umask 077
  cat > "$ENV_FILE" <<EOF
DB_URL=jdbc:postgresql://127.0.0.1:$PGPORT/cabrouter
DB_USER=cabal
DB_PASSWORD=$DB_PASSWORD
CABAL_API_KEY=$CABAL_API_KEY
PORT=$PORT
EOF
  chown root:cabal "$ENV_FILE"
  chmod 0640 "$ENV_FILE"
  echo "wrote $ENV_FILE (app port $PORT)"
fi

log "Database"
psql_admin() { sudo -u postgres psql -p "$PGPORT" -v ON_ERROR_STOP=1 -qtA "$@"; }
# The password goes in through a psql variable on stdin, so it never appears in ps
# output. It is always set from the env file, so the two can never drift apart.
if [[ -z "$(psql_admin -c "select 1 from pg_roles where rolname = 'cabal'")" ]]; then
  echo "create role cabal with login password :'pw';" | psql_admin -v pw="$DB_PASSWORD"
  echo "created role cabal"
else
  echo "alter role cabal with login password :'pw';" | psql_admin -v pw="$DB_PASSWORD"
fi
if [[ -z "$(psql_admin -c "select 1 from pg_database where datname = 'cabrouter'")" ]]; then
  psql_admin -c "create database cabrouter owner cabal"
  echo "created database cabrouter"
fi

log "systemd unit"
install -o root -g root -m 0644 "$HERE/cabal.service" /etc/systemd/system/cabal.service
systemctl daemon-reload
systemctl enable cabal >/dev/null
echo "cabal.service installed and enabled"

log "Done"
cat <<EOF
App port:  $PORT (listens on 127.0.0.1 only)
API key:   stored in $ENV_FILE as CABAL_API_KEY
           read it with: sudo grep CABAL_API_KEY $ENV_FILE

Cloudflare Tunnel: this tunnel is dashboard-managed, so there is no local config
file to edit. Add the route in the Cloudflare dashboard instead (Networks > Tunnels
> the tunnel > Published application routes > Add: subdomain cabal, domain
dhyeytandel.in, type HTTP, URL 127.0.0.1:$PORT). See deploy/cloudflare-tunnel.md.
EOF
