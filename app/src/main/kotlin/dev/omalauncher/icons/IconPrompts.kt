package dev.omalauncher.icons

import dev.omalauncher.linux.agentInvocation

/** How agents see their work, and keep it in the theme: they can't see the phone. */
private const val CRAFT =
    "Draw SVGs and fill them with the theme's colors through the {{ name }} placeholders README.md lists, so the icons " +
        "follow the theme when it changes. After saving each, run icon-check <file>: it prints problems and the paths of PNGs " +
        "of the icon as my phone draws it, in color and in the monochrome style; look at them."

/** Starts [agentBin] in the icons dir with a first prompt. */
private fun agentCommand(
    agentBin: String,
    prompt: String,
) = "mkdir -p ~/${IconStore.GUEST_DIR} && cd ~/${IconStore.GUEST_DIR} && ${agentInvocation(agentBin, prompt)}; exec bash"

/** A session about icons in general: the agent asks which apps and what style, then draws a set. */
fun newIconsCommand(agentBin: String) =
    agentCommand(
        agentBin,
        "Read README.md, and ${IconStore.APPS} for my apps, then ask me which apps' icons to draw and in what style; " +
            "suggest a few styles that fit my launcher's theme. $CRAFT Saved icons show on my phone within seconds.",
    )

/** A session about one app's icon: [stem] is the file name it takes, [label] the app's name. */
fun drawIconCommand(
    agentBin: String,
    stem: String,
    label: String,
): String {
    // An app names itself: keep its name to one plain line, so it reads as a name in the prompt.
    val name = label.filter { !it.isISOControl() }.take(60).replace("\"", "'")
    return agentCommand(
        agentBin,
        "Read README.md, then draw a new icon for my app \"$name\" and save it as $stem.svg here. First ask me what it should " +
            "look like, suggesting something that fits my launcher's theme and my other icons here. $CRAFT",
    )
}
