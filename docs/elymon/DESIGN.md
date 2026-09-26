# Elymon Android — design

This fork of [Amethyst-Android](https://github.com/AngelAuraMC/Amethyst-Android) (LGPL-3.0) is a launcher dedicated to **Elymon**, the Elythera Cobblemon modpack. It targets Minecraft 1.21.1 with NeoForge 21.1.249.

The app does only four things:
1. **Play** installs or updates the pack from the Elythera distribution, then launches it.
2. **Settings.**
3. **Microsoft accounts**: add, switch and remove.
4. **Touch controls**, with Cobblemon shortcuts.

The app has no version picker, no profile editor, no mod-loader installer and no modpack browser.

Research that backs every decision below lives outside the repo, in `ELYTHERA/DEV/elymon-android-notes/`:
- `critique.md`: the plan and risks. Read it first.
- `ui.md`, `launch.md`, `auth.md`, `contract.md`, `compat.md`, `controls.md`, `build.md`.
- `phase0/RESULTATS.md`: measurements on a real Galaxy S24 Ultra.

## Identity
- `applicationId`: `com.elythera.elymon`. Debug builds add `.debug`.
- App name: « Elymon ».
- The Java `namespace` **stays** `net.kdt.pojavlaunch`. Twenty-four JNI symbols hard-code it.
- New code goes in `com.elythera.elymon.*`.
- ABI: `arm64-v8a` only. `minSdk` is 29 and `targetSdk` stays 34.
- License of Elythera code: LGPL-3.0, like the rest.

## Rules for every change
- **Java 8 language level, and only APIs present on Android API 29.** No `List.of`, `InputStream.readAllBytes`, `String.isBlank`, records or `var`.
- **Keep the upstream diff small, so merges with upstream stay easy.**
  - Every edit to an upstream file carries a `// ELYMON:` comment saying why.
  - Prefer one call into `com.elythera.elymon` over logic inlined in upstream classes.
  - To hide a preference, use `setVisible(false)` in code. Deleting it from XML crashes `requirePreference()`.
- **User-facing text is French.**
  - New strings go in `res/values/elymon_<topic>_strings.xml`, one file per work package. That default folder is French.
  - Upstream strings keep their existing translations.
- **Never log secrets.** That covers Microsoft codes and tokens, the Minecraft access token, `ELYTHERA_KEY` and refresh tokens. Phase 0 found 61 token lines in logcat and `--accessToken` in `latestlog.txt`.
- **The sync engine (`com.elythera.elymon.sync`) is pure Java**, with no `android.*` imports. It must run in host-side tests.
- **Build-time secrets are never committed.**
  - `ELYTHERA_KEY` comes from the environment or a git-ignored `.elythera-key`, exactly like the desktop's `tools/prepare-key.cjs`.
  - The release keystore comes from the environment or a git-ignored `elymon-signing.properties`.
- **Do not commit** the build side effects under `app_pojavlauncher/src/main/assets/components/**`. Before committing, run:
  ```
  git checkout -- app_pojavlauncher/src/main/assets/components
  ```

## Building on the owner's Linux box
```
export JAVA_HOME=$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2 ANDROID_HOME=$HOME/Android/Sdk
flock $HOME/Documents/ELYTHERA/DEV/ElymonAndroid-wt/.gradle.lock \
  ./gradlew :app_pojavlauncher:compileDebugJavaWithJavac --no-daemon \
  "-Porg.gradle.java.installations.paths=$HOME/Android/jdk/jdk8u504-b01,$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2"
```
- Use `assembleDebug` instead for a full APK.
- The lock keeps parallel builds from exhausting the 15 GB of RAM.
- `MioLibPatcher` needs the JDK 8 toolchain listed above.
- `/usr/lib/jvm/java-21` is only a JRE.
- Submodule `androidnsbypass` has an SSH URL. `.git/config` overrides it with HTTPS.

## Facts established on a real device (phase 0)
- `amethyst://X` resolves to `DIR_GAME_HOME/X`, which is `files/X`, **not** `files/.minecraft/X`. The instance is therefore `files/custom_instances/elymon-1.21.1`.
- The CDN's pre-processed NeoForge JSON (`id` `neoforge-21.1.249`) launches unchanged:
  - it goes in `.minecraft/versions/neoforge-21.1.249/neoforge-21.1.249.json`;
  - its libraries, plus the four processed jars, go in `.minecraft/libraries/<maven path>`.
  - **No installer runs on the device.**
- `config/fml.toml` needs `earlyWindowControl = false`. The pack ships `true`.
- Measured with 113 mods (11 excluded):
  - boot in 54 s, Showdown in 5 s;
  - the server accepted the client, and Sodium 0.6.13 ran on MobileGlues at 60 FPS;
  - heap 2.1 of 4 GB;
  - 15 min without a crash.
- Amethyst creates an empty `options.txt` before DefaultOptions runs, so the pack's default options never apply. Elymon seeds `options.txt` itself.

## Architecture

### Play flow
`LauncherActivity.mLaunchGameListener` → `com.elythera.elymon.ElymonLaunch.start(activity, account)`, which runs on a background thread:
1. Refuse if the `:game` process is alive.
2. `ElymonSession.ensureFresh(account)` refreshes the Microsoft session.
3. `ElymonSync.run(options, listener)` downloads, verifies and applies overlays, with progress in `ProgressLayout`.
4. Gate on `SyncResult.meta`:
   - `available`;
   - `requires.launcher` against `ElymonConfig.ELP_LEVEL`;
   - `requires.android` against `versionName`.

   If a gate fails, show a French dialog with the URL.
5. `ElymonProfile.ensure(ctx, versionId)` writes the only profile:
   - key `ElymonConfig.PROFILE_UUID`;
   - `gameDir` `ElymonConfig.GAME_DIR`;
   - the existing renderer, `useANGLE`, `controlFile` and `javaDir` are preserved;
   - saved with `commit()`.
6. `new MinecraftDownloader().start(activity, listedVersionOrNull, versionId, new ContextAwareDoneListener(activity, versionId))` handles vanilla files and Java 21, exactly as upstream does.

### Game arguments
Added in `Tools.launchMinecraft`, just before the main class:
- `-Delythera.launcher=elythera`
- `-Delythera.launcher.version=<ELP_LEVEL>-android.<versionCode>`
- `-Delythera.launcher.profile=elymon-1.21.1`
- `-Dsodium.checks.issue2561=false`, always.

`ELYTHERA_KEY` and `ELYTHERA_UUID` are environment variables, added after `readCustomEnv` and never logged. This is the desktop contract in `processbuilder.js:881-930` and ElytheraMod's `ClientHandshake`.

### Sync engine (`com.elythera.elymon.sync`)
The engine follows the desktop semantics (Helios `DistributionFactory` plus Elythera's `instancefiles.js` and `instancepaths.js`):

| Module | Destination |
|---|---|
| `VersionManifest` | `versions/<json id>/<json id>.json` |
| `ForgeHosted` / `Library` | `libraries/<artifact.path or maven path>` |
| `ForgeMod` | `<instance>/mods/<maven file name>` |
| `File` | `<instance>/<artifact.path>` |

- Files are checked by MD5.
- A path guard drops any profile whose path escapes its root.
- Downloads are resumable `.part` files. A size, mtime and MD5 index avoids re-hashing about 1 GB at every Play.
- Files with the same MD5 are downloaded once.
- Pruning follows a managed manifest. It is skipped when the distribution came from cache or while the game runs, and never touches the protected paths.
- The Android policy lives in `assets/elymon/android-policy.json`:
  - excluded mod ids;
  - excluded file prefixes;
  - overlays: edits applied after download (`fml.toml` and keybindings), whose resulting MD5 is remembered, so tracked files are not re-downloaded at every Play;
  - `options.txt` seed values.

### Accounts
- Microsoft only, through the Elythera Entra app `ElymonConfig.AZURE_CLIENT_ID`:
  - v2 `/consumers` authorize with PKCE, `XboxLive.signin offline_access`;
  - the WebView intercepts the `nativeclient` redirect;
  - Xbox `RpsTicket` is `d=<token>`;
  - accounts without Minecraft Java are refused, never downgraded to a demo.
- Local and demo accounts are not offered.

## Work packages and file ownership
Only the owner of a file edits it during a wave. Shared API stubs (`ElymonConfig`, `sync/*` public types, `auth/ElymonSession`) were committed on `elymon` before wave 1. A package may **add** fields or methods to them, but must not change existing signatures.

**Wave 1**

| Package | Branch | Owns |
|---|---|---|
| core | `wip/core` | See below |
| sync | `wip/sync` | `com/elythera/elymon/sync/**` (implementation); `assets/elymon/android-policy.json`; `tools/elymon/test-sync.sh` and its host tests under `app_pojavlauncher/src/test/java/com/elythera/elymon/sync/` |
| auth | `wip/auth` | `net/kdt/pojavlaunch/authenticator/**`; `fragments/MicrosoftLoginFragment.java`, `SelectAuthFragment.java`, `LocalLoginFragment.java`; `com/kdt/mcgui/mcAccountSpinner.java`; `com/elythera/elymon/auth/**`; `res/values/elymon_auth_strings.xml`; auth layouts |

The **core** package owns:
- `app_pojavlauncher/build.gradle` and `src/main/jni/Application.mk`;
- `AndroidManifest.xml` and `.gitignore`;
- `elymon-version.properties`;
- in `net/kdt/pojavlaunch/`: `LauncherActivity`, `Tools`, `utils/JREUtils`, `prefs/LauncherPreferences`, `fragments/MainMenuFragment`, `NewJREUtil` and `TestStorageActivity`;
- `com/elythera/elymon/{ElymonLaunch,ElymonProfile,…}` (not `sync/` or `auth/`);
- `res/values/elymon_core_strings.xml`.

**Wave 2** (after wave 1 was merged and played on the S24 Ultra: fresh install through the app, sync from the CDN in about 30 s, Elythera sign-in, server joined)

Shared stubs committed before wave 2: `com.elythera.elymon.update.ElymonUpdater` (`checkOnStartup`, `checkNow`) and `com.elythera.elymon.support.ElymonLogs` (`share`). Only the release package implements them; ui only calls them.

| Package | Branch | Owns |
|---|---|---|
| ui | `wip/ui` | See below |
| controls | `wip/controls` | See below |
| release | `wip/release` | See below |

The **ui** package owns:
- launcher layouts: `res/layout*/activity_pojav_launcher.xml`, `fragment_launcher.xml` and any new Elymon layout;
- `LauncherActivity` and `MainMenuFragment`;
- `com/kdt/mcgui/mcVersionSpinner.java` and `fragments/ProfileEditorFragment.java`, which become unreachable;
- `prefs/**`, `prefs/screens/**` and `res/xml/pref_*.xml`;
- branding:
  - `res/mipmap*/`, `res/drawable*/` launcher art, `res/values*/colors.xml`, themes and styles;
  - the upstream strings that name Amethyst in `res/values/strings.xml` and `values-fr/strings.xml`;
- `com/elythera/elymon/ui/**` and `com/elythera/elymon/ElymonMemory.java`;
- the memory-warning part of `Tools.java` (`Tools.java:~408-430`);
- `res/values/elymon_ui_strings.xml`.

The **controls** package owns:
- `assets/default.json` and `assets/elymon/android-policy.json`, keybinding overlays only (`seedOptions` too);
- `customcontrols/**`, `CustomControlsActivity.java`, `tasks/AsyncAssetManager.java`;
- `com/elythera/elymon/controls/**` and `res/values/elymon_controls_strings.xml`.

The **release** package owns:
- `AndroidManifest.xml` and `app_pojavlauncher/build.gradle`;
- `com/elythera/elymon/{update,support}/**`, `com/elythera/elymon/ElymonEligibility.java` and `ElymonLaunch.java`;
- `utils/JREUtils.java`, `tasks/MinecraftDownloader.java` and `NewJREUtil.java`;
- `res/xml/` provider paths other than `pref_*`;
- `.github/**`, `README.md`, `NOTICE*` and `docs/elymon/RELEASE.md`;
- `res/values/elymon_release_strings.xml`.
