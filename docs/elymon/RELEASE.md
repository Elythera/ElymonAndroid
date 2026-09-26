# Publier Elymon Android

Ce document décrit comment une version d'Elymon Android part chez les joueurs : numéro de version, tag, CI, clé de signature, publication sur le CDN, et retour arrière.

En bref :
1. Monter `elymon.versionCode` et `elymon.versionName` dans `elymon-version.properties`, commiter sur `elymon`.
2. Poser un tag annoté `v<versionName>` dont le message contient les nouveautés, en français, pour les joueurs.
3. La CI construit l'APK signé, calcule son SHA-256, écrit `latest.json` et crée la release GitHub.
4. `tools/elymon/publish-android.sh --tag v<versionName>` dépose l'APK puis `latest.json` sur le CDN.
5. Les applications installées voient la mise à jour au démarrage suivant (au plus toutes les 6 h) ou par une vérification manuelle.

## 1. Numéros de version

Tout est dans `elymon-version.properties`, à la racine :

| Clé | Rôle |
|---|---|
| `elymon.versionCode` | Entier. **Doit augmenter à chaque APK qui sort de l'équipe** : Android refuse d'installer un versionCode inférieur ou égal. Le mod le reçoit aussi dans `-Delythera.launcher.version=<ELP_LEVEL>-android.<versionCode>`. |
| `elymon.versionName` | Ce que voit le joueur, en semver : `1.0.0`, `1.0.1-alpha.2`… Un qualificatif (`-alpha.N`, `-beta.N`) marque la release GitHub comme préversion. La distribution compare `requires.android` à ce nom, qualificatif ignoré. |
| `elymon.minVersionCode` | **Optionnel, absent par défaut.** Recopié dans `latest.json`. Tout build dont le versionCode est inférieur refuse de lancer la partie et propose la mise à jour. À réserver aux correctifs qui ne peuvent pas attendre (faille, serveur qui n'accepte plus les anciens builds). Jamais supérieur à `elymon.versionCode`. |

Deux autres niveaux existent et ne bougent pas avec l'APK :
- `ElymonConfig.ELP_LEVEL` : le niveau du protocole du launcher desktop que l'application implémente, comparé à `requires.launcher` de la distribution.
- `requires.android` dans la distribution d'Elythera : le plancher côté serveur, vérifié après la synchronisation. Pour bloquer un vieux build **sans** publier d'APK, c'est ce levier-là.

## 2. Tag et CI

```
git switch elymon
# modifier elymon-version.properties, commiter
git tag -a v1.0.1 -m "Elymon 1.0.1

- Mise à jour automatique de l'application
- Journaux d'assistance"
git push origin elymon v1.0.1
```

Le **message du tag annoté** devient les notes de la release GitHub et le champ `notes` de `latest.json`, que l'application affiche dans la fenêtre de mise à jour. Il est lu par les joueurs : en français, court, sans jargon.

`.github/workflows/elymon.yml` :
- À chaque push ou pull request vers `elymon` : tests hôte (`tools/elymon/test-sync.sh` sur l'instantané `tools/elymon/fixtures/`, puis `tools/elymon/test-release.sh`), puis `assembleDebug` publié en artefact avec son `.sha256`. Aucun secret n'est utilisé.
- À chaque tag `v*`, le job `release` :
  1. échoue tout de suite si un secret manque (il nomme lesquels, jamais leur valeur) ou si le tag ne vaut pas `v<elymon.versionName>` ;
  2. construit `assembleRelease` avec `ELYMON_REQUIRE_SIGNING=1` (sans keystore utilisable, `build.gradle` refuse de produire un APK non signé) et sans cache Gradle ;
  3. affiche le certificat de signature (`apksigner verify --print-certs`) ;
  4. calcule le SHA-256, écrit `latest.json`, et crée la release GitHub avec `Elymon-<versionName>.apk`, `Elymon-<versionName>.apk.sha256` et `latest.json` ;
  5. efface le keystore décodé, même en cas d'échec.

### Secrets GitHub Actions

À créer dans *Settings → Secrets and variables → Actions* du dépôt :

| Secret | Contenu |
|---|---|
| `ELYTHERA_KEY` | La clé partagée du handshake ElytheraMod, 64 caractères hexadécimaux (la même que le launcher desktop, `tools/prepare-key.cjs`). |
| `ELYMON_KEYSTORE_BASE64` | Le keystore de release encodé : `base64 -w0 elymon-release.jks`. |
| `ELYMON_KEYSTORE_PASSWORD` | Mot de passe du keystore. |
| `ELYMON_KEY_ALIAS` | Alias de la clé dans le keystore. |
| `ELYMON_KEY_PASSWORD` | Mot de passe de la clé (souvent le même que le keystore). |

GitHub masque ces valeurs dans les journaux. Le build n'affiche de la clé Elythera que deux empreintes tronquées (SHA-256 et HMAC), comme le desktop. Les pull requests venant d'un fork n'ont accès à aucun secret : elles ne construisent que le debug.

### Build de release en local

Possible, mais la CI reste la référence. Mettre dans `elymon-signing.properties`, ignoré par git, à la racine :

```
ELYMON_KEYSTORE_FILE=/chemin/hors/du/depot/elymon-release.jks
ELYMON_KEYSTORE_PASSWORD=…
ELYMON_KEY_ALIAS=elymon
ELYMON_KEY_PASSWORD=…
```

et la clé Elythera dans `.elythera-key`, également ignoré par git. Puis :

```
export JAVA_HOME=$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2 ANDROID_HOME=$HOME/Android/Sdk
ELYMON_REQUIRE_SIGNING=1 flock $HOME/Documents/ELYTHERA/DEV/ElymonAndroid-wt/.gradle.lock \
  ./gradlew :app_pojavlauncher:assembleRelease --no-daemon \
  "-Porg.gradle.java.installations.paths=$HOME/Android/jdk/jdk8u504-b01,$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2"
git checkout -- app_pojavlauncher/src/main/assets/components
```

## 3. La clé de signature

**Elle ne peut jamais changer.** Android n'installe une mise à jour que si elle est signée par la même clé que l'application installée. Une clé perdue oblige chaque joueur à désinstaller Elymon, ce qui efface ses données locales (mondes solo, captures, réglages dans `Android/data`). L'enregistrement « développeur vérifié » d'Android (obligatoire partout en 2027) lie aussi le nom de paquet `com.elythera.elymon` à l'empreinte de ce certificat.

Création, une seule fois, sur une machine de confiance :

```
keytool -genkeypair -v -keystore elymon-release.jks -alias elymon \
  -keyalg RSA -keysize 4096 -validity 20000 \
  -dname "CN=Elythera, O=Elythera, C=FR"
keytool -list -v -keystore elymon-release.jks -alias elymon   # empreinte SHA-256
```

Garde :
- deux copies hors ligne du `.jks`, chiffrées, en deux lieux différents ;
- les mots de passe dans le gestionnaire de mots de passe de l'équipe ;
- l'empreinte SHA-256 du certificat, ci-dessous, pour vérifier chaque APK publié.

Empreinte SHA-256 du certificat de release : **à renseigner à la création de la clé.**

Jamais dans le dépôt : `.gitignore` exclut `*.jks`, `*.keystore`, `elymon-signing.properties` et `.elythera-key`. Le keystore `debug.keystore` du dépôt est public (mot de passe `android`) : un APK de debug ne doit jamais être distribué aux joueurs.

## 4. Le flux de mise à jour : latest.json

L'application lit `https://cdn.elythera.com/elylauncher/android/latest.json` (constante `ElymonUpdater.MANIFEST_URL`) :

```json
{
  "versionCode": 2,
  "versionName": "1.0.1",
  "url": "https://cdn.elythera.com/elylauncher/android/Elymon-1.0.1.apk",
  "sha256": "<64 caractères hexadécimaux>",
  "size": 71234567,
  "minVersionCode": 1,
  "notes": "Nouveautés, en français."
}
```

| Champ | Obligatoire | Règle (`UpdateManifest`) |
|---|---|---|
| `versionCode` | oui | Entier ≥ 1. Une mise à jour n'est proposée que s'il dépasse celui de l'application. |
| `versionName` | oui | Texte non vide, 64 caractères au plus. Affiché au joueur. |
| `url` | oui | `https://` uniquement. |
| `sha256` | oui | SHA-256 de l'APK, 64 hexadécimaux. |
| `size` | oui | Taille exacte de l'APK en octets (entre 1 et 1 Gio). |
| `minVersionCode` | non | Entre 0 et `versionCode`. Au-dessus du versionCode installé, la partie est refusée jusqu'à la mise à jour. |
| `notes` | non | Texte affiché dans la fenêtre de mise à jour (4 000 caractères au plus). |

Les champs inconnus sont ignorés : le format peut s'enrichir sans casser les anciennes versions.

Ce que fait l'application :
- **Au démarrage** (`checkOnStartup`) : au plus une fois toutes les 6 h, en silence ; une erreur réseau ou un 404 ne disent rien. Seule une version plus récente ouvre la fenêtre de mise à jour.
- **Vérification manuelle** (`checkNow`) : répond toujours : « Elymon est à jour », « Vérification impossible », ou la fenêtre de mise à jour.
- **Au bouton Jouer** : relit le flux (5 s au plus). Si `minVersionCode` dépasse le versionCode installé, la partie est refusée et la mise à jour proposée. Sans réseau, la dernière copie lue du flux fait foi ; un 404 efface cette copie.
- **Mise à jour** : téléchargement dans le cache de l'application, vérification de la taille et du SHA-256, puis du nom de paquet, du versionCode (plus grand que l'installé et égal à celui annoncé) et du certificat de signature (le même que l'application installée), puis installation par `PackageInstaller`. Android affiche toujours sa propre confirmation. Si « Installer des applis inconnues » n'est pas autorisé pour Elymon, l'application y conduit le joueur. Aucune installation pendant que le jeu tourne.

**Cache CDN** : `latest.json` ne doit pas rester en cache plus de quelques minutes (en-tête `Cache-Control: no-cache` ou TTL court côté nginx) ; l'application envoie `Cache-Control: no-cache`, mais un cache intermédiaire peut l'ignorer. Les APK, eux, ne changent jamais une fois publiés et peuvent être mis en cache longtemps.

### Tester la mise à jour avec des builds de debug

Les builds de debug (`com.elythera.elymon.debug`, signés avec la clé publique de debug) lisent `latest-debug.json` au lieu de `latest.json` : un APK de release ne leur est jamais proposé, et on peut essayer tout le parcours ainsi :
1. Construire et installer un debug avec `elymon.versionCode=N` (sans commiter).
2. Construire un second debug avec `N+1`, calculer `sha256sum` et la taille.
3. Déposer cet APK et un `latest-debug.json` qui l'annonce dans `elylauncher/android/` sur le CDN.
4. Vérifier : fenêtre au démarrage, progression, installation, refus d'un fichier altéré (changer un octet du `sha256`), refus de jouer avec `minVersionCode` = N+1.
5. Supprimer `latest-debug.json` du CDN après l'essai.

## 5. Publication sur le CDN

Comme `tools/release.sh` du launcher desktop, la publication est un geste manuel, après avoir essayé l'APK de la release GitHub sur au moins un téléphone :

```
ELYMON_CERT_SHA256=<empreinte du certificat de release> \
  tools/elymon/publish-android.sh --tag v1.0.1            # télécharge la release GitHub (gh)
tools/elymon/publish-android.sh --tag v1.0.1 --dry-run    # tout vérifier sans écrire
tools/elymon/publish-android.sh --tag v1.0.1 --dir out/   # fichiers déjà sur le disque
```

Le script :
1. vérifie que le `.sha256`, la taille, le `versionName` et l'`url` de `latest.json` correspondent à l'APK et au tag ;
2. vérifie la signature avec `apksigner` et la compare à `ELYMON_CERT_SHA256` quand elle est donnée ;
3. téléverse par rsync vers `deploy@cdn.elythera.com:/var/www/cdn/elylauncher/android/` (ou `$ELY_CDN_ANDROID_TARGET`) **l'APK et son `.sha256` d'abord, `latest.json` en dernier**, pour qu'aucune application ne soit jamais dirigée vers un fichier absent.

Rien n'est supprimé du CDN : chaque `Elymon-<versionName>.apk` reste, pour les retours arrière et pour la page de téléchargement.

## 6. Retour arrière

Android refuse d'installer un versionCode plus petit : on ne « redescend » pas une application installée.

1. **Arrêter la diffusion** : republier le `latest.json` de la version précédente (il est joint à sa release GitHub) :
   `tools/elymon/publish-android.sh --tag v<précédente>`.
   Les joueurs qui n'ont pas encore mis à jour ne verront plus la version fautive ; ceux qui l'ont installée la gardent.
2. **Corriger en avant** : annuler le commit fautif sur `elymon`, monter `versionCode` et le numéro de correctif (`1.0.2`), taguer, publier. Pour sortir tout le monde de la version fautive, mettre `elymon.minVersionCode` à ce nouveau versionCode.
3. **Bloquer sans publier** : `requires.android` dans la distribution d'Elythera refuse la partie aux versions trop anciennes, avec le message de la distribution.
4. **Retirer toute mise à jour** : supprimer `latest.json` du CDN. Les applications voient un 404 : plus de proposition, plus de blocage par `minVersionCode`.

## 7. Liste de contrôle d'une version

- [ ] `versionCode` monté, `versionName` à jour, `minVersionCode` voulu (ou absent).
- [ ] `bash tools/elymon/test-sync.sh` et `bash tools/elymon/test-release.sh` verts.
- [ ] APK de debug essayé sur un vrai téléphone : connexion, Jouer, serveur rejoint, `latestlog.txt` sans jeton (`grep -c eyJ` = 0), envoi des journaux.
- [ ] Tag annoté `v<versionName>` avec des notes en français, poussé.
- [ ] Job `release` vert ; empreinte du certificat conforme.
- [ ] APK de la release GitHub installé par-dessus la version précédente : mise à jour sans perte de données.
- [ ] `tools/elymon/publish-android.sh --tag v<versionName>`.
- [ ] `curl -s https://cdn.elythera.com/elylauncher/android/latest.json` annonce la nouvelle version.
