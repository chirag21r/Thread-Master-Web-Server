#!/usr/bin/env bash
set -euo pipefail

# Resolve project root and absolute log dir
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

LOG_DIR="$ROOT_DIR/.run_logs"
mkdir -p "$LOG_DIR"

start_server() {
  local dir=$1
  local port=$2
  (
    cd "$dir" && \
    javac Server.java && \
    nohup java Server > "$LOG_DIR/${dir}_$port.log" 2>&1 & \
    echo $! > "$LOG_DIR/${dir}.pid"
  )
  echo "Started $dir on port $port (logs: $LOG_DIR/${dir}_$port.log)"
}

start_server SingleThreaded 8011
start_server Multithreaded 8012
start_server ThreadPool 8013

echo "Servers started. PIDs:"
cat "$LOG_DIR"/*.pid


