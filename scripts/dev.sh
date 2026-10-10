#!/usr/bin/env bash
# The local dev loop in one place, for people and coding agents alike: finds the JDKs
# and the Android SDK the build needs, runs Gradle with them, and drives an emulator.
# See docs/DEVELOPMENT.md.
#
#   scripts/dev.sh doctor             what's missing, and how to get it
#   scripts/dev.sh setup              installs what it can (JDKs via mise, SDK packages, proot, the AVD)
#   scripts/dev.sh env                exports for your shell: eval "$(scripts/dev.sh env)"
#   scripts/dev.sh test               the unit tests CI runs
#   scripts/dev.sh build              the debug APK
#   scripts/dev.sh gradle <args>      ./gradlew with the right JDKs
#   scripts/dev.sh emulator start [--headless] | stop | status
#   scripts/dev.sh run                installs the debug build and opens the home screen
#   scripts/dev.sh exec <command>     runs a guest command in a Linux window (debug builds)
#   scripts/dev.sh shot [file.png]    a screenshot of the device
#   scripts/dev.sh logcat             the app's log
#   scripts/dev.sh reset              clears the app's data (emulators only, unless --force)
#
# The device is $ANDROID_SERIAL when set, else the emulator this script starts.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_ID=dev.omalauncher
AVD="${OMALAUNCHER_AVD:-omalauncher-api36}"
AVD_API=36
AVD_DEVICE=pixel_7
EMU_PORT="${OMALAUNCHER_EMU_PORT:-5554}"
PROOT_LIBS="$ROOT/app/src/main/jniLibs/arm64-v8a"

say() { printf '%s\n' "$*" >&2; }
die() { say "error: $*"; exit 1; }

# The major version of the JDK at $1, or nothing.
jdk_major() { [ -x "$1/bin/java" ] && sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' "$1/release" 2>/dev/null; }

# A JDK of major version $1: $JDK<n>_HOME, $JAVA_HOME, mise's, then the usual system places.
find_jdk() {
  local v=$1 var="JDK$1_HOME" c
  for c in "${!var:-}" "${JAVA_HOME:-}" \
    "$(command -v mise >/dev/null && mise where "java@temurin-$v" 2>/dev/null || true)" \
    /usr/lib/jvm/java-"$v"-openjdk* /usr/lib/jvm/temurin-"$v"* /usr/lib/jvm/jdk-"$v"* \
    /Library/Java/JavaVirtualMachines/temurin-"$v".jdk/Contents/Home; do
    [ -n "$c" ] && [ "$(jdk_major "$c")" = "$v" ] && { echo "$c"; return 0; }
  done
  return 1
}

# The Android SDK: $ANDROID_HOME, $ANDROID_SDK_ROOT, local.properties, then the default places.
find_sdk() {
  local c props
  props=$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" 2>/dev/null || true)
  for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$props" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    [ -n "$c" ] && { [ -d "$c/platform-tools" ] || [ -d "$c/cmdline-tools" ]; } && { echo "$c"; return 0; }
  done
  return 1
}

# Where AVDs live. avdmanager follows $XDG_CONFIG_HOME and the emulator doesn't, so both get it explicitly.
avd_home() {
  if [ -n "${ANDROID_AVD_HOME:-}" ]; then echo "$ANDROID_AVD_HOME"
  elif [ -n "${XDG_CONFIG_HOME:-}" ] && [ -d "$XDG_CONFIG_HOME/.android/avd" ]; then echo "$XDG_CONFIG_HOME/.android/avd"
  else echo "$HOME/.android/avd"; fi
}

host_abi() { case "$(uname -m)" in arm64 | aarch64) echo arm64-v8a ;; *) echo x86_64 ;; esac; }

JDK25=$(find_jdk 25 || true)
JDK17=$(find_jdk 17 || true)
SDK=$(find_sdk || true)

load_env() {
  [ -n "$JDK25" ] && export JAVA_HOME="$JDK25" PATH="$JDK25/bin:$PATH"
  if [ -n "$SDK" ]; then
    export ANDROID_HOME="$SDK" PATH="$SDK/platform-tools:$SDK/emulator:$SDK/cmdline-tools/latest/bin:$PATH"
  fi
  ANDROID_AVD_HOME="$(avd_home)"
  export ANDROID_AVD_HOME
}

# owl compiles with a JDK 17 toolchain and the daemon wants 25 (gradle/gradle-daemon-jvm.properties);
# Gradle doesn't look in mise's directory, so it's told where they are.
gradle() {
  load_env
  [ -n "$SDK" ] || die "no Android SDK found; run scripts/dev.sh doctor"
  local paths
  paths=$(printf '%s\n' "$JDK17" "$JDK25" | grep . | paste -sd, -)
  "$ROOT/gradlew" -p "$ROOT" ${paths:+"-Porg.gradle.java.installations.paths=$paths"} "$@"
}

serial() { echo "${ANDROID_SERIAL:-emulator-$EMU_PORT}"; }
adb_() { adb -s "$(serial)" "$@"; }

booted() { [ "$(adb_ shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; }

check() {
  # $1 ok?, $2 label, $3 hint
  if [ "$1" = 1 ]; then printf '  ok    %s\n' "$2"; else printf '  MISS  %s\n        %s\n' "$2" "$3"; fi
}

doctor() {
  load_env
  local missing=0 ok
  echo "toolchain"
  ok=0; [ -n "$JDK25" ] && ok=1
  check $ok "JDK 25 (Gradle daemon)${JDK25:+: $JDK25}" "scripts/dev.sh setup (with mise), or set JDK25_HOME; Gradle can also download it itself"
  ok=0; [ -n "$JDK17" ] && ok=1 || missing=1
  check $ok "JDK 17 (owl's toolchain)${JDK17:+: $JDK17}" "scripts/dev.sh setup (with mise), or set JDK17_HOME"
  ok=0; [ -n "$SDK" ] && ok=1 || missing=1
  check $ok "Android SDK${SDK:+: $SDK}" "install Android Studio or the command-line tools, then set ANDROID_HOME"
  ok=0; [ -n "$SDK" ] && [ -x "$SDK/platform-tools/adb" ] && ok=1
  check $ok "platform-tools (adb)" "scripts/dev.sh setup"
  echo "full build"
  ok=0; [ -f "$PROOT_LIBS/libproot.so" ] && ok=1
  check $ok "proot in app/src/main/jniLibs" "scripts/fetch-proot.sh (scripts/dev.sh setup runs it)"
  if [ "$(uname)" = Linux ]; then
    ok=0; command -v bsdtar >/dev/null && ok=1
    check $ok "bsdtar (reads Termux's .deb files)" "install libarchive (Arch: pacman -S libarchive, Debian: apt install libarchive-tools)"
  fi
  echo "emulator"
  ok=0; [ -n "$SDK" ] && [ -x "$SDK/emulator/emulator" ] && ok=1
  check $ok "emulator" "scripts/dev.sh setup"
  ok=0; [ -n "$SDK" ] && [ -d "$SDK/system-images/android-$AVD_API/google_apis/$(host_abi)" ] && ok=1
  check $ok "system image android-$AVD_API google_apis $(host_abi)" "scripts/dev.sh setup"
  ok=0; [ -f "$ANDROID_AVD_HOME/$AVD.ini" ] && ok=1
  check $ok "AVD $AVD in $ANDROID_AVD_HOME" "scripts/dev.sh setup"
  ok=0; [ -r /dev/kvm ] && [ -w /dev/kvm ] && ok=1
  [ "$(uname)" = Linux ] && check $ok "/dev/kvm (hardware acceleration)" "add yourself to the kvm group"
  echo "devices"
  if command -v adb >/dev/null; then adb devices | sed 1d | grep . | sed 's/^/  /' || echo "  none"; fi
  [ $missing = 0 ] || { say "missing pieces: the unit tests need both JDKs and the SDK"; return 1; }
}

setup() {
  if [ -z "$JDK25" ] || [ -z "$JDK17" ]; then
    command -v mise >/dev/null || die "no mise: install JDK 25 and 17 yourself (Arch: pacman -S jdk-openjdk jdk17-openjdk) or set JDK25_HOME/JDK17_HOME"
    say "installing JDKs with mise"
    mise install java@temurin-25 java@temurin-17
    JDK25=$(find_jdk 25 || true)
    JDK17=$(find_jdk 17 || true)
  fi
  [ -n "$SDK" ] || die "no Android SDK found: install Android Studio or the command-line tools, then set ANDROID_HOME"
  load_env
  local sdkmanager="$SDK/cmdline-tools/latest/bin/sdkmanager" image pkgs=()
  image="system-images;android-$AVD_API;google_apis;$(host_abi)"
  # Only what's missing: installing an installed package would update it, in an SDK other projects share.
  [ -x "$SDK/platform-tools/adb" ] || pkgs+=(platform-tools)
  [ -x "$SDK/emulator/emulator" ] || pkgs+=(emulator)
  [ -d "$SDK/system-images/android-$AVD_API/google_apis/$(host_abi)" ] || pkgs+=("$image")
  if [ ${#pkgs[@]} -gt 0 ]; then
    [ -x "$sdkmanager" ] || die "no sdkmanager in $SDK/cmdline-tools/latest: install the SDK command-line tools"
    # Asks to accept any license that isn't yet. The build installs the platform, NDK and CMake it needs by itself.
    "$sdkmanager" --install "${pkgs[@]}"
  fi
  [ -f "$ROOT/local.properties" ] || { echo "sdk.dir=$SDK" > "$ROOT/local.properties"; say "wrote local.properties"; }
  [ -f "$PROOT_LIBS/libproot.so" ] || "$ROOT/scripts/fetch-proot.sh"
  if [ ! -f "$ANDROID_AVD_HOME/$AVD.ini" ]; then
    echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" -k "$image" -d "$AVD_DEVICE"
    # Room for the Linux rootfs (a 830 MB download, a few GB unpacked), and a hardware keyboard for Super shortcuts.
    sed -i.bak -e 's/^hw.ramSize=.*/hw.ramSize=4096/' -e 's/^hw.keyboard=.*/hw.keyboard=yes/' \
      -e 's/^disk.dataPartition.size=.*/disk.dataPartition.size=12G/' "$ANDROID_AVD_HOME/$AVD.avd/config.ini"
    rm -f "$ANDROID_AVD_HOME/$AVD.avd/config.ini.bak"
  fi
  doctor
}

emulator_() {
  load_env
  case "${1:-start}" in
    start)
      if booted; then say "$(serial) is already up"; return; fi
      local window=() log="$ROOT/build/emulator.log"
      if [ "${2:-}" = --headless ] || { [ -z "${WAYLAND_DISPLAY:-}" ] && [ -z "${DISPLAY:-}" ] && [ "$(uname)" != Darwin ]; }; then
        window=(-no-window -no-audio)
      fi
      mkdir -p "$ROOT/build"
      # Not from a snapshot, so every run starts from the same boot; the AVD's data stays between runs.
      nohup emulator -avd "$AVD" -port "$EMU_PORT" -no-snapshot -no-boot-anim -gpu swiftshader_indirect "${window[@]}" \
        > "$log" 2>&1 &
      echo $! > "$ROOT/build/emulator.pid"
      say "booting $AVD as $(serial) (log: build/emulator.log)"
      for _ in $(seq 180); do
        booted && { say "$(serial) is up"; return; }
        kill -0 "$(cat "$ROOT/build/emulator.pid")" 2>/dev/null || { tail -5 "$log" >&2; die "the emulator exited"; }
        sleep 2
      done
      die "$(serial) didn't boot in 6 minutes; see build/emulator.log"
      ;;
    stop) adb_ emu kill && rm -f "$ROOT/build/emulator.pid" ;;
    status)
      if booted; then echo "$(serial): booted"; else echo "$(serial): not running"; return 1; fi
      ;;
    *) die "emulator start [--headless] | stop | status" ;;
  esac
}

run() {
  gradle :app:installDebug
  # As a plain app, so the emulator's own home screen stays the default.
  adb_ shell am start -W -n "$APP_ID/.MainActivity" >/dev/null
}

reset() {
  load_env
  case "$(serial)" in
    emulator-*) ;;
    *) [ "${1:-}" = --force ] || die "$(serial) isn't an emulator: this deletes the launcher's data and the Linux install; pass --force" ;;
  esac
  adb_ shell pm clear "$APP_ID"
}

cmd="${1:-help}"
shift || true
case "$cmd" in
  doctor) doctor ;;
  setup) setup ;;
  env)
    load_env
    echo "export JAVA_HOME='${JAVA_HOME:-}' ANDROID_HOME='${ANDROID_HOME:-}' ANDROID_AVD_HOME='$ANDROID_AVD_HOME' PATH='$PATH'"
    paths=$(printf '%s\n' "$JDK17" "$JDK25" | grep . | paste -sd, -)
    [ -n "$paths" ] && echo "alias gw='$ROOT/gradlew -p $ROOT -Porg.gradle.java.installations.paths=$paths'"
    ;;
  gradle) gradle "$@" ;;
  test) gradle :owl:test :app:testDebugUnitTest :owl:owlJar "$@" ;;
  build) gradle :app:assembleDebug "$@" ;;
  emulator) emulator_ "$@" ;;
  run) load_env && run ;;
  exec)
    [ $# -gt 0 ] || die "exec <command>"
    load_env
    adb_ shell am start -n "$APP_ID/.MainActivity" --es exec "'$*'" >/dev/null
    ;;
  shot)
    load_env
    out="${1:-$ROOT/build/screenshots/$(date +%Y%m%d-%H%M%S).png}"
    mkdir -p "$(dirname "$out")"
    adb_ exec-out screencap -p > "$out"
    echo "$out"
    ;;
  logcat)
    load_env
    pid=$(adb_ shell pidof "$APP_ID" | tr -d '\r')
    [ -n "$pid" ] || die "$APP_ID isn't running"
    adb_ logcat --pid="$pid" "$@"
    ;;
  reset) reset "$@" ;;
  help | -h | --help) sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//' ;;
  *) die "unknown command $cmd; see scripts/dev.sh help" ;;
esac
