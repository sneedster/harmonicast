#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

if [[ "${HARMONICAST_SKIP_RELEASE_CHECKS:-0}" != "1" ]]; then
  "$script_dir/../scripts/release-check.sh"
fi

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
if [[ ! -f "$script_dir/signing.properties" || ! -f "$script_dir/release.keystore" ]]; then
  echo "Release signing is not configured. Run ./android/create-release-keystore.sh first." >&2
  exit 1
fi

version_name="${VERSION_NAME:-1.0.19}"
version_code="${VERSION_CODE:-20}"
if [[ ! "$version_name" =~ ^[0-9A-Za-z._-]+$ || ! "$version_code" =~ ^[1-9][0-9]*$ ]]; then
  echo "VERSION_NAME must use letters, numbers, dots, underscores, or dashes; VERSION_CODE must be a positive integer." >&2
  exit 1
fi

cd "$script_dir"
./gradlew --no-daemon --max-workers=2 :app:assembleRelease \
  -PversionName="$version_name" \
  -PversionCode="$version_code"

output_dir="$script_dir/releases"
output_apk="$output_dir/harmonicast-$version_name.apk"
mkdir -p "$output_dir"
cp "$script_dir/app/build/outputs/apk/release/app-release.apk" "$output_apk"
sha256sum "$output_apk"
echo "Signed release APK: $output_apk"
