package dev.omalauncher.linux

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** A coding agent CLI, installed the way Omarchy's omarchy-default-agent does it: via mise — or, for [remote] agents, a wrapper that reaches a machine outside the guest. */
data class Agent(
    val bin: String,
    val name: String,
    val description: String,
    /** mise package spec; empty for [remote] agents, which mise never sees. */
    val pkg: String,
    /** Runs on another machine (our home server) instead of inside the guest. */
    val remote: Boolean = false,
    /** An Android app the bar hands the prompt to (kind `android`): no guest, no Linux needed. */
    val app: String = "",
    /** What starts it without a prompt, when that's more than [bin]. */
    val command: String = bin,
    /** Whether mise needs node to install it: npm packages run their postinstall scripts with it. */
    val node: Boolean = pkg.startsWith("npm:"),
) {
    val android get() = app.isNotEmpty()

    /** Its activity alias in the manifest (cursor-agent -> CursorAgentWindow), so Recents names its window after it. */
    val windowAlias get() = bin.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) } + "Window"
}

/** Omarchy's agent list (omarchy-default-agent), limited to ones mise can install on arm64. Hermes is ours: first installed wins the prompt bar, so she leads. */
val AGENTS =
    listOf(
        Agent("pantheon", "Pantheon", "Hermes in the Pantheon app, no Linux needed", "", app = PANTHEON_PACKAGE),
        Agent("hermes", "Hermes", "This house's assistant, on the home server", "", remote = true),
        Agent("claude", "Claude Code", "Anthropic's coding agent", "claude"),
        // mise installs OpenCode 2 from npm (@opencode/cli).
        Agent("opencode", "OpenCode", "Open source agent, any model provider", "opencode", node = true),
        Agent("codex", "Codex", "OpenAI's coding agent", "codex"),
        Agent("grok", "Grok", "xAI's coding agent", "npm:@xai-official/grok"),
        Agent("copilot", "GitHub Copilot", "GitHub's coding agent", "copilot"),
        Agent("crush", "Crush", "Charm's agent for the terminal", "crush"),
        Agent("cursor-agent", "Cursor CLI", "Cursor's agent for the terminal", "cursor-agent"),
        Agent("agy", "Antigravity", "Google's Gemini coding agent", "antigravity-cli"),
        Agent("pi", "Pi", "Minimal, extensible coding agent", "pi"),
        Agent("omp", "Oh My Pi", "Pi with tools and plugins built in", "github:can1357/oh-my-pi"),
        Agent("ori", "Ori", "OpenRouter's coding agent", "github:OpenRouterLabs/ori-releases", command = "ori code"),
        Agent(
            "muse",
            "Muse Code",
            "Meta's coding agent",
            // Meta's launcher fetches and updates the native binary for this platform.
            "http:muse[url=https://api.meta.ai/muse-launcher.sh,bin=muse,version_list_url=https://api.meta.ai/muse-code/channels/muse-stable,version_json_path=.version]",
        ),
        Agent("kimi", "Kimi", "Moonshot AI's coding agent", "npm:@moonshot-ai/kimi-code"),
    )

/**
 * The hermes wrapper installed into the guest's bound bin (see [AgentStore.wrapperDir]). Hermes
 * is not a mise package: she runs on the home server, and this reaches her over ssh. Provisioning
 * (key, host, Tailscale) is docs/HERMES.md; the wrapper says so plainly when ssh can't connect.
 */
val HERMES_WRAPPER =
    """
    #!/bin/bash
    # Hermes runs on the home server, not in here; this wrapper reaches her over ssh.
    # Set-up (phone key in ~/.ssh/authorized_keys, Tailscale up): docs/HERMES.md in Omalauncher.
    host=${'$'}{HERMES_SSH_HOST:-raspberrypi}
    if ! command -v ssh >/dev/null 2>&1; then
        echo "hermes: needs openssh in Arch Linux - install it with: pacman -Syu openssh" >&2
        exit 127
    fi
    if ! ssh -o BatchMode=yes -o ConnectTimeout=6 "${'$'}host" true 2>/dev/null; then
        echo "hermes: can't reach '${'$'}host' over ssh without a password." >&2
        echo "  Is Tailscale up, and is this phone's key in ${'$'}host:~/.ssh/authorized_keys? (docs/HERMES.md)" >&2
        echo "  Check by hand: ssh ${'$'}host" >&2
        exit 1
    fi
    # The smarts live on the server (~/scripts/omalauncher-ask.sh): it delivers the query to
    # Hermes' Bot Chat and pushes her answer to the Pantheon inbox. We fire it detached and
    # exit now — the window closes fast and the answer arrives on the phone. The detached
    # script owns the query file (the delivery deletes it when consumed); we never rm it here.
    # Single quotes: everything inside reaches the server's shell unexpanded (the guest's own
    # shell runs with HOME=/root, so the PATH must not expand here).
    [ "${'$'}{1:-}" = "-q" ] && shift   # agentInvocation's -q flag; the whole line is the query now
    printf '%s\n' "${'$'}*" | ssh "${'$'}host" 'export PATH=${'$'}HOME/.local/bin:${'$'}PATH
    ask="${'$'}HOME/scripts/omalauncher-ask.sh"
    if [ ! -x "${'$'}ask" ]; then
        echo "hermes: missing ${'$'}ask on the server (docs/HERMES.md)" >&2
        exit 1
    fi
    qf="${'$'}(mktemp /tmp/omalauncher-query.XXXXXX)"
    cat > "${'$'}qf"
    setsid "${'$'}ask" "${'$'}qf" >/dev/null 2>&1 &
    true' && echo "sent to Hermes — answer lands in Pantheon"
    """.trimIndent() + "\n"

/** Where the guest sees the launcher's own agent wrappers; bound from outside the rootfs. */
const val GUEST_WRAPPER_DIR = "/root/.local/share/omalauncher/bin"

/**
 * Whether a hold-to-talk ask runs without a terminal window. Remote agents answer
 * out-of-band (Hermes' reply lands in the Pantheon inbox), so a foot window would
 * only flash and close; local agents need their window to work in.
 */
fun askHeadless(agent: Agent, hold: Boolean): Boolean = agent.remote && hold

/** Pantheon, the Hermes app on this phone. Its ask provider admits only us, by signature. */
const val PANTHEON_PACKAGE = "com.axum.pocket"
const val PANTHEON_ASK_AUTHORITY = "com.axum.pocket.ask"
const val PANTHEON_TICKET_EXTRA = "ask_ticket"

/** The provider verb: Enter opens Pantheon with the reply streaming; hold-to-talk answers as a notification. */
fun pantheonVerb(hold: Boolean): String = if (hold) "ask_silent" else "ask_open"

/** Where a prompt goes when an Android agent can't take it: the hermes wrapper, if installed. */
fun androidFallback(installed: List<Agent>): Agent? = installed.firstOrNull { it.bin == "hermes" }

/** The agents the chip lists by default: Pantheon, Hermes and the picked one (settings can show them all). */
fun chipAgents(installed: List<Agent>, picked: Agent?, all: Boolean): List<Agent> =
    if (all) installed else installed.filter { it.android || it.bin == "hermes" || it.bin == picked?.bin }

/**
 * Brings mise itself up to date. Which backend a tool installs from is built into mise, and an old mise
 * breaks when a tool moves: OpenCode 2 left GitHub releases for npm, and mise 2026.10.8 still looked for
 * it on GitHub (404). Best effort, since the old mise may still manage.
 */
const val MISE_SELF_UPDATE = "{ mise self-update -y || true; }"

/** Adds node to the global config when an installed agent needs it but was installed before it did (OpenCode 1). */
val MISE_NODE_FOR_AGENTS =
    AGENTS.filter { it.node }.joinToString(" ", prefix = "if [ -z \"\$(mise ls -g node)\" ] && [ -n \"\$(mise ls -g ", postfix = ")\" ]; then mise use -g node@lts; fi") {
        shellQuote(it.pkg)
    }

/**
 * Starts [agentBin] with a first prompt; OpenCode takes it as --prompt, Antigravity and Copilot as -i (they
 * refuse a bare argument), the others as an argument.
 * Ori's agent is `ori code`, which stays open only with --interactive. Kimi takes a prompt only in
 * print mode, so the session it ran in is continued after the answer.
 * [readOnly], for reading something untrusted: Claude Code and Antigravity start in plan mode, where they
 * can read but not run commands or change files whatever the user's settings say. The others have no flag
 * for that we can rely on inside proot, and only have the prompt.
 */
fun agentInvocation(
    agentBin: String,
    prompt: String,
    readOnly: Boolean = false,
): String {
    // A prompt starting with a dash would be read as an option (claude --settings=... runs hooks); a space in front isn't.
    val quoted = shellQuote(if (prompt.startsWith("-")) " $prompt" else prompt)
    return when {
        agentBin == "opencode" -> "$agentBin --prompt $quoted"
        agentBin == "copilot" -> "$agentBin -i $quoted"
        agentBin == "claude" && readOnly -> "$agentBin --permission-mode plan $quoted"
        // Hermes runs on the home server behind the wrapper (see AgentStore); hermes chat takes the prompt with -q.
        agentBin == "hermes" -> "$agentBin -q $quoted"
        agentBin == "ori" -> "ori code --interactive --prompt $quoted"
        agentBin == "kimi" -> "kimi --prompt $quoted && kimi --continue"
        agentBin == "agy" && readOnly -> "$agentBin --mode plan -i $quoted"
        agentBin == "agy" -> "$agentBin -i $quoted"
        else -> "$agentBin $quoted"
    }
}

class AgentStore(
    private val proot: Proot,
    /** Called when the set of installed agents changed, so their desktop entries follow. */
    private val onChanged: () -> Unit,
    /** Whether an Android package is on the phone, for [Agent.android] agents. */
    private val appInstalled: (String) -> Boolean = { false },
) {
    private val mise get() = File(proot.home, ".local/bin/mise")
    private val shims get() = File(proot.home, ".local/share/mise/shims")

    /** The launcher's wrappers for [Agent.remote] agents, on the host side of the bind (see LauncherData.binDir). */
    private val wrapperDir get() = File(proot.filesDir, "bin")

    val states = mutableStateMapOf<String, JobState>()

    /** Shims are symlinks to a guest path (/root/.local/bin/mise), so don't follow them from the host. */
    fun isInstalled(agent: Agent) =
        if (agent.android) appInstalled(agent.app)
        else if (agent.remote) File(wrapperDir, agent.bin).isFile
        else Files.exists(File(shims, agent.bin).toPath(), LinkOption.NOFOLLOW_LINKS)

    /** The agent picked in the prompt bar, kept in the guest home where scripts can read it too. */
    private val preferredFile get() = File(proot.launcherConfig, "agent")
    private var preferred by mutableStateOf<String?>(null)

    /** Reads what's on disk; call off the main thread before the UI needs [default]. */
    fun load() {
        preferred = runCatching { preferredFile.readText().trim() }.getOrNull()
        refresh()
        promotePantheon()
    }

    /**
     * Pantheon becomes the bar's agent once, the first time it's seen on the phone (hermes over
     * ssh stays as the fallback). A later pick of another agent stands: the marker keeps this
     * from happening again.
     */
    private fun promotePantheon() {
        // The pick lives in the guest home; with no Linux there's none to write (and Pantheon,
        // first in the list, is the default anyway).
        if (!proot.home.isDirectory) return
        val pantheon = installed.firstOrNull { it.android } ?: return
        val marker = File(proot.launcherConfig, "agent-pantheon-promoted")
        if (marker.exists()) return
        choose(pantheon)
        runCatching { marker.writeText("1\n") }
    }

    /** Installed agents in Omarchy's order; re-read from disk after installs and removals. */
    var installed by mutableStateOf<List<Agent>>(emptyList())
        private set

    private fun refresh() {
        installed = AGENTS.filter { isInstalled(it) }
    }

    /** The agent the launcher opens for prompts and widget work: the picked one, else the first installed in Omarchy's order. */
    val default: Agent? get() = installed.firstOrNull { it.bin == preferred } ?: installed.firstOrNull()

    /** The agent for work that needs a terminal in the guest (editing widgets): never an Android app. */
    val terminalDefault: Agent? get() =
        installed.firstOrNull { it.bin == preferred && !it.android } ?: installed.firstOrNull { !it.android }

    fun choose(agent: Agent) {
        preferred = agent.bin
        // Never create the guest home from here: a missing one means no Linux, and a partial
        // rootfs is what a half-finished removal leaves (the launcher deletes it on start).
        if (!proot.home.isDirectory) return
        runCatching {
            preferredFile.parentFile?.mkdirs()
            preferredFile.writeText(agent.bin + "\n")
        }.onFailure { Log.w("agents", "cannot save the default agent", it) }
    }

    suspend fun install(agent: Agent) =
        run(agent, "installing") { installScript(agent) }.also { ok -> if (ok) onInstalled(agent) }

    /** Installs [agent]: a remote agent's wrapper into the bound bin (no mise), the rest via mise. */
    fun installScript(agent: Agent) =
        if (agent.android) error("${agent.name} is an Android app; install it on the phone")
        else if (agent.remote) remoteInstallScript(agent) else miseInstallScript(agent)

    /** Writes the wrapper into the directory proot binds from outside the rootfs, so it survives Linux removal. */
    private fun remoteInstallScript(agent: Agent) =
        when (agent.bin) {
            "hermes" ->
                "mkdir -p $GUEST_WRAPPER_DIR && cat > $GUEST_WRAPPER_DIR/hermes <<'OMALAUNCHER_WRAPPER'\n" +
                    HERMES_WRAPPER + "OMALAUNCHER_WRAPPER\nchmod +x $GUEST_WRAPPER_DIR/hermes"
            else -> error("no wrapper known for ${agent.bin}")
        }

    /** Installs [agent] with mise, bootstrapping mise itself if needed. */
    fun miseInstallScript(agent: Agent) =
        buildString {
            append(if (mise.exists()) "$MISE_SELF_UPDATE && " else "curl -fsSL https://mise.run | sh && ")
            if (agent.node) append("mise use -g node@lts && ")
            append("mise use -g ${shellQuote(agent.pkg)}")
            // Downloads are only needed during install; storage on phones is tight.
            append(" && mise cache clear && rm -rf ~/.local/share/mise/downloads")
        }

    /** Call after [installScript] succeeded: makes the agent show up in the drawer. */
    fun onInstalled(agent: Agent) {
        refresh()
        onChanged()
    }

    suspend fun remove(agent: Agent) =
        run(agent, "removing") {
            if (agent.remote) "rm -f $GUEST_WRAPPER_DIR/${agent.bin}"
            else "mise unuse -g ${shellQuote(agent.pkg)} && mise prune -y"
        }
            .also {
                refresh()
                onChanged()
            }

    /** Drawer entries for the installed agents, with their logos (see [bundledIcons]); each opens in a terminal in the home directory. */
    fun managed(): List<Managed> =
        installed.filterNot { it.android }.map { agent ->
            Managed.text(File(proot.applications, "agent-${agent.bin}.desktop"), Policy.Owned) {
                "[Desktop Entry]\nType=Application\nName=${agent.name}\nTerminal=true\nExec=${agent.command}\nIcon=${bundledIcon("agent-${agent.bin}")}\n"
            }
        } + codexConfig()

    /**
     * Codex starts a shared background server and reads its start time with `ps`; when that fails, Codex
     * refuses to start ("failed to read start time for pid-managed app server", rerun with --no-daemon).
     * Proot kills that server with its window anyway, so don't start it.
     *
     * Its sandbox for commands needs bubblewrap and real mounts (/proc/self/mountinfo) to isolate that
     * server's socket; under proot it can have neither (see assets/bwrap-shim.sh), and every command fails
     * with "cannot establish app-server socket mount isolation". Only full access skips the sandbox. Linux
     * here is already confined to the app's own data by Android.
     *
     * A system layer, so the user's ~/.codex/config.toml stays theirs and overrides both.
     */
    private fun codexConfig() =
        if (installed.none { it.bin == "codex" }) {
            emptyList()
        } else {
            listOf(
                Managed.text(File(proot.rootfs, "etc/codex/config.toml"), Policy.Owned) {
                    "# Written by the launcher.\nsandbox_mode = \"danger-full-access\"\n\n[features]\ndaemon_auto_start = false\n"
                },
            )
        }

    private suspend fun run(
        agent: Agent,
        action: String,
        script: () -> String,
    ): Boolean {
        val (ok, _) = proot.trackedJob(action, withContext(Dispatchers.IO) { script() }) { states[agent.bin] = it }
        Log.i("agents", "$action ${agent.bin}: ${if (ok) "ok" else "failed"}")
        return ok
    }
}
