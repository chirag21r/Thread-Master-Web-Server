#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$ROOT_DIR/.run_logs"

# Kill tracked PIDs
if compgen -G "$LOG_DIR/*.pid" > /dev/null; then
  for f in "$LOG_DIR"/*.pid; do
    if [[ -f "$f" ]]; then
      pid=$(cat "$f" || true)
      if [[ -n "${pid:-}" ]]; then
        kill "$pid" 2>/dev/null || true
      fi
    fi
  done
fi

# Fallback: kill any lingering java Server processes
pkill -f 'java Server' 2>/dev/null || true

# Ensure ports are freed
for p in 8011 8012 8013; do
  fuser -k "${p}/tcp" 2>/dev/null || true
done

echo "Stopped servers and freed ports."


