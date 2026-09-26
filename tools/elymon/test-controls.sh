#!/usr/bin/env bash
# Host checks of the Elymon touch controls: no Android, no Gradle.
#
# 1. tools/elymon/controls/make_layout.py --check: the shipped layout is what the generator writes;
# 2. tools/elymon/check-controls.py: schema, key codes, positions on every test screen, letter keys
#    in screens, key bindings against the Android overlay, SHA-256 history (also dumps every
#    evaluated position);
# 3. com.elythera.elymon.controls.ControlsHostTests, compiled with `javac --release 8` against
#    libs/gson-2.8.6.jar and libs/exp4j-0.4.9-SNAPSHOT.jar: layout install and upgrade, key-binding
#    migration of options.txt, and every position recomputed with the real exp4j.
#
# Usage: tools/elymon/test-controls.sh [--keep] [--preview]
#   --keep     keeps the temporary directory
#   --preview  also draws the layout on every test screen (PNG, needs Pillow) in the temporary directory
# Environment: JAVA_HOME, a full JDK (javac); defaults to the Gradle-provisioned Temurin 21.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APP="$REPO/app_pojavlauncher"
GSON="$APP/libs/gson-2.8.6.jar"
EXP4J="$APP/libs/exp4j-0.4.9-SNAPSHOT.jar"

KEEP=0
PREVIEW=0
for arg in "$@"; do
    case "$arg" in
        --keep) KEEP=1 ;;
        --preview) PREVIEW=1; KEEP=1 ;;
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

TMP="$(mktemp -d "${TMPDIR:-/tmp}/elymon-controls-test.XXXXXX")"
cleanup() {
    if [ "$KEEP" = 1 ]; then
        echo "kept: $TMP"
    else
        rm -rf "$TMP"
    fi
}
trap cleanup EXIT

echo "== generator"
python3 "$REPO/tools/elymon/controls/make_layout.py" --check

echo "== check-controls.py"
EXTRA=()
if [ "$PREVIEW" = 1 ]; then
    EXTRA=(--preview "$TMP/preview")
fi
python3 "$REPO/tools/elymon/check-controls.py" --dump "$TMP/positions.tsv" "${EXTRA[@]}"

echo "== Java host tests ($JDK/bin/javac --release 8)"
mkdir -p "$TMP/classes" "$TMP/run"
"$JDK/bin/javac" --release 8 -encoding UTF-8 -Xlint:all,-serial,-options -Werror \
    -cp "$GSON:$EXP4J" -d "$TMP/classes" \
    "$APP/src/main/java/com/elythera/elymon/controls/LayoutInstaller.java" \
    "$APP/src/main/java/com/elythera/elymon/controls/KeybindingMigration.java" \
    "$APP"/src/test/java/com/elythera/elymon/controls/*.java

"$JDK/bin/java" -cp "$TMP/classes:$GSON:$EXP4J" com.elythera.elymon.controls.ControlsHostTests \
    --layout "$APP/src/main/assets/default.json" \
    --policy "$APP/src/main/assets/elymon/android-policy.json" \
    --history "$APP/src/main/assets/elymon/controls-keybindings.json" \
    --pack-keybindings "$REPO/tools/elymon/controls/pack-keybindings.txt" \
    --positions "$TMP/positions.tsv" \
    --tmp "$TMP/run"
