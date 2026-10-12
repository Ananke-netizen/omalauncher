package dev.omalauncher.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.feed.FeedCatalog
import dev.omalauncher.feed.FeedItem
import dev.omalauncher.feed.FeedSettings
import dev.omalauncher.feed.FeedSource
import dev.omalauncher.feed.FeedStore
import dev.omalauncher.feed.ReadStyle
import dev.omalauncher.feed.Section
import dev.omalauncher.feed.shortAgo
import dev.omalauncher.feed.shortCount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How far the feed has slid in over home: 0 hidden, 1 open. Drags step it synchronously ([value]) so the
 * gesture code reads where it is without waiting for a frame; the panel draws [progress].
 */
@Stable
class FeedReveal {
    val progress = Animatable(0f)
    private var scope: CoroutineScope? = null
    private var position = 0f

    /** A finger is moving the feed; its fling settles it rather than turning pages. */
    var dragging = false
        private set

    val value get() = if (dragging) position else progress.value

    /** Open or opening: Back and Home close it. */
    val isOpen get() = progress.targetValue > 0f || dragging

    /** Animations need the composition's frame clock. */
    @Composable
    fun bind() {
        val s = rememberCoroutineScope()
        SideEffect { scope = s }
    }

    private fun launch(block: suspend CoroutineScope.() -> Unit) = (scope ?: CoroutineScope(Dispatchers.Main)).launch(block = block)

    /** Animates once composed; before that (Home pressed on the first-run screen) there's no frame clock, so it jumps. */
    private fun animate(target: Float) {
        dragging = false
        scope?.launch { progress.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow)) }
            ?: CoroutineScope(Dispatchers.Main).launch { progress.snapTo(target) }
    }

    fun open() = animate(1f)

    fun close() = animate(0f)

    fun toggle() = if (progress.targetValue > 0.5f) close() else open()

    fun dragBy(fraction: Float) {
        // Grabbed mid-animation: carry on from where it is.
        if (!dragging) position = progress.value
        dragging = true
        position = (position + fraction).coerceIn(0f, 1f)
        val to = position
        launch { progress.snapTo(to) }
    }

    /**
     * Whether the touch now on screen may start pulling the feed in: set by [feedPullGate] once it passes
     * touch slop, so only a level drag ([feedPullAllowed]) opens it, never a swipe up that leaned sideways.
     * True until a gate is attached, so the feed still works wherever none is.
     */
    var pullAllowed = true
        internal set

    /** Settles after a drag; [velocity] is in px/s, positive towards open. */
    fun settle(velocity: Float) {
        if (!dragging) return
        if (feedSettlesOpen(velocity, position)) open() else close()
    }
}

/**
 * Watches every touch over home, without consuming it, to decide at touch slop whether it may pull the feed
 * in ([FeedReveal.pullAllowed]). The pager can't tell: it only passes on the horizontal part of a drag.
 */
internal fun Modifier.feedPullGate(reveal: FeedReveal): Modifier =
    pointerInput(reveal) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            reveal.pullAllowed = false
            while (true) {
                val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                val d = c.position - down.position
                if (d.getDistance() > viewConfiguration.touchSlop) {
                    reveal.pullAllowed = feedPullAllowed(d.x, d.y)
                    break
                }
            }
        }
    }

/** +1 when the feed is to the left of home, -1 under a right-to-left layout, where the first page is on the right. */
@Composable
private fun feedSide() = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f

/**
 * Pulls the feed in when the home pages are dragged past the first one, as Launcher3 hands that overscroll
 * to its -1 screen overlay. Goes on the pager's parent: it gets what the pager couldn't use.
 */
@Composable
internal fun rememberFeedScroll(
    reveal: FeedReveal,
    enabled: Boolean,
): NestedScrollConnection {
    val width = LocalWindowInfo.current.containerSize.width.coerceAtLeast(1).toFloat()
    val side = feedSide()
    val on by rememberUpdatedState(enabled)
    return remember(reveal, width, side) {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Partly out: the drag moves the feed, either way, and the pages stay put.
                if (!on || source != NestedScrollSource.UserInput || reveal.value <= 0f) return Offset.Zero
                reveal.dragBy(side * available.x / width)
                return Offset(available.x, 0f)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!on || source != NestedScrollSource.UserInput || side * available.x <= 0f) return Offset.Zero
                // Only a level drag starts a pull; once it's started, the pre-scroll above carries it.
                if (!reveal.dragging && !reveal.pullAllowed) return Offset.Zero
                reveal.dragBy(side * available.x / width)
                return Offset(available.x, 0f)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!reveal.dragging) return Velocity.Zero
                reveal.settle(side * available.x)
                return available
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity {
                // In case the pre-fling never came, the feed mustn't stay halfway.
                reveal.settle(0f)
                return Velocity.Zero
            }
        }
    }
}

/**
 * The feed: Hacker News, X and tech and news sites in one scrolling, terminal-styled window that slides in
 * from the left of the first home page, like Pixel's Discover. Gapped and bordered like an Omarchy window,
 * in the theme's colors, so it changes with the theme. Drawn over the bar; swipe it back, or Back, to close.
 */
@Composable
fun FeedPanel(
    runtime: Runtime,
    state: LauncherState,
) {
    val reveal = state.feed
    reveal.bind()
    val store = runtime.feed
    val enabled = store.settings.enabled
    LaunchedEffect(enabled) { if (!enabled) reveal.close() }
    val shown by remember(reveal) { derivedStateOf { reveal.progress.value > 0f } }
    LaunchedEffect(shown) { if (shown) store.shown() }
    if (!shown) return

    val width = LocalWindowInfo.current.containerSize.width.coerceAtLeast(1).toFloat()
    val side = feedSide()
    Box(Modifier.fillMaxSize()) {
        // Home dims behind it; a tap there closes the feed.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = reveal.progress.value }
                .background(Color.Black.copy(alpha = 0.45f))
                .pointerInput(reveal) { detectTapGestures { reveal.close() } },
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = -side * (1f - reveal.progress.value) * size.width }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { dx -> reveal.dragBy(side * dx / width) },
                    onDragStopped = { velocity -> reveal.settle(side * velocity) },
                ).windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                // The home grid's gap, so the wallpaper frames it like a tiled window.
                .padding(HomeGap)
                // Opaque: home's widgets showing through made the text hard to read.
                .frame(2.dp, Palette.accent, Palette.background, radius = 16.dp),
        ) {
            FeedWindow(
                store,
                open = reveal.progress.targetValue >= 1f,
                onSettings = { state.feedSettingsOpen = true },
                onClose = reveal::close,
            )
        }
    }
}

/** The tabs: everything, or one section. */
private val TABS: List<Section?> = listOf(null) + Section.entries

/** The keys that pick [TABS], as numbered on screen. */
private val TAB_KEYS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five)

/** Rows each section shows on the "all" tab before a "more" link to its own tab. */
private const val DIGEST = 5

private sealed interface Entry {
    val key: String
}

private data class Head(
    val section: Section,
    val info: String,
    override val key: String,
) : Entry

private data class Story(
    val item: FeedItem,
    val section: Section,
    val rank: Int,
) : Entry {
    override val key get() = "${section.id}:${item.url}"
}

private data class More(
    val section: Section,
    val count: Int,
) : Entry {
    override val key get() = "more:${section.id}"
}

private data class Note(
    val text: String,
    val error: Boolean = false,
    override val key: String,
) : Entry

@Composable
private fun FeedWindow(
    store: FeedStore,
    open: Boolean,
    onSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val settings = store.settings
    var picked by remember { mutableStateOf<Section?>(null) }
    // A section hidden in settings while it was showing: back to all.
    val tab = picked?.takeIf { it !in settings.hidden }
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    val window = LocalWindowInfo.current.containerSize
    val panes = if (showsTwoPages(window)) 2 else 1
    val entries by remember(store, tab, now) {
        derivedStateOf { feedEntries(store.settings, store.results, store.read, store.loading, store.failures(), tab, now) }
    }
    val split = remember(entries, panes, tab) { splitPanes(entries, panes, tab) }
    // Each tab and pane keeps its scroll position.
    val lists = remember { mutableMapOf<String, LazyListState>() }
    val listsShown = split.indices.map { lists.getOrPut("${tab?.id}:$it") { LazyListState() } }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(open) { if (open) runCatching { focus.requestFocus() } }

    fun openUrl(item: FeedItem, url: String = item.url) {
        store.markRead(item)
        openLink(context, url)
    }

    fun pick(section: Section?) {
        // Picking the tab that's showing goes back to its top, like a terminal's `gg`.
        if (section == tab) scope.launch { listsShown.forEach { it.animateScrollToItem(0) } }
        picked = section
    }

    Column(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.R -> store.refresh(force = true)
                    Key.S -> onSettings()
                    Key.Q, Key.Escape -> onClose()
                    Key.J, Key.DirectionDown -> scope.launch { listsShown.first().animateScrollBy(240f) }
                    Key.K, Key.DirectionUp -> scope.launch { listsShown.first().animateScrollBy(-240f) }
                    Key.G -> scope.launch { listsShown.forEach { it.animateScrollToItem(0) } }
                    in TAB_KEYS -> {
                        // The numbers the tabs show, so a hidden section leaves its number unused.
                        val section = TABS[TAB_KEYS.indexOf(e.key)]
                        if (section == null || section !in settings.hidden) pick(section)
                    }
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        Header(store, now)
        Tabs(settings, tab, ::pick)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            split.forEachIndexed { i, pane ->
                if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.border))
                LazyColumn(
                    Modifier.weight(1f, fill = panes > 1).then(if (panes == 1) Modifier.widthIn(max = 760.dp) else Modifier),
                    state = listsShown[i],
                ) {
                    items(pane, key = { it.key }) { entry ->
                        when (entry) {
                            is Head -> SectionRule(entry)
                            is Story -> StoryRow(entry, settings, store.read, now, compact = tab == null, onOpen = ::openUrl)
                            is More -> MoreRow(entry) { pick(entry.section) }
                            is Note -> NoteRow(entry)
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
        KeyBar(onRefresh = { store.refresh(force = true) }, onSettings = onSettings, onClose = onClose)
    }
}

/** The window's title line: where you are, and what the feed is doing. */
@Composable
private fun Header(
    store: FeedStore,
    now: Long,
) {
    val spinner by produceState(0, store.loading) {
        while (store.loading) {
            delay(80)
            value = (value + 1) % SPINNER.length
        }
    }
    val status =
        when {
            store.loading -> "${SPINNER[spinner]} ${store.fetching - store.pending}/${store.fetching}"
            store.updatedAt() > 0 -> "updated ${shortAgo(store.updatedAt() / 1000, now).let { if (it == "now") "just now" else "$it ago" }}"
            else -> ""
        }
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText("~/feed", style = Mono.copy(color = Palette.accent, fontSize = TextSize.Title))
        Spacer(Modifier.weight(1f))
        BasicText(status, style = Mono.copy(color = if (store.loading) Palette.accent else Palette.muted))
    }
}

/** Braille spinner frames, as terminal tools draw them. */
private const val SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"

/** tmux's window list: number and name, the current one filled with the accent color. */
@Composable
private fun Tabs(
    settings: FeedSettings,
    tab: Section?,
    onPick: (Section?) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TABS.forEachIndexed { i, section ->
            if (section != null && section in settings.hidden) return@forEachIndexed
            val active = section == tab
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .clickable { onPick(section) }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = if (active) Palette.background else Palette.muted)) { append("${i + 1}") }
                        withStyle(SpanStyle(color = if (active) Palette.background else Palette.foreground)) { append(":${section?.id ?: "all"}") }
                    },
                    Modifier
                        .background(if (active) Palette.accent else Color.Transparent, LauncherStyle.shape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    style = Mono,
                )
            }
        }
    }
}

/** `── hacker news ──────── top 24h`: a section's name on a rule, in its color. */
@Composable
private fun SectionRule(head: Head) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(10.dp).height(1.dp).background(Palette.border))
        BasicText(" ${head.section.label} ", style = Mono.copy(color = sectionColor(head.section)))
        Box(Modifier.weight(1f).height(1.dp).background(Palette.border))
        if (head.info.isNotEmpty()) BasicText(" ${head.info}", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
    }
}

@Composable
private fun StoryRow(
    story: Story,
    settings: FeedSettings,
    read: Set<String>,
    now: Long,
    /** On the "all" tab: long posts are cut shorter. */
    compact: Boolean,
    onOpen: (FeedItem, String) -> Unit,
) {
    val item = story.item
    val seen = settings.read == ReadStyle.Dim && item.url in read
    val title = Mono.copy(color = if (seen) Palette.muted else Palette.foreground)
    val meta = Mono.copy(color = Palette.muted, fontSize = TextSize.Small)
    val ago = shortAgo(item.time, now)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(item, item.url) }
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        when (story.section) {
            Section.Hn -> {
                BasicText("${story.rank}".padStart(2), Modifier.width(28.dp), style = meta)
                Column(Modifier.weight(1f)) {
                    BasicText(item.title, style = title, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    BasicText(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = sectionColor(Section.Hn))) { append("▲${shortCount(item.score ?: 0)}") }
                            for (part in listOfNotNull(item.site, item.author?.let { "by $it" }, ago.ifEmpty { null })) append(" · $part")
                        },
                        style = meta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Section.X -> {
                Column(Modifier.weight(1f)) {
                    BasicText(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = sectionColor(Section.X))) { append("@${item.author ?: "?"}") }
                            if (ago.isNotEmpty()) append(" · $ago")
                            item.via?.let { append("  ↻ @$it") }
                        },
                        style = meta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    BasicText(item.title, style = title, maxLines = if (compact) 3 else 6, overflow = TextOverflow.Ellipsis)
                    // U+FE0E asks for the text heart: the color emoji ignores the theme.
                    val stats = listOfNotNull(item.score?.takeIf { it > 0 }?.let { "♥\uFE0E ${shortCount(it)}" }, item.media?.let { if (it == "video") "▶\uFE0E video" else "▣ $it" })
                    if (stats.isNotEmpty()) BasicText(stats.joinToString("  "), style = meta)
                }
            }
            Section.Tech, Section.News -> {
                val source = settings.sourcesOf(story.section).firstOrNull { it.id == item.source }
                BasicText(source?.tag.orEmpty(), Modifier.width(44.dp), style = Mono.copy(color = source?.let(::tagColor) ?: Palette.muted), maxLines = 1)
                BasicText(item.title, Modifier.weight(1f), style = title, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (item.discussion == null) BasicText(ago, Modifier.padding(start = 8.dp, end = 8.dp), style = meta)
            }
        }
        // Where people talk about it: Hacker News' thread, Lobsters' and Slashdot's comments.
        item.discussion?.let { thread ->
            val count = item.comments
            Column(horizontalAlignment = Alignment.End) {
                BasicText(
                    if (count != null) "[${shortCount(count)}]" else "[≡]",
                    Modifier
                        .semantics { contentDescription = if (count != null) "$count comments" else "comments" }
                        .clickable { onOpen(item, thread) }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    style = Mono.copy(color = Palette.accent),
                )
                if (story.section == Section.Tech || story.section == Section.News) BasicText(ago, Modifier.padding(end = 8.dp), style = meta)
            }
        }
    }
}

@Composable
private fun MoreRow(
    more: More,
    onClick: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)) {
        BasicText("[ +${more.count} more · ${more.section.label} › ]", style = Mono.copy(color = Palette.accent))
    }
}

@Composable
private fun NoteRow(note: Note) {
    BasicText(
        note.text,
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        style = Mono.copy(color = if (note.error) Palette.red else Palette.muted, fontSize = TextSize.Small),
    )
}

/** htop's function-key bar: each action's key, filled, then its name. Taps work too. */
@Composable
private fun KeyBar(
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        KeyAction("r", "refresh", onRefresh)
        KeyAction("s", "settings", onSettings)
        Spacer(Modifier.weight(1f))
        KeyAction("q", "close", onClose)
    }
}

@Composable
private fun KeyAction(
    key: String,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(" $key ", Modifier.background(Palette.accent, LauncherStyle.shape(4.dp)), style = Mono.copy(color = Palette.background))
        BasicText(" $label", style = Mono.copy(color = Palette.foreground))
    }
}

/** Each section's color from the theme, with the accent for themes that lack it. */
private fun sectionColor(section: Section): Color =
    when (section) {
        Section.X -> Palette.named("blue")
        Section.Hn -> Palette.named("orange") ?: Palette.named("yellow")
        Section.Tech -> Palette.named("cyan")
        Section.News -> Palette.named("magenta")
    } ?: Palette.accent

private val TAG_COLORS = listOf("cyan", "green", "magenta", "yellow", "blue", "red", "orange")

/** A site's tag color: fixed per site, cycling through the theme's terminal colors. */
private fun tagColor(source: FeedSource): Color {
    val sites = FeedCatalog.tech + FeedCatalog.news
    val i = sites.indexOfFirst { it.id == source.id }.coerceAtLeast(0)
    return Palette.named(TAG_COLORS[i % TAG_COLORS.size]) ?: Palette.accent
}

private fun Section.info(settings: FeedSettings) =
    when (this) {
        Section.X -> "${settings.xHandles.size} accounts · ${settings.xSort.label}"
        Section.Hn -> settings.hn.label
        Section.Tech -> "${settings.tech.size} sites"
        Section.News -> "${settings.news.size} sites"
    }

/** What the list shows for [tab]: every shown section's best few on "all", one section in full otherwise, then the status. */
private fun feedEntries(
    settings: FeedSettings,
    results: Map<String, dev.omalauncher.feed.SourceResult>,
    read: Set<String>,
    loading: Boolean,
    failures: List<Pair<FeedSource, String>>,
    tab: Section?,
    now: Long,
): List<Entry> {
    val sections = if (tab == null) settings.sections else listOf(tab)
    if (sections.isEmpty()) return listOf(Note("every section is hidden · s for settings", key = "none"))
    val out = mutableListOf<Entry>()
    var total = 0
    for (section in sections) {
        out += Head(section, section.info(settings), "head:${section.id}")
        val items = settings.items(section, results, read, now)
        total += items.size
        val shown = if (tab == null) items.take(DIGEST) else items
        shown.forEachIndexed { i, item -> out += Story(item, section, i + 1) }
        if (tab == null && items.size > DIGEST) out += More(section, items.size - DIGEST)
        if (items.isEmpty()) {
            val failed = failures.filter { it.first.section == section }
            out +=
                when {
                    loading -> Note("fetching…", key = "empty:${section.id}")
                    settings.sourcesOf(section).isEmpty() -> Note("no sources picked · s for settings", key = "empty:${section.id}")
                    failed.isNotEmpty() -> Note("✗ ${failed.first().second}", error = true, key = "empty:${section.id}")
                    else -> Note("nothing here right now", key = "empty:${section.id}")
                }
        }
    }
    val count = settings.sources().size
    out += Note(if (loading) "── fetching…" else "── end · $total stories · ${count - failures.size}/$count sources ok", key = "end")
    for ((source, why) in failures) out += Note("✗ ${source.label}: $why", error = true, key = "fail:${source.id}")
    if (Section.X in sections && settings.xHandles.isNotEmpty()) out += Note("x posts via api.fxtwitter.com, an unofficial bridge", key = "via")
    return out
}

/**
 * Two panes side by side (a foldable's inner screen), split at the hinge like the home pages: on "all" whole
 * sections go left or right, a single section continues from the left pane into the right one.
 */
private fun splitPanes(
    entries: List<Entry>,
    panes: Int,
    tab: Section?,
): List<List<Entry>> {
    if (panes < 2) return listOf(entries)
    val heads = entries.withIndex().filter { it.value is Head }.map { it.index }
    val cut =
        if (tab == null && heads.size > 1) {
            heads[(heads.size + 1) / 2]
        } else {
            val stories = entries.withIndex().filter { it.value is Story }.map { it.index }
            stories.getOrNull((stories.size + 1) / 2) ?: return listOf(entries)
        }
    return listOf(entries.subList(0, cut), entries.subList(cut, entries.size))
}

/** Opens [url] in whatever handles it: the browser, or the X app for posts. */
private fun openLink(
    context: Context,
    url: String,
) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
