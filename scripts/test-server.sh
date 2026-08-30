#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# better-sqlite3 is a native module. Run in the same Node 20 Alpine runtime as
# the production image so the host Node version cannot affect test results.
docker run --rm \
  -v "$repo_dir":/src:ro \
  -w /tmp \
  node:20-alpine \
  sh -ceu '
    mkdir /tmp/harmonicast
    tar -C /src --exclude=server/node_modules -cf - server | tar -C /tmp/harmonicast -xf -
    cd /tmp/harmonicast/server
    npm ci
    npm test
  '
