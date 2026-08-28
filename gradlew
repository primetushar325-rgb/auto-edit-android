#!/usr/bin/env bash
set -euo pipefail
GRADLE_VERSION="8.8"
BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
GRADLE_HOME="$BASE_DIR/.gradle-local/gradle-$GRADLE_VERSION"
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  mkdir -p "$BASE_DIR/.gradle-local"
  ZIP="$BASE_DIR/.gradle-local/gradle-$GRADLE_VERSION-bin.zip"
  if [ ! -f "$ZIP" ]; then
    curl -fsSL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP"
  fi
  unzip -q "$ZIP" -d "$BASE_DIR/.gradle-local"
fi
exec "$GRADLE_HOME/bin/gradle" "$@"
