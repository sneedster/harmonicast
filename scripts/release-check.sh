#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$repo_dir/android/build-debug.sh" :app:testDebugUnitTest :app:lintDebug
printf '%s\n' 'Standalone Android release checks passed.'
