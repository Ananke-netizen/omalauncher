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
are true. Hermes did this end to end on Nick's Pixel on 2026-10-11 and it
answered a real chat through the bar.

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
   shell remotely and exports `PATH=$HOME/.local/bin:$PATH` there (single
   quotes, so the Pi's shell expands it — the guest's own shell runs with
   HOME=/root and would corrupt a double-quoted version).

### What provisioning looked like on a real Pixel

All of this lived inside the rootfs (`arch/root/.ssh`, `arch/etc/resolv.conf`),
so **removing and reinstalling Linux wipes it**; expect to redo it, or move it
to bound launcher data (open work, see below).

- Guest keypair at `arch/root/.ssh/id_ed25519`; its pubkey went into the Pi's
  `~/.ssh/authorized_keys`.
- `~/.ssh/config` in the guest mapping both `raspberrypi` and `pi` to
  `pi.<tailnet>.ts.net` with `User erebus`.
- `known_hosts` entries for BOTH the tailnet IP and the MagicDNS hostname:
  ssh connects by name, and an IP-only entry fails host key verification.
- `arch/etc/resolv.conf` with `nameserver 100.100.100.100`, otherwise the
  guest cannot resolve MagicDNS names at all.

## The ask path (async)

A bar query does not open a one-off `hermes chat` session. The wrapper pipes the
query to `~/scripts/omalauncher-ask.sh` on the Pi, fired detached (setsid), and
prints "sent to Hermes - answer lands in Pantheon" and exits, so the terminal
window closes immediately. On the Pi the script delivers the query into Hermes'
canonical Bot Chat via `bot_mode_dm.py --run-delivery` (author `bot:pixel` /
"Omalauncher"; it queues when she is busy), then pushes her reply to the
Pantheon inbox through `~/scripts/inbox-push` (bridge token read from
`~/.hermes-pocket`, never printed). The phone gets the answer as an inbox note
titled "Omalauncher: <first words of query>".

The script lives on the Pi, not in the wrapper, so its logic can change without
touching the app. If it is missing the wrapper says so plainly.

Note for existing installs: the wrapper in the guest's bound bin is only
rewritten when the Hermes agent is reinstalled from the agents screen (Linux >
agents > Hermes > install). After an app update that changes HERMES_WRAPPER,
reinstall it once.

## Open work

The ssh plumbing (key, config, known_hosts, resolv.conf) dying with the rootfs
is known. The clean fix is a bootstrap step or bound launcher data for
`~/.ssh` and resolv.conf, plus host-key pinning and Tailscale as an Android
VPN rather than a guest binary. That is a design of its own; this wrapper is
deliberately dumb so that story can replace it later without touching the
agent list.
