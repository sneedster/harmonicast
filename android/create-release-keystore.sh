#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
keystore_file="$script_dir/release.keystore"
properties_file="$script_dir/signing.properties"
keytool_bin="${JAVA_HOME:-/home/mjstrong/.local/share/jdks/temurin-21}/bin/keytool"

if [[ ! -x "$keytool_bin" ]]; then
  echo "keytool was not found. Set JAVA_HOME to a Java 17-21 runtime." >&2
  exit 1
fi
if [[ -e "$keystore_file" || -e "$properties_file" ]]; then
  echo "A release keystore or signing.properties already exists; refusing to overwrite it." >&2
  exit 1
fi

read -r -p "Certificate name [Resonance]: " certificate_name
certificate_name="${certificate_name:-Resonance}"
read -r -s -p "Keystore password: " store_password
echo
read -r -s -p "Repeat keystore password: " store_password_repeat
echo
if [[ -z "$store_password" || "$store_password" != "$store_password_repeat" ]]; then
  echo "Passwords are empty or do not match." >&2
  exit 1
fi

"$keytool_bin" -genkeypair \
  -keystore "$keystore_file" \
  -storetype PKCS12 \
  -alias resonance \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=$certificate_name" \
  -storepass "$store_password" \
  -keypass "$store_password"

umask 077
printf 'storeFile=release.keystore\nstorePassword=%s\nkeyAlias=resonance\nkeyPassword=%s\n' \
  "$store_password" "$store_password" > "$properties_file"
chmod 600 "$keystore_file" "$properties_file"

echo "Created $keystore_file and $properties_file."
echo "Back up both files securely before distributing an APK."
