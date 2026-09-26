#!/usr/bin/env bash
# Host tests of the release package (no Android, no Gradle): log redaction and the support
# zip, launch diagnostics, the update feed and APK download, eligibility thresholds and
# the vanilla asset trim.
#
# Compiles the pure-Java classes and com.elythera.elymon.release.ReleaseHostTests with
# `javac --release 8` against libs/gson-2.8.6.jar, then runs them. The update tests start a
# local HTTP server (com.sun.net.httpserver) on 127.0.0.1; nothing leaves the machine.
#
# Usage: tools/elymon/test-release.sh [--keep]
# Environment:
#   JAVA_HOME          a full JDK (javac); defaults to the Gradle-provisioned Temurin 21
#   ELYMON_ASSET_INDEX Minecraft's asset index 17 (1.21.1), to measure the trim on the real
#                      index; defaults to ~/.elytheralauncher/common/assets/indexes/17.json
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APP="$REPO/app_pojavlauncher"
SRC="$APP/src/main/java/com/elythera/elymon"
GSON="$APP/libs/gson-2.8.6.jar"

KEEP=0
for arg in "$@"; do
    case "$arg" in
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

PURE=(
    "$SRC/ElymonAssetTrim.java"
    "$SRC/ElymonEligibilityRules.java"
    "$SRC/support/LogRedactor.java"
    "$SRC/support/LogBundle.java"
    "$SRC/support/LaunchDiagnostics.java"
    "$SRC/update/UpdateManifest.java"
    "$SRC/update/UpdateHttp.java"
)
# These classes run here: they must stay free of Android classes.
for file in "${PURE[@]}"; do
    if grep -n "^import android\.\|^import androidx\.\|^import net\.kdt\." "$file" >/dev/null; then
        echo "$file imports Android or launcher classes: it must stay pure Java." >&2
        exit 1
    fi
done

TMP="$(mktemp -d "${TMPDIR:-/tmp}/elymon-release-test.XXXXXX")"
cleanup() {
    if [ "$KEEP" = 1 ]; then
        echo "kept: $TMP"
    else
        rm -rf "$TMP"
    fi
}
trap cleanup EXIT

echo "Compiling with $JDK/bin/javac --release 8"
mkdir -p "$TMP/classes" "$TMP/run"
"$JDK/bin/javac" --release 8 -encoding UTF-8 -Xlint:all,-serial,-options -Werror \
    -cp "$GSON" -d "$TMP/classes" \
    "${PURE[@]}" \
    "$APP/src/test/java/com/elythera/elymon/release/ReleaseHostTests.java"

ASSET_INDEX="${ELYMON_ASSET_INDEX:-$HOME/.elytheralauncher/common/assets/indexes/17.json}"
"$JDK/bin/java" -cp "$TMP/classes:$GSON" com.elythera.elymon.release.ReleaseHostTests \
    --tmp "$TMP/run" --asset-index "$ASSET_INDEX"
