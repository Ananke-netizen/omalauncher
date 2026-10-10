# Working on Omalauncher

Instructions for coding agents (and a checklist for people). Omalauncher is an
Android home screen with Omarchy's look, plus an optional Arch Linux ARM system
under proot for which it is the Wayland compositor. Read these first, as needed:

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): the compositor, rendering, GPU, and how updates reach existing installs
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md): the dev environment, the emulator, what can be tested where, upstream syncs
- [app/src/main/assets/widgets/README.md](app/src/main/assets/widgets/README.md): the OWL widget language

## Commands

Use `scripts/dev.sh`: it finds the two JDKs the build needs (25 for Gradle, 17
for the `owl` module's toolchain) and the Android SDK, which a bare `./gradlew`
may not.

```sh
scripts/dev.sh doctor                     # what's missing; scripts/dev.sh setup installs it
scripts/dev.sh test                       # what CI runs: :owl:test :app:testDebugUnitTest :owl:owlJar (~30 s)
scripts/dev.sh gradle :app:testDebugUnitTest --tests 'dev.omalauncher.ui.AppSearchTest'
scripts/dev.sh build                      # the debug APK; needs scripts/fetch-proot.sh once
java -jar owl/build/cli/owl.jar check some.owl   # the widget checker, after `test` or :owl:owlJar
```

## Checking a change on a device

Unit tests don't cover the UI. For anything visible, run it:

```sh
scripts/dev.sh emulator start --headless  # Pixel 7, API 36; boots in ~30 s
scripts/dev.sh run                        # installs the debug build and opens the home screen
android layout --flat                     # the screen as JSON: texts, bounds, tap centers (SDK cmdline-tools 23+)
adb shell input tap <x> <y>               # then: adb shell input text 'btop' / keyevent ENTER
scripts/dev.sh shot                       # a PNG in build/screenshots/; look at it
scripts/dev.sh logcat -d                  # the app's log
scripts/dev.sh emulator stop              # when done
```

The emulator is x86_64 and runs the arm64 app through ARM translation. The
launcher, themes, widgets without commands, Android apps and the first-run
flow work there. **Nothing under proot does**: Linux setup past the download,
terminals, Linux windows, pacman, coding agents, the GPU. Those need a real
arm64 phone (`ANDROID_SERIAL=<serial> scripts/dev.sh run`). Say which of the
two you checked a change on, or that you couldn't.

`scripts/dev.sh reset` clears the app's data. It only runs against emulators;
never clear data or uninstall on someone's phone without asking.

## Invariants

Each of these protects installs that already exist. Breaking one breaks users'
phones after an update, not the build.

- **Rootfs steps** (`linux/Bootstrap.kt`): a step's id is recorded once it
  succeeds. To change what a step does, give it a new id (`packages-1` →
  `packages-2`); never reuse one. Steps must be idempotent.
- **Provided files** (`upgrade/ManagedFiles.kt`, `upgrade/Provisioner.kt`): a
  provider returns the complete set every time, since anything missing counts
  as removed. `Seeded` files are the user's once edited; never overwrite them.
- **Saved data** (`upgrade/Versioned.kt`): stored as `{"v": N, "data": …}`.
  Changing a format bumps `N` and adds a migration. Never rename a persisted id.
- **The widget language**: a feature a widget could depend on goes into
  `Compiler.SINCE` with a bump of `Compiler.LEVEL`, and into
  `assets/widgets/README.md`, whose samples `ExamplesTest` compiles.
- **Pinned versions**: `ThemeStore.OMARCHY_REF` (then rerun
  `scripts/fetch-theme-colors.sh`), `CLIAMP_VERSION` in `Bootstrap.kt`, and the
  proot pins in `scripts/fetch-proot.sh` (with the links in `ui/LicensesScreen.kt`).
- **Native code**: the JNI names in `wayland/Native` are kept by
  `proguard-rules.pro`. proot is exec'd from `nativeLibraryDir`, hence the
  `lib*.so` names and `useLegacyPackaging`.
- **Security**: only `MainActivity` and the system-guarded services are
  exported; the rest trust their intents. The `exec` extra in `MainActivity`
  runs guest commands and stays behind `BuildConfig.DEBUG`. Guest processes
  run as the app's uid and can read all its data.
- **Threads**: widget evaluation runs on Main; disk and network go through
  `withContext(Dispatchers.IO)`. Anything that runs pacman holds `Pacman.lock`.

## Conventions

- Match the code around you: short KDoc on most declarations saying what it
  does and why, plain sentences, official Kotlin style with trailing commas.
  No formatter is checked in, so don't reformat lines you aren't changing.
- UI is Compose foundation without Material: `Palette`, `Mono`,
  `LauncherStyle`, lowercase terminal-style copy (`[ retry ]`).
- Tests are JUnit 4 on the JVM (`app/src/test`, `owl/src/test`).
  `android.util.Log` is a no-op there, and `org.json` is the real one. Add a
  test for new logic and for every bug fixed; pull pure logic out of
  Android-bound code to make it testable.
- This is a fork of [SimonSchubert/omalauncher](https://github.com/SimonSchubert/omalauncher).
  Keep changes small and in separate files where that's natural, so upstream
  syncs (`scripts/sync-upstream.sh`) stay easy. General fixes are worth offering upstream.

## Delivering

Work on a branch and open a pull request: what changed, why, how it was tested
(unit tests, emulator, phone), and screenshots for UI changes. Releases are the
maintainer's (docs/RELEASING.md); never touch signing keys or Play credentials.
