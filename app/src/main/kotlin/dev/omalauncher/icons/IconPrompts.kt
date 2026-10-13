package dev.omalauncher.icons

import dev.omalauncher.linux.agentInvocation
import dev.omalauncher.theme.StyleStore

/** How agents see their work, and keep it in the theme: they can't see the phone. */
private const val CRAFT =
    "Draw SVGs and fill them with the theme's colors through the {{ name }} placeholders README.md lists, so the icons " +
        "follow the theme when it changes. After saving an icon, run icon-check <file>: it prints problems and the paths of PNGs " +
        "of the icon as my phone draws it, in color and in the monochrome style; look at them."

/** Where agents find the apps to draw, and the name each one's file takes. */
private const val APPS = "~/${IconStore.GUEST_DIR}/${IconStore.APPS}"

/** How a pack goes into use. */
private const val SWITCH = "icons.pack = \"<the pack's folder name>\" in ~/${StyleStore.GUEST_DIR}/${StyleStore.FILE}"

/** Starts [agentBin] in [dir], under the guest home, with a first prompt. */
private fun agentCommand(
    agentBin: String,
    dir: String,
    prompt: String,
) = "mkdir -p ~/$dir && cd ~/$dir && ${agentInvocation(agentBin, prompt)}; exec bash"

/** An app or pack names itself: keep its name to one plain line, so it reads as a name in a prompt. */
private fun plain(name: String) = name.filter { !it.isISOControl() }.take(60).replace("\"", "'")

/** A new icon pack: the agent asks for a style, draws every app's icon and a backdrop, and offers to switch to it. */
fun newPackCommand(agentBin: String) =
    agentCommand(
        agentBin,
        IconStore.PACKS_GUEST_DIR,
        "Read README.md here, then make me a new icon pack for my launcher. First ask me what it should look like, suggesting a few " +
            "styles that fit my launcher's theme. Then make it a folder here with a pack.toml and an icon for every app in $APPS, " +
            "named as that file says, in one consistent style; a small script that writes the SVGs from one template is fine. " +
            "Add a backdrop for apps I install later. $CRAFT My phone lists the pack in settings › icons as soon as the folder has " +
            "an icon. When it's done, ask me whether to use it; that's $SWITCH.",
    )

/** Draws the icons a pack is missing: apps installed since it was made, or ones the agent skipped. */
fun fillPackCommand(
    agentBin: String,
    pack: IconPackInfo,
) = agentCommand(
    agentBin,
    "${IconStore.PACKS_GUEST_DIR}/${pack.id}",
    "This is my icon pack \"${plain(pack.label)}\". Read ../README.md and pack.toml, then compare the icons here with the apps " +
        "in $APPS and draw an icon for each app that has none here yet, in the same style as the ones here. $CRAFT Tell me which " +
        "you drew when you're done.",
)

/** Changes a pack: the agent asks what to change. */
fun editPackCommand(
    agentBin: String,
    pack: IconPackInfo,
) = agentCommand(
    agentBin,
    "${IconStore.PACKS_GUEST_DIR}/${pack.id}",
    "This is my icon pack \"${plain(pack.label)}\". Read ../README.md, pack.toml and a few of the icons here, then ask me what " +
        "to change about it. Keep the pack consistent: a change of style goes to every icon. $CRAFT",
)

/** A session about one app's icon: [stem] is the file name it takes, [label] the app's name. */
fun drawIconCommand(
    agentBin: String,
    stem: String,
    label: String,
) = agentCommand(
    agentBin,
    IconStore.GUEST_DIR,
    "Read README.md, then draw a new icon for my app \"${plain(label)}\" and save it as $stem.svg here. First ask me what it should " +
        "look like, suggesting something that fits my launcher's theme and my other icons. $CRAFT",
)
