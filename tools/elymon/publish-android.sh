#!/usr/bin/env bash
#
# Publie une version d'Elymon Android sur le CDN : l'APK, son .sha256, puis latest.json.
#
# Les fichiers viennent de la release GitHub que la CI crée pour le tag (ou d'un dossier
# local avec --dir). Le script refuse, et pourquoi :
#
#   - un latest.json qui n'annonce pas la version du tag, ou dont la taille ou le SHA-256
#     ne correspondent pas à l'APK : c'est le seul fichier que lisent les applications
#     installées, qui refuseraient ensuite le téléchargement ;
#   - une adresse d'APK qui ne pointe pas là où le script le dépose ;
#   - un APK dont le certificat de signature n'est pas celui attendu (ELYMON_CERT_SHA256),
#     quand apksigner est disponible : Android refuserait la mise à jour chez tous les joueurs.
#
# L'APK part en premier et latest.json en dernier : un latest.json publié avant son APK
# enverrait toutes les applications vers un fichier absent. Rien n'est jamais supprimé
# sur le CDN : les anciens APK restent pour un retour arrière (docs/elymon/RELEASE.md).
#
set -euo pipefail

CDN_TARGET="${ELY_CDN_ANDROID_TARGET:-deploy@cdn.elythera.com:/var/www/cdn/elylauncher/android}"
CDN_URL="https://cdn.elythera.com/elylauncher/android"
REPO="${ELYMON_GITHUB_REPO:-Elythera/ElymonAndroid}"
tag=""
dir=""
dry_run=0
assume_yes=0

usage(){
    cat <<EOF
Publie une version d'Elymon Android (APK + latest.json) sur le CDN.

  tools/elymon/publish-android.sh --tag v1.0.0 [options]

Options :
  --tag <tag>       Tag de la release GitHub (v<versionName>). Obligatoire.
  --dir <dossier>   Prendre les fichiers dans ce dossier au lieu de les télécharger
                    depuis la release GitHub (gh release download).
  --target <cible>  Destination rsync. Défaut : \$ELY_CDN_ANDROID_TARGET, sinon
                    $CDN_TARGET
  --dry-run         Tout vérifier et montrer le téléversement, sans écrire.
  -y, --yes         Ne pas demander confirmation.
  -h, --help        Cette aide.

Environnement :
  ELYMON_CERT_SHA256  Empreinte SHA-256 attendue du certificat de signature (64 hex,
                      deux-points acceptés). Vérifiée avec apksigner s'il est trouvé.
  ELYMON_GITHUB_REPO  Dépôt GitHub des releases. Défaut : $REPO
EOF
}

die(){ echo >&2; echo "publish-android : $*" >&2; exit 1; }
step(){ echo; echo "=== $* ==="; }

while [ $# -gt 0 ]; do
    case "$1" in
        --tag) tag="${2:-}"; shift 2 ;;
        --dir) dir="${2:-}"; shift 2 ;;
        --target) CDN_TARGET="${2:-}"; shift 2 ;;
        --dry-run) dry_run=1; shift ;;
        -y|--yes) assume_yes=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Option inconnue : $1" >&2; usage >&2; exit 2 ;;
    esac
done

[ -n "$tag" ] || { usage >&2; die "--tag est obligatoire."; }
case "$tag" in v*) ;; *) die "le tag doit commencer par v (reçu : $tag)." ;; esac
version="${tag#v}"
apk="Elymon-$version.apk"

step "1/4 Fichiers de la release $tag"
if [ -z "$dir" ]; then
    command -v gh >/dev/null 2>&1 || die "gh est introuvable : installez-le ou passez --dir."
    dir="$(mktemp -d "${TMPDIR:-/tmp}/elymon-publish.XXXXXX")"
    trap 'rm -rf "$dir"' EXIT
    gh release download "$tag" --repo "$REPO" --dir "$dir" \
        --pattern "$apk" --pattern "$apk.sha256" --pattern latest.json \
        || die "téléchargement de la release $tag impossible."
fi
for f in "$apk" "$apk.sha256" latest.json; do
    [ -f "$dir/$f" ] || die "fichier absent : $dir/$f"
done
echo "Dossier : $dir"

step "2/4 Cohérence de latest.json"
(cd "$dir" && sha256sum -c "$apk.sha256") || die "le SHA-256 de $apk ne correspond pas à $apk.sha256."
python3 - "$dir/latest.json" "$dir/$apk" "$version" "$CDN_URL/$apk" <<'PY' || die "latest.json est incohérent : rien n'a été téléversé."
import hashlib, json, os, sys
manifest_path, apk_path, version, url = sys.argv[1:]
m = json.load(open(manifest_path, encoding="utf-8"))
errors = []
def need(cond, msg):
    if not cond: errors.append(msg)
need(isinstance(m.get("versionCode"), int) and m["versionCode"] > 0, "versionCode doit être un entier > 0")
need(m.get("versionName") == version, "versionName (%r) ne vaut pas %r" % (m.get("versionName"), version))
need(m.get("url") == url, "url (%r) ne vaut pas %r" % (m.get("url"), url))
digest = hashlib.sha256(open(apk_path, "rb").read()).hexdigest()
need(isinstance(m.get("sha256"), str) and m["sha256"].lower() == digest, "sha256 ne correspond pas à l'APK")
need(m.get("size") == os.path.getsize(apk_path), "size ne correspond pas à l'APK")
if "minVersionCode" in m:
    need(isinstance(m["minVersionCode"], int) and 0 <= m["minVersionCode"] <= m.get("versionCode", 0),
         "minVersionCode doit être entre 0 et versionCode")
need(isinstance(m.get("notes", ""), str), "notes doit être un texte")
for e in errors:
    print("  - " + e, file=sys.stderr)
if errors:
    sys.exit(1)
print("versionCode %d, versionName %s, %d octets, minVersionCode %s" % (
    m["versionCode"], m["versionName"], m["size"], m.get("minVersionCode", "absent")))
PY

step "3/4 Signature"
apksigner="$(command -v apksigner 2>/dev/null || true)"
if [ -z "$apksigner" ] && [ -n "${ANDROID_HOME:-}" ]; then
    latest_bt="$(ls -d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)"
    [ -n "$latest_bt" ] && [ -x "${latest_bt}apksigner" ] && apksigner="${latest_bt}apksigner"
fi
if [ -n "$apksigner" ]; then
    certs="$("$apksigner" verify --print-certs "$dir/$apk")" || die "apksigner refuse la signature de $apk."
    echo "$certs" | grep -i "SHA-256" || true
    if [ -n "${ELYMON_CERT_SHA256:-}" ]; then
        want="$(echo "$ELYMON_CERT_SHA256" | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')"
        echo "$certs" | tr '[:upper:]' '[:lower:]' | grep -q "sha-256 digest: $want" \
            || die "le certificat de $apk n'est pas celui attendu (ELYMON_CERT_SHA256)."
        echo "Certificat conforme à ELYMON_CERT_SHA256."
    else
        echo "ATTENTION : ELYMON_CERT_SHA256 non défini, l'empreinte n'est pas comparée."
    fi
else
    echo "ATTENTION : apksigner introuvable, signature non vérifiée ici (la CI l'a fait)."
fi

step "4/4 Téléversement"
command -v rsync >/dev/null 2>&1 || die "rsync est introuvable."
echo "Destination : ${CDN_TARGET%/}/"
echo "  $apk, $apk.sha256, puis latest.json"
if [ "$assume_yes" -eq 0 ] && [ "$dry_run" -eq 0 ]; then
    [ -t 0 ] || die "confirmation impossible (pas de terminal) : relancer avec --yes ou --dry-run."
    printf 'Publier Elymon %s pour tous les joueurs (o/N) ? ' "$version"
    read -r answer
    case "$answer" in o|O|oui|Oui|y|Y|yes) ;; *) die "annulé." ;; esac
fi
rsync_opts=(-lptv --human-readable)
[ "$dry_run" -eq 1 ] && rsync_opts+=(--dry-run)
rsync "${rsync_opts[@]}" "$dir/$apk" "$dir/$apk.sha256" "${CDN_TARGET%/}/" \
    || die "échec du téléversement de l'APK : latest.json n'a pas été touché (état sûr)."
rsync "${rsync_opts[@]}" "$dir/latest.json" "${CDN_TARGET%/}/" \
    || die "échec du téléversement de latest.json : l'APK est en place mais pas annoncé. Relancez."

echo
if [ "$dry_run" -eq 1 ]; then
    echo "Simulation terminée (--dry-run) : rien n'a été écrit."
else
    echo "Elymon $version publié. Vérifiez : curl -s $CDN_URL/latest.json"
    echo "Les applications le verront à leur prochain démarrage (au plus toutes les 6 h) ou par une vérification manuelle depuis les paramètres."
fi
