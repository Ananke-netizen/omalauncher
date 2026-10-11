# The hermes agent (remote over ssh)

`hermes` is the one agent in the list that doesn't run inside the guest.
Hermes (our house assistant) runs on the home server; the launcher installs a
small wrapper at `~/.local/share/omalauncher/bin/hermes` that sshes there and
runs `hermes chat`. The wrapper lives in launcher data that proot binds in, so
it survives removing and reinstalling Linux.

## What "install" does

`AgentStore.installScript` writes the wrapper (see `HERMES_WRAPPER` in
`Agents.kt`) into the bound bin and chmods it. No mise, no download.
`isInstalled` is simply "the wrapper file exists". Removing deletes the file.

The prompt bar sends `hermes -q <prompt>`: `hermes chat -q` seeds the query and
exits when it has answered, which is what a terminal task wants.

## What the phone needs (Nick's hands, once)

The wrapper cannot provision itself; it fails with a clear message until these
are true:

1. **openssh in the guest.** The bootstrap installs it (`packages-2`). On a
   system set up before that step existed, install it by hand once:
   `pacman -Syu openssh`.
2. **A path to the server.** The home server must be resolvable and reachable
   from the guest. We use Tailscale: run `tailscale up` on the phone (Android
   app) and on the server; the wrapper's default host is `raspberrypi` (the
   tailnet name). Override with `HERMES_SSH_HOST=<host>` in the terminal.
3. **A key.** Generate one in the guest (`ssh-keygen -t ed25519`), then append
   its `.pub` to `~/.ssh/authorized_keys` on the server. The wrapper probes
   with `BatchMode=yes`, so a missing key says so instead of hanging on a
   password prompt.
4. **`hermes` on the server's non-login PATH.** The wrapper runs a non-login
   shell remotely; if `hermes` isn't found, make sure it's in `~/.local/bin`
   (which most installs already do).

Nothing here sends anything beyond the LAN/tailnet, and the launcher stores no
secrets: the key stays in the guest's `~/.ssh`, which is inside the rootfs and
never leaves the phone.

## Why not a first-class ssh/Tailscale integration?

A real story (key storage outside the rootfs, host-key pinning, reconnect UX,
Tailscale as an Android VPN rather than a guest binary) is a design of its own.
This wrapper is deliberately dumb so that story can replace it later without
touching the agent list.
