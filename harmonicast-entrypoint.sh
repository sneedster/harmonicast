#!/bin/sh
set -eu

mkdir -p "${DATA_DIR:-/app/data}"
chown -R node:node "${DATA_DIR:-/app/data}"

exec su-exec node "$@"
