#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

export JAVA_HOME="${JAVA_HOME:-/home/mjstrong/.local/share/jdks/temurin-21}"
export ANDROID_HOME="${ANDROID_HOME:-/home/mjstrong/Android/Sdk}"

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "Java runtime not found at $JAVA_HOME. Set JAVA_HOME to a Java 17-21 runtime." >&2
  exit 1
fi

if [[ ! -d "$ANDROID_HOME/platforms/android-35" ]]; then
  echo "Android SDK platform 35 not found at $ANDROID_HOME. Set ANDROID_HOME accordingly." >&2
  exit 1
fi

cd "$script_dir"
exec "$script_dir/gradlew" --no-daemon --max-workers=2 :app:assembleDebug "$@"
