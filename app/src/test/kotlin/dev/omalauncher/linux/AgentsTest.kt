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
        for (agent in AGENTS) {
            val alias = aliases.firstOrNull { "android:name=\".${agent.windowAlias}\"" in it }
            assertTrue("${agent.bin}: no .${agent.windowAlias} in the manifest", alias != null)
            assertTrue("${agent.bin}: not labelled \"${agent.name}\"", "android:label=\"${agent.name}\"" in alias!!)
            assertTrue("${agent.bin}: no logo", "android:icon=\"@drawable/agent_${agent.bin.replace('-', '_')}\"" in alias)
        }
    }

    /** Hermes is the house assistant, not a mise package: she leads the list and takes the prompt with -q. */
    @Test fun hermesIsTheRemoteAgent() {
        val hermes = AGENTS.first { it.bin == "hermes" }
        assertTrue("hermes should be marked remote", hermes.remote)
        assertEquals("", hermes.pkg)
        assertEquals(hermes, AGENTS.first())
        assertEquals("hermes -q 'fix the build'", agentInvocation("hermes", "fix the build"))
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
        assertTrue(HERMES_WRAPPER.contains("hermes chat"))
        // ssh runs a non-login shell on the server: hermes lives in ~/.local/bin there.
        assertTrue(HERMES_WRAPPER.contains("PATH=\$HOME/.local/bin"))
        assertTrue(HERMES_WRAPPER.contains("HERMES_SSH_HOST"))
    }
}
