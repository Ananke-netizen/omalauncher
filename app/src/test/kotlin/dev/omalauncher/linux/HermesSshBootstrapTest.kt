package dev.omalauncher.linux

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bootstrap step that rebuilds the hermes ssh plumbing after a Linux reinstall
 * (docs/HERMES.md). The step-journal id hermes-ssh-1 re-runs it on every fresh rootfs.
 */
class HermesSshBootstrapTest {
    private val script = hermesSshScript("pi.example.ts.net", "someone")

    /** A wiped rootfs has no key: generate one, but never over an existing one. */
    @Test fun keypairIsGeneratedOnlyWhenAbsent() {
        assertTrue(script.contains("[ -f ~/.ssh/id_ed25519 ] || ssh-keygen -t ed25519 -N ''"))
    }

    /** The host alias the wrapper uses (raspberrypi/pi) must point at the server by name and user. */
    @Test fun sshConfigMapsTheAliasesToTheServer() {
        assertTrue(script.contains("Host raspberrypi pi"))
        assertTrue(script.contains("HostName %s"))
        assertTrue(script.contains("'pi.example.ts.net' 'someone'"))
    }

    /**
     * ssh connects by MagicDNS name, so known_hosts needs the name's entry; the tailnet IP
     * entry alone fails host key verification. Both are scanned.
     */
    @Test fun hostKeysCoverNameAndIp() {
        assertTrue(script.contains("ssh-keyscan -T 10 pi.example.ts.net"))
        assertTrue(script.contains("ssh-keyscan -T 10 pi.example.ts.net \"\$ip\""))
    }

    /** The one thing the phone can't do alone: the pubkey must be printed for a manual paste. */
    @Test fun printsThePubkeyForTheAuthorizedKeysPaste() {
        assertTrue(script.contains("authorized_keys"))
        assertTrue(script.trimEnd().endsWith("cat ~/.ssh/id_ed25519.pub"))
    }

    /** The guest resolves MagicDNS names only through Tailscale's DNS. */
    @Test fun guestDnsIsTailscale() {
        assertTrue(HERMES_TAILSCALE_DNS == "100.100.100.100")
        assertTrue(HERMES_SSH_HOST.endsWith("ts.net"))
    }
}
