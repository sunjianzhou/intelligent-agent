#!/usr/bin/env bash
# Intelligent Agent — Java-only unified startup (Python Agent retired 2026-08-08)
#
# Usage:
#   ./start_all.sh           # start all services (native, java mode backend)
#   ./start_all.sh stop      # stop all native services
#   ./start_all.sh docker    # start via docker compose
#   ./start_all.sh client    # start only the Java CLI client (REPL)
#
# Override defaults by creating .env.local in the project root:
#   JAVA_HOME=/path/to/jdk21  # explicit JDK home
#   MVN=/path/to/mvnw         # explicit Maven wrapper path

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
ACTION="${1:-start}"
PID_DIR="$ROOT/.pids"
LOG_DIR="$ROOT/logs"

CYAN='\033[0;36m'; GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; NC='\033[0m'
step() { echo -e "\n${CYAN}>>> $*${NC}"; }
ok()   { echo -e "  ${GREEN}[OK]${NC} $*"; }
err()  { echo -e "  ${RED}[ERR]${NC} $*" >&2; }
info() { echo -e "  ${YELLOW}[ - ]${NC} $*"; }

mkdir -p "$PID_DIR" "$LOG_DIR"
[[ -f "$ROOT/.env.local" ]] && { set -a; source "$ROOT/.env.local"; set +a; }

MVN="${MVN:-$ROOT/backend/web/mvnw}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA="${JAVA:-$(command -v java)}"
[[ -n "$JAVA" ]] || { err "Java 21 not found. Set JAVA_HOME in .env.local."; exit 1; }

wait_port() {
    local port=$1 name=$2 max=${3:-30}
    info "Waiting for $name (port $port)..."
    for ((i=1; i<=max; i++)); do
        if (echo > /dev/tcp/localhost/$port) 2>/dev/null; then
            ok "$name is ready"
            return 0
        fi
        sleep 2
    done
    err "$name did not start on port $port after $((max*2))s"
    return 1
}

start_bg() {
    local name="$1"; shift
    local logfile="$LOG_DIR/$name.log"
    "$@" >> "$logfile" 2>&1 &
    local pid=$!
    echo "$pid" > "$PID_DIR/$name.pid"
    ok "Started $name (pid=$pid, log=logs/$name.log)"
}

ensure_jar() {
    local dir="$1" jar="$2"
    if [[ ! -f "$ROOT/$jar" ]]; then
        info "Building $jar (first run)..."
        ( cd "$ROOT/$dir" && "$MVN" -q package -DskipTests )
    fi
}

stop_all() {
    step "Stopping all services"
    local found=0
    for f in "$PID_DIR"/*.pid; do
        [[ -f "$f" ]] || continue
        found=1
        local pid name
        pid=$(cat "$f")
        name=$(basename "$f" .pid)
        if kill -0 "$pid" 2>/dev/null; then
            kill "$pid" && ok "Stopped $name (pid=$pid)"
        else
            info "$name not running (stale pid=$pid)"
        fi
        rm -f "$f"
    done
    [[ $found -eq 0 ]] && info "No running services found in $PID_DIR"
}

docker_start() {
    step "Starting via Docker Compose"
    if [[ ! -f "$ROOT/.env.docker" ]]; then
        err ".env.docker not found."
        info "Copy .env.docker.example → .env.docker and set JWT_SECRET + ADMIN_PASSWORD."
        exit 1
    fi
    cd "$ROOT"
    docker compose up -d "${@:2}"
    echo ""
    ok "All containers started."
    echo ""
    echo "  Frontend : http://localhost:3000"
    echo "  Backend  : http://localhost:8080"
    echo ""
    echo "  Profiles : --profile local   (+ Ollama)"
    echo "             --profile https   (+ Nginx TLS)"
    echo "             --profile tunnel  (+ Cloudflare Tunnel)"
    echo "  Stop     : docker compose down"
}

native_start() {
    echo ""
    echo "============================================="
    echo " Intelligent Agent — Starting All Services"
    echo "============================================="

    command -v npm &>/dev/null || { err "npm not found. Install Node.js."; exit 1; }
    [[ -x "$MVN" ]] || { err "Maven wrapper not found at $MVN"; exit 1; }

    ensure_jar backend/web backend/web/target/web-1.0-SNAPSHOT.jar
    ensure_jar client client/target/client-1.0-SNAPSHOT.jar

    step "[1/3] Backend (port 8080, java mode)"
    start_bg backend bash -c "cd '$ROOT/backend/web' && '$JAVA' -jar target/web-1.0-SNAPSHOT.jar"

    step "[2/3] Frontend (port 5173)"
    start_bg frontend bash -c "cd '$ROOT/frontend' && npm run dev"

    step "Waiting for services to come up"
    wait_port 8080 "Backend"
    wait_port 5173 "Frontend"

    echo ""
    ok "All services running!"
    echo ""
    echo "  Backend  : http://localhost:8080"
    echo "  Frontend : http://localhost:5173"
    echo ""
    echo "  Logs : logs/  |  Stop : ./start_all.sh stop"

    step "[3/3] Client (Java CLI REPL)"
    info "Connecting to http://localhost:8080 ..."
    echo ""
    cd "$ROOT/client"
    exec "$JAVA" -jar target/client-1.0-SNAPSHOT.jar repl --url http://localhost:8080
}

client_only() {
    ensure_jar client client/target/client-1.0-SNAPSHOT.jar
    cd "$ROOT/client"
    exec "$JAVA" -jar target/client-1.0-SNAPSHOT.jar repl --url "${2:-http://localhost:8080}"
}

case "$ACTION" in
    stop)   stop_all ;;
    docker) docker_start "$@" ;;
    client) client_only "$@" ;;
    start|*) native_start ;;
esac
