# Development

Setting up a machine to build, test and run Omalauncher, and the loop for
working on it. Agents: [AGENTS.md](../AGENTS.md) has the short version.

## What the build needs

| | Version | Why |
|---|---|---|
| JDK | 25 | Gradle's daemon (`gradle/gradle-daemon-jvm.properties`) |
| JDK | 17 | the `owl` module's toolchain (`jvmToolchain(17)`) |
| Android SDK | platform 37 | `compileSdk`; the build installs it, NDK 28.2 and CMake 3.22.1 itself once the SDK licenses are accepted |
| proot | Termux's, pinned | `scripts/fetch-proot.sh`; only for the APK, not the unit tests |
| bsdtar | any | on Linux, for fetch-proot (GNU tar can't read `.deb`); Arch: `libarchive` |
| emulator | system image API 36 | optional; `/dev/kvm` on Linux |

Gradle doesn't find JDKs installed by mise by itself, so `scripts/dev.sh`
passes them as `org.gradle.java.installations.paths`. It looks in
`$JDK25_HOME`/`$JDK17_HOME`, `$JAVA_HOME`, mise, `/usr/lib/jvm` and macOS's
JavaVirtualMachines.

## Getting started

```sh
scripts/dev.sh doctor   # checks all of the above
scripts/dev.sh setup    # JDKs via mise, SDK packages, proot, local.properties, an AVD
scripts/dev.sh test     # the unit tests CI runs
```

`setup` doesn't install the Android SDK itself: install Android Studio or the
command-line tools first, and set `ANDROID_HOME` unless the SDK is in
`~/Android/Sdk` (Linux) or `~/Library/Android/sdk` (macOS).

For a shell where `adb`, `emulator` and `android` are on the PATH:
`eval "$(scripts/dev.sh env)"`; it also defines `gw`, `./gradlew` with the JDKs.

## The loop

```sh
scripts/dev.sh emulator start      # a window when there's a display; --headless otherwise
scripts/dev.sh run                 # build, install, open the home screen
scripts/dev.sh shot                # screenshot into build/screenshots/
scripts/dev.sh logcat              # the app's log (-d to dump and exit)
scripts/dev.sh exec foot           # debug builds: runs a guest command in a Linux window
scripts/dev.sh reset               # back to the first-run screen (emulators only)
scripts/dev.sh emulator stop
```

The AVD is `omalauncher-api36` (Pixel 7, Google APIs, 4 GB RAM, a hardware
keyboard for the Super shortcuts), on port 5554, so its serial is
`emulator-5554`. `OMALAUNCHER_AVD` and `OMALAUNCHER_EMU_PORT` change them;
`ANDROID_SERIAL` points every command at another device. The emulator boots
cold each time (no snapshot) but keeps the AVD's data. Its log is
`build/emulator.log`.

`scripts/dev.sh run` installs the app without making it the home app. The
app asks once, on reaching the home screen; to answer from a script:
`adb shell cmd role add-role-holder android.app.role.HOME dev.omalauncher`.

### Driving the UI from a script

Recent SDK command-line tools (23+) ship `android`, a CLI meant for agents:

- `android layout --flat` prints the views on screen as JSON, with text,
  bounds, `CLICKABLE`/`SCROLLABLE` and a tap `center`. Compose texts show up.
  The first call installs a small instrumentation server on the device.
- `android screen capture` saves a PNG; `scripts/dev.sh shot` does the same through adb.
- `android docs search "<query>"` searches developer.android.com.

Tap with `adb shell input tap <x> <y>`, type with `adb shell input text`, and
send keys with `adb shell input keyevent`.

## What can be tested where

| | JVM tests | x86_64 emulator | arm64 phone |
|---|---|---|---|
| owl language, store, sharing | ✓ | ✓ | ✓ |
| search ranking, saved data, managed files | ✓ | ✓ | ✓ |
| first-run, home screen, themes, wallpapers | | ✓ | ✓ |
| widgets that don't run commands, Android apps | | ✓ | ✓ |
| Linux setup past the download, pacman, agents | | | ✓ |
| terminals, Linux windows, the compositor with real clients | | | ✓ |
| GPU acceleration | | | Mali v10+ only |

The emulator runs the arm64-only app through Android's ARM translation
(`libndk_translation`). That covers the app's own code, but not proot: the
translated linker can't load it (`library "libtalloc.so" not found`, checked on
the API 36 image), and proot's ptrace-based syscall translation expects a real
aarch64 process anyway. An arm64 emulator on an arm64 host (Apple silicon, an
arm64 Linux box with KVM) runs everything natively and should cover the Linux
side except the GPU. That's untested.

### On a phone

Enable USB debugging, or pair over Wi-Fi with `adb pair`, then:

```sh
adb devices                                   # its serial
ANDROID_SERIAL=<serial> scripts/dev.sh run
# skip the 830 MB rootfs download:
adb push ArchLinuxARM-aarch64-latest.tar.gz /sdcard/Android/data/dev.omalauncher/files/
```

The debug build has the Play build's package name but another signature, so
Android won't install one over the other. Uninstalling first deletes the Linux
install and the widgets: use a spare phone, or back up the widgets
(`~/.config/omalauncher/widgets` in Linux) first.

## Keeping up with upstream

This repository is a fork of
[SimonSchubert/omalauncher](https://github.com/SimonSchubert/omalauncher).
Upstream published its history as a single squashed commit, so its history
may be rewritten again, and a plain `git merge` would then have nothing in
common to merge from. Instead:

```sh
scripts/sync-upstream.sh --check   # upstream's commits (or diff) since the last sync
scripts/sync-upstream.sh           # a branch upstream-sync/<sha> off origin/main with them
scripts/dev.sh test                # then push the branch and open a pull request
```

`.upstream` records the upstream commit we're synced to. The script applies
upstream's diff since then with a three-way merge, writes the new commit into
`.upstream`, and lists upstream's commits in the commit message. That works
whether or not upstream rewrote its history, and whether the pull request is
merged or squashed. On conflicts it stops with the files marked; resolve them,
`git add` and commit.

## Troubleshooting

- **`Unknown AVD name`**: `avdmanager` puts AVDs under `$XDG_CONFIG_HOME/.android/avd`
  when that's set, and the emulator looks in `~/.android/avd`. `scripts/dev.sh`
  sets `ANDROID_AVD_HOME` for both; do the same when starting the emulator yourself.
- **Gradle can't find a Java 17 toolchain**: go through `scripts/dev.sh gradle`, or
  set `org.gradle.java.installations.paths` in `~/.gradle/gradle.properties`.
- **`This does not look like a tar archive` from fetch-proot**: install bsdtar (libarchive).
- **fetch-proot 404s**: Termux only keeps current versions. Update the pins and
  SHA256s in `scripts/fetch-proot.sh` and the source links in `ui/LicensesScreen.kt` together.
- **The emulator is slow or won't start**: check `/dev/kvm` (`scripts/dev.sh doctor`) and `build/emulator.log`.
