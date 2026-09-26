# Elymon Android

Elymon Android est l'application Android du serveur **Elymon**, le modpack Cobblemon de la communauté [Elythera](https://elythera.com). Elle installe le pack, le tient à jour et lance Minecraft: Java Edition 1.21.1 avec NeoForge, directement sur un téléphone ou une tablette.

C'est un fork d'[Amethyst-Android](https://github.com/AngelAuraMC/Amethyst-Android) (AngelAuraMC), lui-même issu de [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher) et de [Boardwalk](https://github.com/zhuowei/Boardwalk). Tout le mérite de faire tourner Java et Minecraft sur Android leur revient.

> **Elymon Android n'est pas un produit officiel Minecraft. Il n'est ni approuvé par Mojang ou Microsoft, ni associé à eux.**
> *NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.*

## Pour les joueurs

### Ce qu'il faut

- Un compte Microsoft qui **possède Minecraft: Java Edition**. Les comptes hors ligne et les démos ne sont pas acceptés.
- Android 10 ou plus récent, sur un processeur 64 bits (arm64).
- **6 Go de RAM au minimum, 8 Go recommandés.** Elymon compte plus de cent mods. Sur un téléphone de 4 Go, l'application refuse de lancer la partie ; sur un téléphone de 6 Go, elle prévient une fois que l'expérience sera dégradée.
- Une puce graphique compatible OpenGL ES 3.2, soit presque tous les téléphones récents.
- Environ **3 Go d'espace libre** pour la première installation, puis 1 Go de marge.
- Une connexion Wi-Fi pour la première installation : environ 520 Mo depuis Elythera, 350 Mo depuis les serveurs de Mojang et 30 Mo pour Java. L'application demande confirmation avant un gros téléchargement, et prévient quand la connexion est limitée (données mobiles).

### Installer

1. Télécharge l'APK d'Elymon depuis le site d'Elythera ou depuis la page *Releases* de ce dépôt. Ne l'installe jamais depuis un autre site.
2. Ouvre le fichier. Android demande d'autoriser l'installation depuis ton navigateur ou ton gestionnaire de fichiers : accepte pour cette fois.
3. Ouvre Elymon, ajoute ton compte Microsoft, puis appuie sur **Jouer**.

La première partie installe le pack, Minecraft et Java : compte quelques minutes. Les parties suivantes ne téléchargent que ce qui a changé.

### Mises à jour

- **Le pack** (mods, configuration) se met à jour tout seul à chaque appui sur **Jouer**.
- **L'application** vérifie au démarrage s'il existe une nouvelle version et propose de l'installer. Android demande alors d'autoriser Elymon à installer des applications (*Installer des applis inconnues*), une seule fois. Avant l'installation, l'application vérifie la taille, la somme de contrôle et la signature de l'équipe Elythera. Si une version devient obligatoire, la partie est refusée jusqu'à la mise à jour.

### Ce qu'Elymon Android ne fait pas

- Pas d'autre version de Minecraft, pas d'autre modpack, pas d'installateur de mods, pas de navigateur de modpacks.
- Pas de compte hors ligne ni de démo.
- Quelques mods du pack PC sont écartés sur Android parce qu'ils ne peuvent pas y fonctionner, dont Distant Horizons, Iris et les shaders, et Cobblemon Vocalized. Le monde tutoriel est aussi écarté.
- La musique d'origine de Minecraft n'est pas téléchargée (545 Mo) : Elymon a la sienne. Le mode de musique « vanilla » du mod Elymon reste donc muet.
- Pas de Google Play : l'application se télécharge et se met à jour depuis Elythera.
- Pas de publicité, pas de statistiques d'usage.

### Les services contactés

- `cdn.elythera.com` : le pack Elymon, les mises à jour de l'application.
- Microsoft, Xbox Live et les services Minecraft : la connexion à ton compte.
- Les serveurs de Mojang : Minecraft, ses bibliothèques et ses sons.
- GitHub (AngelAuraMC) : Java 21 pour Android, vérifié par sa somme de contrôle.
- Maven Central : quelques bibliothèques remplacées pour Android.
- `mc-heads.net` : la tête de ton personnage, affichée à côté de ton compte.
- Le serveur Elymon, une fois en jeu.

Tes jetons de connexion restent sur l'appareil : ils ne sont ni sauvegardés dans le cloud, ni écrits dans les journaux.

### Un problème ?

Utilise l'envoi des journaux de l'application (« Envoyer les journaux »). Elle prépare une archive avec les journaux du lanceur et du jeu, le dernier rapport de plantage et une fiche sur ton appareil, **sans tes jetons de connexion ni la clé Elythera**, et te laisse choisir où l'envoyer. Joins-la à ton message au support d'Elythera ou à un [ticket](https://github.com/Elythera/ElymonAndroid/issues/new/choose).

Les mondes solo, les captures d'écran et les réglages vivent dans le stockage de l'application : **désinstaller Elymon ou vider son stockage les efface.**

## Pour les contributeurs

Avant toute modification, lis [`docs/elymon/DESIGN.md`](docs/elymon/DESIGN.md). Il contient les règles du fork : Java 8, API 29, textes en français, un commentaire `// ELYMON:` sur chaque fichier d'origine touché, aucun secret dans les journaux. Il décrit aussi l'architecture et la répartition des fichiers.

### Construire

Il faut :
- un JDK 21 complet (avec `javac`) pour Gradle, et un JDK 8 pour `MioLibPatcher` ;
- le SDK Android avec les plateformes `android-37.0` et `android-36.1`, le NDK `27.3.13750724` et CMake `3.22.1` ;
- les sous-modules : `git submodule update --init --recursive`. `androidnsbypass` a une URL SSH ; sans clé SSH, ajoute `git config url."https://github.com/".insteadOf git@github.com:`.

Commandes exactes sur la machine de l'équipe :

```
export JAVA_HOME=$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2 ANDROID_HOME=$HOME/Android/Sdk
flock $HOME/Documents/ELYTHERA/DEV/ElymonAndroid-wt/.gradle.lock \
  ./gradlew :app_pojavlauncher:compileDebugJavaWithJavac --no-daemon \
  "-Porg.gradle.java.installations.paths=$HOME/Android/jdk/jdk8u504-b01,$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2"
```

- Remplace `compileDebugJavaWithJavac` par `assembleDebug` pour un APK complet : `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk`.
- `flock` évite que deux builds parallèles épuisent la mémoire.
- Chaque build réécrit des fichiers suivis sous `app_pojavlauncher/src/main/assets/components/`. Ne les commite jamais : lance `git checkout -- app_pojavlauncher/src/main/assets/components` avant chaque commit.
- Sans clé Elythera (`.elythera-key` ou `ELYTHERA_KEY`), un build de debug avertit seulement, et le handshake avec ElytheraMod n'est pas signé.
- Un APK de debug est signé avec une clé publique : ne le distribue jamais aux joueurs.

### Tester

Deux suites s'exécutent sur la machine, sans Android ni Gradle :

```
bash tools/elymon/test-sync.sh      # moteur de synchronisation (serveur HTTP local, distribution réelle)
bash tools/elymon/test-release.sh   # caviardage des journaux, flux de mise à jour, éligibilité, élagage des assets
```

La CI (`.github/workflows/elymon.yml`) les lance à chaque push et pull request vers `elymon`, puis construit l'APK de debug.

### Publier une version

Voir [`docs/elymon/RELEASE.md`](docs/elymon/RELEASE.md) : numéros de version, tag, CI, clé de signature, publication de l'APK et de `latest.json` sur le CDN, retour arrière.

## Licence et crédits

Elymon Android est distribué sous la **GNU LGPL 3.0**, comme Amethyst : voir [`LICENSE`](LICENSE), qui complète la GNU GPL 3.0 ([`COPYING`](COPYING)). Le code ajouté par Elythera est publié sous la même licence.

**Offre de source** : pour chaque APK publié, le code source complet correspondant (scripts de build et CI compris) est celui du tag `v<version>` de ce dépôt. Les composants tiers et leurs licences sont listés dans [`NOTICE`](NOTICE), avec l'offre écrite pour les composants sous GPL et LGPL.

Merci à :
- [Amethyst-Android](https://github.com/AngelAuraMC/Amethyst-Android) et AngelAuraMC : le lanceur, les moteurs de rendu, Java pour Android ;
- [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher) et [Boardwalk](https://github.com/zhuowei/Boardwalk), à l'origine de tout ;
- [MobileGlues](https://github.com/MobileGL-Dev/MobileGlues), [Mesa](https://mesa3d.org), [ANGLE](https://chromium.googlesource.com/angle/angle), [LWJGL](https://www.lwjgl.org), [OpenAL Soft](https://github.com/kcat/openal-soft), [SDL](https://www.libsdl.org), [bytehook](https://github.com/bytedance/bhook), [TouchController](https://github.com/TouchController/TouchController) et les autres projets listés dans `NOTICE` ;
- l'équipe [Cobblemon](https://cobblemon.com) et tous les auteurs des mods du pack Elymon.

Minecraft est une marque de Mojang AB. Elymon Android n'embarque aucun fichier de Minecraft : le jeu est téléchargé depuis les serveurs de Mojang avec ton propre compte.
