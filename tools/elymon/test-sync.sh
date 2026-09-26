#!/usr/bin/env bash
# Host tests of the Elymon sync engine (com.elythera.elymon.sync): no Android, no Gradle.
#
# Compiles ElymonConfig, the sync sources and their tests with `javac --release 8`
# against libs/gson-2.8.6.jar, serves a fake distribution with
# tools/elymon/sync-test-server.py (python3 http.server + Range, ETag, faults)
# from a temporary directory, and runs com.elythera.elymon.sync.AllSyncTests.
#
# Usage: tools/elymon/test-sync.sh [--live] [--keep]
#   --live  also fetches the live distribution.json once (about 6 MB) and plans it
#   --keep  keeps the temporary directory for inspection
# Environment:
#   JAVA_HOME            a full JDK (javac); defaults to the Gradle-provisioned Temurin 21
#   ELYMON_NOTES         the elymon-android-notes folder (distribution-2026-09-26.json)
#   ELYMON_VERSION_JSON  a local copy of the NeoForge 21.1.249 version JSON
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APP="$REPO/app_pojavlauncher"
GSON="$APP/libs/gson-2.8.6.jar"

LIVE=""
KEEP=0
for arg in "$@"; do
    case "$arg" in
        --live) LIVE="--live" ;;
        --keep) KEEP=1 ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done

JDK="${JAVA_HOME:-}"
if [ -z "$JDK" ] || [ ! -x "$JDK/bin/javac" ]; then
    # /usr/lib/jvm/java-21 on the owner's box is only a JRE.
    JDK="$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2"
fi
if [ ! -x "$JDK/bin/javac" ]; then
    echo "No javac found: set JAVA_HOME to a full JDK." >&2
    exit 2
fi

NOTES="${ELYMON_NOTES:-}"
if [ -z "$NOTES" ]; then
    for candidate in "$REPO/../elymon-android-notes" "$REPO/../../elymon-android-notes"; do
        if [ -f "$candidate/distribution-2026-09-26.json" ]; then
            NOTES="$(cd "$candidate" && pwd)"
            break
        fi
    done
fi
if [ -z "$NOTES" ] || [ ! -f "$NOTES/distribution-2026-09-26.json" ]; then
    echo "distribution-2026-09-26.json not found: set ELYMON_NOTES." >&2
    exit 2
fi
VERSION_JSON="${ELYMON_VERSION_JSON:-$HOME/.elytheralauncher/common/versions/21.1.249/21.1.249.json}"

# The engine must stay free of Android classes: it runs here.
if grep -rn "^import android\." "$APP/src/main/java/com/elythera/elymon/sync" >/dev/null; then
    echo "com.elythera.elymon.sync imports android.*: the engine must stay pure Java." >&2
    exit 1
fi

TMP="$(mktemp -d "${TMPDIR:-/tmp}/elymon-sync-test.XXXXXX")"
SERVER_PID=""
cleanup() {
    if [ -n "$SERVER_PID" ]; then
        kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
    if [ "$KEEP" = 1 ]; then
        echo "kept: $TMP"
    else
        rm -rf "$TMP"
    fi
}
trap cleanup EXIT

echo "Compiling with $JDK/bin/javac --release 8"
mkdir -p "$TMP/classes" "$TMP/www" "$TMP/run"
"$JDK/bin/javac" --release 8 -encoding UTF-8 -Xlint:all,-serial,-options -Werror \
    -cp "$GSON" -d "$TMP/classes" \
    "$APP/src/main/java/com/elythera/elymon/ElymonConfig.java" \
    "$APP"/src/main/java/com/elythera/elymon/sync/*.java \
    "$APP"/src/test/java/com/elythera/elymon/sync/*.java

python3 "$REPO/tools/elymon/sync-test-server.py" "$TMP/www" "$TMP/port" "$TMP/requests.log" &
SERVER_PID=$!
for _ in $(seq 1 100); do
    [ -s "$TMP/port" ] && break
    sleep 0.05
done
if [ ! -s "$TMP/port" ]; then
    echo "the test server did not start" >&2
    exit 1
fi
PORT="$(cat "$TMP/port")"

"$JDK/bin/java" -cp "$TMP/classes:$GSON" com.elythera.elymon.sync.AllSyncTests \
    --server "http://127.0.0.1:$PORT/" --www "$TMP/www" --log "$TMP/requests.log" --tmp "$TMP/run" \
    --policy "$APP/src/main/assets/elymon/android-policy.json" \
    --strings "$APP/src/main/res/values/elymon_sync_strings.xml" \
    --dist "$NOTES/distribution-2026-09-26.json" \
    --version-json "$VERSION_JSON" $LIVE
