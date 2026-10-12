package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The android agent kind in the store: Pantheon by default, never in a terminal, never writing a guest that isn't there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentStoreTest {
    private lateinit var proot: Proot
    private var pantheonOnPhone = true

    private fun store() = AgentStore(proot, onChanged = {}, appInstalled = { it == PANTHEON_PACKAGE && pantheonOnPhone })

    private fun installHermesWrapper() {
        File(proot.filesDir, "bin").mkdirs()
        File(proot.filesDir, "bin/hermes").writeText("#!/bin/sh\n")
    }

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        // Robolectric leaves it unset; Proot only reads it to find the bundled binaries.
        app.applicationInfo.nativeLibraryDir = File(app.filesDir, "lib").path
        proot = Proot(app)
        proot.rootfs.deleteRecursively()
        File(proot.filesDir, "bin").deleteRecursively()
    }

    @Test fun withoutLinuxPantheonIsTheDefaultAndNothingIsWritten() {
        val s = store().apply { load() }
        assertEquals("pantheon", s.default?.bin)
        assertNull(s.terminalDefault)                      // no Linux agent to edit widgets with
        assertFalse("must not create a rootfs", proot.rootfs.exists())
        s.choose(s.default!!)
        assertFalse("choose must not create a rootfs either", proot.rootfs.exists())
    }

    @Test fun pantheonIsPromotedOnceOverAnEarlierHermesPick() {
        proot.home.mkdirs()
        installHermesWrapper()
        File(proot.launcherConfig.apply { mkdirs() }, "agent").writeText("hermes\n")
        val s = store().apply { load() }
        assertEquals("pantheon", s.default?.bin)
        assertEquals("hermes", s.terminalDefault?.bin)     // widget edits still go to a terminal agent
        assertEquals("pantheon", File(proot.launcherConfig, "agent").readText().trim())
        // Nick picks hermes again: that stands across restarts.
        s.choose(s.installed.first { it.bin == "hermes" })
        val again = store().apply { load() }
        assertEquals("hermes", again.default?.bin)
    }

    @Test fun withoutPantheonHermesLeadsAsBefore() {
        pantheonOnPhone = false
        proot.home.mkdirs()
        installHermesWrapper()
        val s = store().apply { load() }
        assertEquals("hermes", s.default?.bin)
        assertTrue(s.installed.none { it.android })
        assertFalse(File(proot.launcherConfig, "agent-pantheon-promoted").exists())
    }

    @Test fun pantheonNeverGetsADesktopEntry() {
        proot.home.mkdirs()
        installHermesWrapper()
        val s = store().apply { load() }
        val entries = s.managed().map { it.file.name }
        assertTrue(entries.none { "agent-pantheon" in it })
        assertTrue("agent-hermes.desktop" in entries)
    }
}
