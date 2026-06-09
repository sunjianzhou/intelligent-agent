#!/usr/bin/env bash
# Intelligent Agent — unified startup script (Linux / macOS / WSL)
#
# Usage:
#   ./start_all.sh           # start all services (native)
#   ./start_all.sh stop      # stop all native services
#   ./start_all.sh docker    # start via docker compose
#   ./start_all.sh client    # start only the CLI client
#
# Override defaults by creating .env.local in the project root:
#   PYTHON_EXE=/path/to/python   # explicit Python path
#   MVN=/path/to/mvnw            # explicit Maven wrapper path

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
ACTION="${1:-start}"
PID_DIR="$ROOT/.pids"
LOG_DIR="$ROOT/logs"

# ── Colours ───────────────────────────────────────────────────
CYAN='\033[0;36m'; GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; NC='\033[0m'
step() { echo -e "\n${CYAN}>>> $*${NC}"; }
ok()   { echo -e "  ${GREEN}[OK]${NC} $*"; }
err()  { echo -e "  ${RED}[ERR]${NC} $*" >&2; }
info() { echo -e "  ${YELLOW}[ - ]${NC} $*"; }

mkdir -p "$PID_DIR" "$LOG_DIR"

# ── Load optional local overrides ────────────────────────────
[[ -f "$ROOT/.env.local" ]] && { set -a; source "$ROOT/.env.local"; set +a; }

# ── Find Python (prefers conda python310 env) ─────────────────
find_python() {
    [[ -n "${PYTHON_EXE:-}" && -x "$PYTHON_EXE" ]] && { echo "$PYTHON_EXE"; return; }
    # conda environments (common install locations)
    for base in "$HOME/anaconda3" "$HOME/miniconda3" "$HOME/opt/anaconda3" \
                "/opt/conda" "/usr/local/anaconda3"; do
        local p="$base/envs/python310/bin/python"
        [[ -x "$p" ]] && { echo "$p"; return; }
    done
    # system Python 3.9+
    for cmd in python3.10 python3.11 python3.9 python3 python; do
        local path; path=$(command -v "$cmd" 2>/dev/null) || continue
        local ok; ok=$("$path" -c "import sys; print(sys.version_info >= (3,9))" 2>/dev/null)
        [[ "$ok" == "True" ]] && { echo "$path"; return; }
    done
}

PYTHON="${PYTHON_EXE:-$(find_python)}"
MVN="${MVN:-$ROOT/backend/web/mvnw}"

# ── Wait for a TCP port to open ───────────────────────────────
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

# ── Launch a process in the background, record its PID ────────
start_bg() {
    local name="$1"; shift
    local logfile="$LOG_DIR/$name.log"
    "$@" >> "$logfile" 2>&1 &
    local pid=$!
    echo "$pid" > "$PID_DIR/$name.pid"
    ok "Started $name (pid=$pid, log=logs/$name.log)"
}

# ── Stop all services ─────────────────────────────────────────
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

# ── Docker mode ───────────────────────────────────────────────
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
    echo "  Agent    : http://localhost:8000"
    echo ""
    echo "  Profiles : --profile local   (+ Ollama)"
    echo "             --profile https   (+ Nginx TLS)"
    echo "             --profile tunnel  (+ Cloudflare Tunnel)"
    echo "  Stop     : docker compose down"
}

# ── Native start ──────────────────────────────────────────────
native_start() {
    echo ""
    echo "============================================="
    echo " Intelligent Agent — Starting All Services"
    echo "============================================="

    [[ -n "$PYTHON" ]] || { err "Python 3.9+ not found. Install Anaconda/Miniconda or set PYTHON_EXE."; exit 1; }
    [[ -x "$MVN" ]]    || { err "Maven wrapper not found at $MVN"; exit 1; }
    command -v npm &>/dev/null || { err "npm not found. Install Node.js."; exit 1; }

    step "[1/4] Agent (port 8000)"
    start_bg agent bash -c "cd '$ROOT/agent' && '$PYTHON' -m uvicorn api.fastapi_app:app --host 0.0.0.0 --port 8000 --reload"

    step "[2/4] Backend (port 8080)"
    start_bg backend bash -c "cd '$ROOT/backend/web' && '$MVN' spring-boot:run"

    step "[3/4] Frontend (port 5173)"
    start_bg frontend bash -c "cd '$ROOT/frontend' && npm run dev"

    step "Waiting for services to come up"
    wait_port 8000 "Agent"
    wait_port 8080 "Backend"
    wait_port 5173 "Frontend"

    echo ""
    ok "All services running!"
    echo ""
    echo "  Agent    : http://localhost:8000"
    echo "  Backend  : http://localhost:8080"
    echo "  Frontend : http://localhost:5173"
    echo ""
    echo "  Logs : logs/  |  Stop : ./start_all.sh stop"

    step "[4/4] Client (CLI)"
    info "Connecting to http://localhost:8000 ..."
    echo ""
    cd "$ROOT/client"
    exec "$PYTHON" main.py
}

# ── Client only ───────────────────────────────────────────────
client_only() {
    [[ -n "$PYTHON" ]] || { err "Python 3.9+ not found."; exit 1; }
    cd "$ROOT/client"
    exec "$PYTHON" main.py "${@:2}"
}

# ── Dispatch ──────────────────────────────────────────────────
case "$ACTION" in
    stop)   stop_all ;;
    docker) docker_start "$@" ;;
    client) client_only "$@" ;;
    start|*) native_start ;;
esac
