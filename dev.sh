#!/usr/bin/env zsh
# Load .env then start the backend. Usage: ./dev.sh
set -e
while IFS= read -r line; do
  case "$line" in ''|'#'*) continue ;; esac
  export "$line"
done < .env

lsof -ti :8080 | xargs kill -9 2>/dev/null || true
exec mvn spring-boot:run -DskipTests
