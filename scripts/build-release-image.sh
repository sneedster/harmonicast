#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

version="${1#v}"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Usage: $0 <version, for example 1.0.37>" >&2
  exit 2
fi

revision="$(git rev-parse HEAD)"
docker build \
  --build-arg "HARMONICAST_VERSION=$version" \
  --build-arg "VCS_REF=$revision" \
  --tag "mjstrong/harmonicast:$version" \
  --tag "mjstrong/harmonicast:latest" \
  .
