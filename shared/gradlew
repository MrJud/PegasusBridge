#!/usr/bin/env bash
# Gradle wrapper for Unix. The repository shipped only gradlew.bat, so every
# documented `./gradlew` command failed on Linux and macOS with "not found".
#
# Deliberately minimal: it runs the wrapper jar that is already committed under
# gradle/wrapper, which downloads and caches the Gradle distribution named in
# gradle-wrapper.properties on first use.
set -euo pipefail

APP_HOME="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"

if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    JAVACMD="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVACMD="java"
else
    echo "No Java found. Set JAVA_HOME or put java on PATH (JDK 21 is what this builds with)." >&2
    exit 1
fi

exec "$JAVACMD" -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
     org.gradle.wrapper.GradleWrapperMain "$@"
