package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentsTest {
    /** A missing alias would make opening that agent's window throw. */
    @Test fun everyAgentHasAWindowAlias() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val aliases = Regex("""<activity-alias(.*?)/>""", RegexOption.DOT_MATCHES_ALL).findAll(manifest).map { it.groupValues[1] }.toList()
        for (agent in AGENTS.filterNot { it.android }) {   // an Android app has no terminal window
            val alias = aliases.firstOrNull { "android:name=\".${agent.windowAlias}\"" in it }
            assertTrue("${agent.bin}: no .${agent.windowAlias} in the manifest", alias != null)
            assertTrue("${agent.bin}: not labelled \"${agent.name}\"", "android:label=\"${agent.name}\"" in alias!!)
            assertTrue("${agent.bin}: no logo", "android:icon=\"@drawable/agent_${agent.bin.replace('-', '_')}\"" in alias)
        }
    }

    /** Hermes is the house assistant, not a mise package: she leads the Linux agents and takes the prompt with -q. */
    @Test fun hermesIsTheRemoteAgent() {
        val hermes = AGENTS.first { it.bin == "hermes" }
        assertTrue("hermes should be marked remote", hermes.remote)
        assertEquals("", hermes.pkg)
        assertEquals(hermes, AGENTS.first { !it.android })
        assertEquals("hermes -q 'fix the build'", agentInvocation("hermes", "fix the build"))
    }

    /** Pantheon is the android kind: first in the list (so the default with no pick), no mise, no guest. */
    @Test fun pantheonIsTheAndroidAgent() {
        val pantheon = AGENTS.first()
        assertEquals("pantheon", pantheon.bin)
        assertTrue(pantheon.android)
        assertEquals("com.axum.pocket", pantheon.app)
        assertEquals("", pantheon.pkg)
        assertFalse(pantheon.remote)
        assertEquals(listOf(pantheon), AGENTS.filter { it.android })
        // hold-to-talk goes to Pantheon's provider, not the wrapper's headless path
        assertFalse(askHeadless(pantheon, hold = true))
    }

    /** Enter opens Pantheon with the reply streaming; hold-to-talk asks without a window. */
    @Test fun pantheonVerbs() {
        assertEquals("ask_open", pantheonVerb(hold = false))
        assertEquals("ask_silent", pantheonVerb(hold = true))
        assertEquals("com.axum.pocket.ask", PANTHEON_ASK_AUTHORITY)
    }

    /** With Pantheon gone or refusing, the hermes wrapper takes the prompt; nothing else does. */
    @Test fun androidFallsBackToTheHermesWrapperOnly() {
        val (pantheon, hermes, claude) = listOf("pantheon", "hermes", "claude").map { b -> AGENTS.first { it.bin == b } }
        assertEquals(hermes, androidFallback(listOf(pantheon, hermes, claude)))
        assertEquals(null, androidFallback(listOf(pantheon, claude)))
        assertEquals(null, androidFallback(emptyList()))
    }

    /** The chip: Pantheon, Hermes and the picked agent by default; everything with the setting on. */
    @Test fun chipListsPantheonHermesAndThePick() {
        val all = AGENTS.filter { it.bin in listOf("pantheon", "hermes", "claude", "codex") }
        val claude = all.first { it.bin == "claude" }
        assertEquals(listOf("pantheon", "hermes", "claude"), chipAgents(all, claude, all = false).map { it.bin })
        assertEquals(listOf("pantheon", "hermes"), chipAgents(all, all.first(), all = false).map { it.bin })
        assertEquals(all, chipAgents(all, claude, all = true))
    }

    /** mise must never see a remote agent: an empty pkg would match every mise tool name. */
    @Test fun remoteAgentsHaveNoMisePackage() {
        for (agent in AGENTS.filter { it.remote }) {
            assertEquals(agent.bin, "", agent.pkg)
            assertFalse(agent.bin, agent.pkg in listOf("claude", "opencode", "codex"))
        }
    }

    /** The wrapper must say what to fix when ssh can't reach the server, not hang or vanish. */
    @Test fun hermesWrapperFailsWithInstructions() {
        assertTrue(HERMES_WRAPPER.contains("BatchMode=yes"))
        assertTrue(HERMES_WRAPPER.contains("authorized_keys"))
        assertTrue(HERMES_WRAPPER.contains("omalauncher-ask.sh"))
        // ssh runs a non-login shell on the server: hermes lives in ~/.local/bin there.
        assertTrue(HERMES_WRAPPER.contains("PATH=\$HOME/.local/bin"))
        assertTrue(HERMES_WRAPPER.contains("HERMES_SSH_HOST"))
    }

    /**
     * The remote command must reach the Pi's shell unexpanded: the guest's own shell runs with
     * HOME=/root, so a double-quoted "export PATH=$HOME/..." would set /root/.local/bin on the
     * server and the Pi would answer "command not found". Single quotes keep it ours.
     */
    @Test fun remotePathExpandsOnTheServerNotInTheGuest() {
        val remote = HERMES_WRAPPER.substringAfter("| ssh \"\$host\" '").substringBeforeLast("' &&")
        assertTrue(remote.contains("export PATH=\$HOME/.local/bin"))
        assertTrue(remote.contains("ask=\"\$HOME/scripts/omalauncher-ask.sh\""))
        assertFalse(HERMES_WRAPPER.contains("\"export PATH"))
    }

    /**
     * The ask is async by design: the query goes to the Pi-side script detached (setsid), the
     * wrapper prints one line and exits — the launcher window must not wait for Hermes' answer.
     */
    @Test fun hermesAskIsFireAndForget() {
        val remote = HERMES_WRAPPER.substringAfter("| ssh \"\$host\" '").substringBeforeLast("' &&")
        assertTrue("the ask must run detached", remote.contains("setsid \"\$ask\""))
        assertTrue(HERMES_WRAPPER.contains("sent to Hermes"))
        // agentInvocation still passes -q first; the wrapper swallows it.
        assertTrue(HERMES_WRAPPER.contains("\"-q\" ] && shift"))
    }

    /** Hold-to-talk on a remote agent skips the terminal window; local agents keep theirs. */
    @Test fun holdOnARemoteAgentIsHeadless() {
        val hermes = AGENTS.first { it.bin == "hermes" }
        val claude = AGENTS.first { it.bin == "claude" }
        assertTrue(askHeadless(hermes, hold = true))
        assertFalse(askHeadless(hermes, hold = false)) // a tap still dictates into the field, then Enter opens the window
        assertFalse(askHeadless(claude, hold = true)) // a local agent works inside its window
    }
}
