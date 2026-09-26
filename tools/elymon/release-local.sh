#!/usr/bin/env bash
# Builds a signed Elymon release on the team's PC, the same way the CI release job would:
# signed APK, certificate check, SHA-256, latest.json whose notes are the annotated tag's
# message. The signing key and ELYTHERA_KEY never leave this machine (owner's choice,
# 2026-09-27; docs/elymon/RELEASE.md).
#
# Usage: tools/elymon/release-local.sh v<versionName> [--out <dir>]
#   The tag must exist (annotated), point at HEAD, and equal v<elymon.versionName>.
#
# Inputs, never printed:
#   signing: ELYMON_KEYSTORE_FILE / _PASSWORD / ELYMON_KEY_ALIAS / _PASSWORD in the environment,
#            else $ELYMON_SIGNING_PROPERTIES (default ~/Android/keys/elymon-signing.properties)
#   ELYTHERA_KEY or ELYTHERA_KEY_FILE (read by build.gradle, like the desktop's prepare-key.cjs)
set -euo pipefail

die() { echo "release-local: $*" >&2; exit 1; }

TAG="${1:-}"
[ -n "$TAG" ] || die "usage : $0 v<versionName> [--out <dossier>]"
shift
OUT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="${2:?}"; shift 2 ;;
    *) die "option inconnue : $1" ;;
  esac
done

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
OUT="${OUT:-$ROOT/out/release/$TAG}"

# Certificate every Elymon release must be signed with (docs/elymon/RELEASE.md §3).
EXPECTED_CERT_SHA256="d53e70bdaba2cbdc6392826dbc8db52b1b978d80c9468db6bf443bf014a7e186"

VERSION_NAME="$(sed -n 's/^elymon\.versionName=//p' elymon-version.properties | tr -d '[:space:]')"
VERSION_CODE="$(sed -n 's/^elymon\.versionCode=//p' elymon-version.properties | tr -d '[:space:]')"
MIN_VERSION_CODE="$(sed -n 's/^elymon\.minVersionCode=//p' elymon-version.properties | tr -d '[:space:]')"
[ "$TAG" = "v$VERSION_NAME" ] || die "le tag $TAG ne correspond pas à elymon.versionName=$VERSION_NAME"
case "$VERSION_CODE" in ''|*[!0-9]*) die "elymon.versionCode invalide : '$VERSION_CODE'" ;; esac
case "$MIN_VERSION_CODE" in ''|*[!0-9]*) MIN_VERSION_CODE="" ;; esac

[ "$(git cat-file -t "$TAG" 2>/dev/null)" = "tag" ] || die "$TAG n'est pas un tag annoté (git tag -a)"
[ "$(git rev-parse "$TAG^{commit}")" = "$(git rev-parse HEAD)" ] || die "$TAG ne pointe pas sur HEAD"
# Build side effects under assets/components are expected; anything else is not.
if git status --porcelain | grep -v ' app_pojavlauncher/src/main/assets/components/' | grep -q .; then
  die "l'arbre de travail n'est pas propre"
fi

if [ -z "${ELYMON_KEYSTORE_FILE:-}" ]; then
  PROPS="${ELYMON_SIGNING_PROPERTIES:-$HOME/Android/keys/elymon-signing.properties}"
  [ -r "$PROPS" ] || die "pas de keystore : ni ELYMON_KEYSTORE_FILE ni $PROPS"
  set -a; . "$PROPS"; set +a
fi
[ -n "${ELYTHERA_KEY:-}" ] || [ -n "${ELYTHERA_KEY_FILE:-}" ] || [ -f "$ROOT/.elythera-key" ] \
  || die "clé Elythera introuvable (ELYTHERA_KEY, ELYTHERA_KEY_FILE ou .elythera-key)"

export JAVA_HOME="${JAVA_HOME_21:-$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
JDKS="${ELYMON_JDK_PATHS:-$HOME/Android/jdk/jdk8u504-b01,$JAVA_HOME}"
LOCK="${ELYMON_GRADLE_LOCK:-$HOME/Documents/ELYTHERA/DEV/ElymonAndroid-wt/.gradle.lock}"
mkdir -p "$(dirname "$LOCK")"

# From scratch, like the CI: nothing from an earlier (debug or unsigned) build is reused.
ELYMON_REQUIRE_SIGNING=1 flock "$LOCK" ./gradlew :app_pojavlauncher:clean :app_pojavlauncher:assembleRelease \
  --no-daemon --no-build-cache "-Porg.gradle.java.installations.paths=$JDKS"
git checkout -- app_pojavlauncher/src/main/assets/components

APK_IN=app_pojavlauncher/build/outputs/apk/release/app_pojavlauncher-release.apk
[ -f "$APK_IN" ] || die "APK signé introuvable ($APK_IN)"
APKSIGNER="$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)apksigner"
CERT="$("$APKSIGNER" verify --print-certs "$APK_IN" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)"
[ "$CERT" = "$EXPECTED_CERT_SHA256" ] || die "certificat inattendu ($CERT) : ce n'est pas la clé de release Elymon"

mkdir -p "$OUT"
APK="Elymon-$VERSION_NAME.apk"
cp "$APK_IN" "$OUT/$APK"
(cd "$OUT" && sha256sum "$APK" > "$APK.sha256")
SHA256="$(cut -d' ' -f1 "$OUT/$APK.sha256")"
SIZE="$(stat -c %s "$OUT/$APK")"
git for-each-ref --format='%(contents:subject)%0a%0a%(contents:body)' "refs/tags/$TAG" > "$OUT/notes.txt"

# Same manifest as the CI release job (.github/workflows/elymon.yml).
VERSION_NAME="$VERSION_NAME" VERSION_CODE="$VERSION_CODE" MIN_VERSION_CODE="$MIN_VERSION_CODE" \
SHA256="$SHA256" SIZE="$SIZE" python3 - "$OUT/notes.txt" "$OUT/latest.json" <<'PY'
import json, os, sys
notes = open(sys.argv[1], encoding="utf-8").read().strip()
if not notes:
    notes = "Corrections et améliorations."
manifest = {
    "versionCode": int(os.environ["VERSION_CODE"]),
    "versionName": os.environ["VERSION_NAME"],
    "url": "https://cdn.elythera.com/elylauncher/android/Elymon-%s.apk" % os.environ["VERSION_NAME"],
    "sha256": os.environ["SHA256"],
    "size": int(os.environ["SIZE"]),
    "notes": notes,
}
if os.environ.get("MIN_VERSION_CODE"):
    manifest["minVersionCode"] = int(os.environ["MIN_VERSION_CODE"])
with open(sys.argv[2], "w", encoding="utf-8") as out:
    json.dump(manifest, out, ensure_ascii=False, indent=2)
    out.write("\n")
PY

echo
echo "Release $TAG prête dans $OUT :"
ls -l "$OUT"
echo "Certificat : $CERT"
echo
echo "Publier sur GitHub :"
echo "  git push origin $TAG"
echo "  gh release create $TAG \"$OUT/$APK\" \"$OUT/$APK.sha256\" \"$OUT/latest.json\" --verify-tag --title \"Elymon $VERSION_NAME\" --notes-file \"$OUT/notes.txt\"$(case "$VERSION_NAME" in *-*) echo ' --prerelease';; esac)"
echo "Puis déposer $APK, $APK.sha256 et latest.json (en dernier) dans /elylauncher/android/ du CDN."
