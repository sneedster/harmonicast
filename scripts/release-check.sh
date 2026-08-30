#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required for the Harmonicast release checks." >&2
  exit 1
fi

echo "==> Server regression suite"
npm test

echo "==> Web type check"
npm run typecheck

echo "==> Web lint"
npm run lint

echo "==> Web production build"
npm run build

echo "==> Docker production image build"
docker build --tag harmonicast:release-check .

echo "==> Docker Compose configuration"
docker compose --env-file .env.example config --quiet

echo "Release checks passed."
