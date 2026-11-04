#!/bin/sh
# First use: download Gradle's OFFICIAL 8.13 wrapper JAR and verify its published SHA-256.
# Afterwards, delegate to the standard Gradle wrapper implementation.
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
EXPECTED=81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f
if [ ! -f "$WRAPPER" ]; then
  echo 'Fetching the official Gradle 8.13 wrapper JAR (first run only)...'
  if ! command -v curl >/dev/null 2>&1; then
    echo 'Please install curl or download gradle-8.13-wrapper.jar from services.gradle.org/distributions/ manually.' >&2
    exit 1
  fi
  TMP="${WRAPPER}.tmp.$$"
  trap 'rm -f "$TMP"' EXIT HUP INT TERM
  curl -fL --retry 2 --connect-timeout 20 'https://services.gradle.org/distributions/gradle-8.13-wrapper.jar' -o "$TMP"
  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL=$(sha256sum "$TMP" | cut -d ' ' -f 1)
  elif command -v shasum >/dev/null 2>&1; then
    ACTUAL=$(shasum -a 256 "$TMP" | cut -d ' ' -f 1)
  else
    echo 'A SHA-256 utility is required to verify the wrapper JAR.' >&2
    exit 1
  fi
  if [ "$ACTUAL" != "$EXPECTED" ]; then
    echo 'Gradle wrapper checksum mismatch. Refusing to run it.' >&2
    exit 1
  fi
  mv "$TMP" "$WRAPPER"
  trap - EXIT HUP INT TERM
fi
if [ -n "${JAVA_HOME:-}" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA=java
fi
exec "$JAVA" -classpath "$WRAPPER" org.gradle.wrapper.GradleWrapperMain "$@"
