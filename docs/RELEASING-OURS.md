# Releasing our own build

Upstream ships debug builds; ours is signed with a household release key so it
can be updated in place (Android refuses same-package installs signed by a
different key).

## The key

- Keystore: `~/.hermes/keys/omalauncher-release.keystore` on the Pi (alias
  `omalauncher`, RSA 2048, ~27 years).
- Password: `~/.hermes/keys/omalauncher-release.password` (one line, no
  newline).
- Both are `600`, owned by `erebus`. The password is never printed in reports,
  logs, commits or chat.
- Backup: the proton nightly tar already includes `~/.hermes` (keys/ is not
  among its excludes), so the key rides the existing off-box backup. Losing
  this key means every installed phone must be uninstalled to switch keys -
  treat the backup as load-bearing.

## Building a signed release

On evo (the build box), with the repo rsynced to /root/owl-build:

1. Copy the keystore and password to the build machine (scp, then chmod 600).
2. Write `keystore.properties` in the repo root (git-ignored by convention -
   never commit it):

   ```
   storeFile=/root/keys/omalauncher-release.keystore
   storePassword=<contents of the password file>
   keyAlias=omalauncher
   keyPassword=<same>
   ```

   `app/build.gradle.kts` picks it up automatically; without the file the
   release build falls back to the debug key.
3. `./gradlew :app:assembleRelease`, then verify the cert:

   ```
   build-tools/35.0.0/apksigner verify --print-certs app-release.apk
   ```

   The SHA-256 fingerprint must match the keystore's (`keytool -list` on the
   Pi). Record it here:

   - cert SHA-256: 5D:6C:78:71:AA:CB:0B:BA:7A:7B:A9:92:05:F7:2D:AA:
     38:DE:1F:42:BC:17:D6:EA:7E:E9:B8:D0:3B:0C:27:90

## Switching a phone from the debug build

The first release-signed install replaces a debug-signed one only after an
uninstall, and uninstalling wipes app data - including the Arch rootfs and
the guest's ssh provisioning. That is survivable because the bootstrap step
`hermes-ssh-1` (docs/HERMES.md) rebuilds the ssh plumbing on the fresh
rootfs; the only manual part is pasting the printed pubkey into the Pi's
`~/.ssh/authorized_keys`.
