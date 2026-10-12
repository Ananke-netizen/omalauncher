package dev.omalauncher.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** The scripts the launcher installs in the guest's /usr/local/bin for the home server. */
class HouseScriptsTest {
    private val scripts = listOf("house", "house-menu", "house-mirror", "agenda")

    private fun asset(name: String) = File("src/main/assets/$name.sh")

    @Test fun everyScriptIsBashAndParses() {
        for (s in scripts) {
            val f = asset(s)
            assertTrue("$s.sh", f.isFile && f.readText().startsWith("#!/bin/bash\n"))
            val p = ProcessBuilder("bash", "-n", f.path).redirectErrorStream(true).start()
            assertTrue(p.waitFor(20, TimeUnit.SECONDS))
            assertEquals("$s: " + p.inputStream.bufferedReader().readText(), 0, p.exitValue())
        }
    }

    @Test fun theDrawerAppsAreInstalledCommands() {
        assertTrue(WidgetEngine.HOUSE_APPS.all { (bin, _) -> bin in scripts })
    }

    @Test fun userTextNeverReachesTheServersShell() {
        // add and capture send what was typed on stdin, read there with "$(cat)".
        val house = asset("house").readText()
        assertTrue(house.contains("printf '%s' \"\$2\" | pi \"~/scripts/house-todo add \\\"\\\$(cat)\\\" \$due\""))
        assertTrue(house.contains("printf '%s' \"\$2\" | pi '~/scripts/house-capture \"\$(cat)\"'"))
    }

    @Test fun theMenuNeverRestartsModelServers() {
        val deny = Regex("DENY='([^']+)'").find(asset("house-menu").readText())!!.groupValues[1].toRegex()
        for (unit in listOf("llama-swap.service", "llama-server.service", "llama-warm.timer", "halogen.service")) assertTrue(unit, deny.containsMatchIn(unit))
        assertTrue("glean.service" !in listOf("glean.service").filter { deny.containsMatchIn(it) })
    }
}
