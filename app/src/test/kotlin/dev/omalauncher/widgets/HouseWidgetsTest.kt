package dev.omalauncher.widgets

import dev.omalauncher.widgets.lang.Budget
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Env
import dev.omalauncher.widgets.lang.Global
import dev.omalauncher.widgets.lang.Host
import dev.omalauncher.widgets.lang.Json
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Program
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.WNode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * The home server's widgets (30-38) run against house.json as house-snapshot.py writes it:
 * every node and screen evaluates without an error, loaded, loading and with the file missing,
 * and their taps run the right `house` commands, quoted.
 */
class HouseWidgetsTest {
    private val zone = ZoneId.of("America/New_York")
    private var savedZone: TimeZone? = null

    // Monday 12 October 2026, 09:30 in New York.
    private val now = LocalDateTime.of(2026, 10, 12, 9, 30).atZone(zone).toEpochSecond().toDouble()

    private fun at(
        day: Int,
        hour: Int,
    ) = LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toEpochSecond()

    /** The shape of house.json, with made-up values. */
    private val house by lazy {
        """
        {"generated": ${now.toLong() - 60},
         "events": [
           {"title": "Done already", "start": ${at(12, 7)}, "end": ${at(12, 8)}, "allday": false, "day": "2026-10-12", "court": false},
           {"title": "Calendar call", "start": ${at(14, 9)}, "end": ${at(14, 10)}, "allday": false, "day": "2026-10-14", "court": true},
           {"title": "Class", "start": ${at(15, 0)}, "end": ${at(16, 0)}, "allday": true, "day": "2026-10-15", "court": false}],
         "todos": [
           {"uid": "a'1@x", "title": "File the reply", "due": ${at(10, 12)}, "priority": 1, "status": "NEEDS-ACTION", "overdue": true, "today": false},
           {"uid": "b2", "title": "Call back", "due": null, "priority": 0, "status": "NEEDS-ACTION", "overdue": false, "today": false}],
         "todo_counts": {"open": 2, "overdue": 1, "today": 0},
         "health": {"ok": true, "overall": "warn", "age_s": 40, "failing": ["glean.service"],
                    "evo": {"mode": "llm", "ready": true, "version": "x", "tps_24h": 41.7}},
         "inbox": {"ok": true, "unread": 3, "last": {"text": "The build is green.", "ts": ${now - 600}},
                   "side": {"legal": 2, "class": 1, "build": 0}},
         "brief": {"text": "Good morning.\n\nTwo things today.\nThird line", "ts": ${now.toLong() - 3600}},
         "chat_model": "halogen", "proton_days": 12}
        """.trimIndent()
    }

    @Before fun pinZone() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }

    @After fun restoreZone() = TimeZone.setDefault(savedZone)

    private class TestHost(
        val program: Program,
        sources: Map<String, Value>,
    ) : Host {
        val slots = Array<Value>(program.globals.size) { Null }
        val actions = ArrayList<String>()
        val problems = ArrayList<String>()

        init {
            program.globals.forEachIndexed { i, g ->
                when (g) {
                    is Global.State -> slots[i] = g.init(Env.root(this))
                    is Global.Source -> slots[i] = sources[g.name] ?: Null
                    else -> {}
                }
            }
        }

        override fun read(slot: Int): Value = (program.globals[slot] as? Global.Let)?.value?.invoke(Env.root(this)) ?: slots[slot]

        override fun write(
            slot: Int,
            value: Value,
        ) {
            slots[slot] = value
        }

        fun state(name: String) = slots[program.globals.indexOfFirst { it.name == name }]

        override fun theme(name: String) = 0xff7aa2f7.toInt()

        override fun time() = 0.0

        override fun act(
            name: String,
            args: List<Value>,
        ) {
            actions += "$name ${args.joinToString()}"
        }

        override fun runInto(
            command: String,
            slot: Int,
        ) {
            actions += "run $command"
        }

        override fun report(message: String) {
            problems += message
        }

        override val budget = Budget()
    }

    /** One element as it would be on screen, with the frame its handlers run in. */
    private class Shown(
        val el: WNode.Element,
        val env: Env,
        val text: String?,
    )

    /** Evaluates every node the way the renderer would reach it: taken branches, every loop item, every component. */
    private fun walk(
        nodes: List<WNode>,
        env: Env,
        out: MutableList<Shown>,
    ) {
        for (n in nodes) {
            when (n) {
                is WNode.Element -> {
                    val props = n.props.mapValues { (_, ev) -> ev(env) }
                    out += Shown(n, env, (props["text"] as? Str)?.v ?: props["text"]?.toString())
                    if (n.kind == "Canvas") walk(n.children, env.child(Num(300.0), Num(108.0)), out) else walk(n.children, env, out)
                }
                is WNode.If -> walk(if (n.cond(env).truthy) n.then else n.orElse, env, out)
                is WNode.For -> {
                    val items = (n.list(env) as? dev.omalauncher.widgets.lang.Lst)?.items.orEmpty()
                    items.forEachIndexed { i, v -> walk(n.body, env.child(v, Num(i.toDouble())), out) }
                }
                is WNode.Lets -> walk(n.children, env.lets(n.values), out)
                is WNode.Use -> walk(n.body, Env(env.host, Env.root(env.host), Array(n.args.size) { i -> n.args[i](env) }), out)
                is WNode.Draw -> {
                    n.args.forEach { it(env) }
                    n.named.values.forEach { it(env) }
                    n.children?.let { walk(it, env, out) }
                }
            }
        }
    }

    private inner class Run(
        file: String,
        h: Value,
    ) {
        val program = Compiler.compile(File("src/main/assets/widgets/$file").readText())
        val host = TestHost(program, mapOf("h" to h, "now" to Num(now), "sync" to Null))

        fun tile(): List<Shown> = ArrayList<Shown>().also { walk(program.root, Env.root(host), it) }

        fun screen(): List<Shown> = ArrayList<Shown>().also { walk(program.screen.orEmpty(), Env.root(host), it) }

        fun texts() = tile().mapNotNull { it.text }

        fun tap(text: String, screen: Boolean = false, value: String? = null) {
            val all = if (screen) screen() else tile()
            // A typed value goes to the Input; a tap to the element showing [text], or (for "Box") the first Box.
            val shown =
                when {
                    value != null -> all.first { "onSubmit" in it.el.handlers }
                    text == "Box" -> all.first { it.el.kind == "Box" && "onTap" in it.el.handlers }
                    else -> all.first { it.text == text }
                }
            val handler = shown.el.handlers[if (value != null) "onSubmit" else "onTap"] ?: error("'$text' has no handler")
            handler.run(shown.env, if (value != null) listOf(Str(value)) else emptyList())
        }
    }

    private val files = listOf("30-calendar.owl", "31-todo.owl", "32-house.owl", "33-brain.owl", "34-hermes.owl", "35-capture.owl", "36-brief.owl", "37-timer.owl", "38-debate.owl")

    @Test fun everyNodeEvaluatesLoadedLoadingAndMissing() {
        // Loaded; still loading (null); the file missing (the empty string a file source reads as).
        for (h in listOf(Json.parseOrText(house), Null, Str(""))) {
            for (f in files) {
                val run = Run(f, h)
                run.tile()
                run.screen()
                assertTrue("$f: ${run.host.problems}", run.host.problems.isEmpty())
            }
        }
    }

    @Test fun beforeTheFirstSyncTheTilesSaySo() {
        for ((f, line) in listOf("30-calendar.owl" to "…", "31-todo.owl" to "…", "34-hermes.owl" to "…", "36-brief.owl" to "…")) {
            assertTrue(f, line in Run(f, Str("")).texts())
        }
    }

    @Test fun calendarShowsWhatsAheadWithCourtCountdowns() {
        val run = Run("30-calendar.owl", Json.parseOrText(house))
        val texts = run.texts()
        assertTrue(texts.toString(), "october" in texts && "2 ahead" in texts)
        assertTrue("Calendar call" in texts && "Class" in texts && "Done already" !in texts)
        assertTrue("court dates count down", "2d" in texts)
        assertTrue("wed 14 · 09:00" in texts && "thu 15" in texts)
        // October 2026 starts on a Thursday: three blank cells in a Monday-first grid; 31 days.
        assertEquals(Num(3.0), run.host.read(run.program.globals.indexOfFirst { it.name == "lead" }))
        assertEquals(31, (run.host.read(run.program.globals.indexOfFirst { it.name == "days" }) as dev.omalauncher.widgets.lang.Lst).items.size)
        assertTrue(run.screen().any { it.text == "in 2d" })
    }

    @Test fun todoTicksUndoesAndAddsThroughHouseQuoted() {
        val run = Run("31-todo.owl", Json.parseOrText(house))
        assertTrue(run.texts().toString(), "2 open · 1 late" in run.texts() && "File the reply" in run.texts())
        run.tap("☐")
        assertEquals("run house done 'a'\\''1@x'", run.host.actions.last())
        assertFalse("ticked hides it", "File the reply" in run.texts())
        assertTrue(run.texts().any { it.startsWith("undo ") })
        run.tap("undo 6")
        assertEquals("run house undo 'a'\\''1@x'", run.host.actions.last())
        assertTrue("File the reply" in run.texts())
        run.tap("", screen = true, value = "call Bob's office 2026-10-14")
        assertEquals("run house add 'call Bob'\\''s office' 2026-10-14", run.host.actions.last())
        run.tap("", screen = true, value = "  no date here ")
        assertEquals("run house add 'no date here'", run.host.actions.last())
        run.tap("", screen = true, value = "   ")
        assertEquals("run true", run.host.actions.last())
    }

    @Test fun brainSwitchesOnlyOnTheSecondTap() {
        val run = Run("33-brain.owl", Json.parseOrText(house))
        assertTrue(run.texts().toString(), "halogen" in run.texts())
        assertTrue("the current lane does nothing", run.tile().first { it.text == "halogen" }.el.handlers.isEmpty())
        run.tap("glm")
        assertTrue("armed, nothing run", run.host.actions.isEmpty())
        assertTrue(run.texts().toString(), "sure? 5" in run.texts())
        run.tap("sure? 5")
        assertEquals(listOf("run house brain glm"), run.host.actions)
        assertTrue(run.texts().any { it.startsWith("switching to glm") })
    }

    @Test fun hermesShowsTheAnswerAndOnlyCountsForTheSideChats() {
        val texts = Run("34-hermes.owl", Json.parseOrText(house)).texts()
        assertTrue(texts.toString(), "3" in texts && "The build is green." in texts && "legal 2 · class 1 · build 0" in texts)
    }

    @Test fun houseShowsHealthModelAndFailures() {
        val texts = Run("32-house.owl", Json.parseOrText(house)).texts()
        assertTrue(texts.toString(), "pi warn" in texts && "evo llm · 42 tok/s" in texts && "halogen · proton 12d" in texts && "glean.service" in texts)
    }

    @Test fun briefShowsItsLinesOrSaysThereIsNone() {
        assertTrue(Run("36-brief.owl", Json.parseOrText(house)).texts().containsAll(listOf("Good morning.", "Two things today.")))
        assertTrue("no brief yet" in Run("36-brief.owl", Json.parseOrText(house.replace(Regex("\"brief\": \\{[^}]*\\}"), "\"brief\": null"))).texts())
    }

    @Test fun captureQuotesWhatsTyped() {
        val run = Run("35-capture.owl", Null)
        run.tap("", value = "it's \$(rm -rf ~) `x`")
        assertEquals("run house capture 'it'\\''s \$(rm -rf ~) `x`'", run.host.actions.last())
        assertTrue(run.texts().any { it.startsWith("✓ saved") })
    }

    @Test fun debateClockStartsPausesAndResumes() {
        val run = Run("38-debate.owl", Null)
        assertTrue("2:00" in run.texts())
        run.tap("Box")
        assertEquals(Num(now + 120), run.host.state("endsAt"))
        run.host.slots[run.program.globals.indexOfFirst { it.name == "now" }] = Num(now + 50)
        assertTrue(run.texts().toString(), "1:10" in run.texts())
        run.tap("Box")
        assertEquals(Num(0.0), run.host.state("endsAt"))
        assertEquals(Num(70.0), run.host.state("paused"))
        run.tap("Box")
        assertEquals(Num(now + 50 + 70), run.host.state("endsAt"))
        assertEquals(Num(0.0), run.host.state("paused"))
    }
}
