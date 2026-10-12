package dev.omalauncher.feed

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/** Which Hacker News list the feed shows. [id] is saved: never rename. */
enum class HnMode(
    val id: String,
    val label: String,
) {
    Top("top24h", "top 24h"),
    Front("front", "front page"),
}

/** How posts from X are ordered. [id] is saved: never rename. */
enum class XSort(
    val id: String,
    val label: String,
) {
    Latest("latest", "latest"),
    Top("top24h", "top 24h"),
}

/** What happens to a story once it was opened. [id] is saved: never rename. */
enum class ReadStyle(
    val id: String,
    val label: String,
) {
    Dim("dim", "dim"),
    Hide("hide", "hide"),
    Keep("keep", "keep"),
}

/** The feed's options, saved by [FeedStore] as one versioned document. */
@Immutable
data class FeedSettings(
    /** The feed slides in from the first home page; off, that swipe does nothing. */
    val enabled: Boolean = true,
    val hidden: Set<Section> = emptySet(),
    val hn: HnMode = HnMode.Top,
    val hnCount: Int = 50,
    val xHandles: List<String> = FeedCatalog.defaultX,
    val xSort: XSort = XSort.Latest,
    val xReplies: Boolean = false,
    val xReposts: Boolean = true,
    /** Ids of the [FeedCatalog.tech] and [FeedCatalog.news] sites to read. */
    val tech: Set<String> = FeedCatalog.defaultTech,
    val news: Set<String> = FeedCatalog.defaultNews,
    /** Words and phrases whose stories are left out, matched as whole words in any case. */
    val mute: List<String> = emptyList(),
    /** Opening the feed refetches sources older than this; 0 refreshes only when asked. */
    val refreshMinutes: Int = 30,
    val read: ReadStyle = ReadStyle.Dim,
) {
    val sections get() = Section.entries.filter { it !in hidden }

    /** Every source the shown sections read. */
    fun sources(): List<FeedSource> = sections.flatMap(::sourcesOf)

    fun sourcesOf(section: Section): List<FeedSource> =
        when (section) {
            Section.X -> xHandles.map(FeedCatalog::x)
            Section.Hn -> listOf(if (hn == HnMode.Top) FeedCatalog.hnTop else FeedCatalog.hnFront)
            Section.Tech -> FeedCatalog.tech.filter { it.id in tech }
            Section.News -> FeedCatalog.news.filter { it.id in news }
        }

    /**
     * What [section] shows, best first: Hacker News in its own order, X newest or most liked first, sites
     * newest first with at most [PER_SITE] each so a busy site can't fill the section. Muted words, and
     * read stories if they're hidden, are left out.
     */
    fun items(
        section: Section,
        results: Map<String, SourceResult>,
        read: Set<String>,
        now: Long,
    ): List<FeedItem> {
        val muted = mute.mapNotNull { w -> w.trim().ifEmpty { null }?.let { Regex("(?<![\\p{L}\\p{N}])${Regex.escape(it)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE) } }
        val shown: (FeedItem) -> Boolean = { item -> muted.none { it.containsMatchIn(item.title) } && !(this.read == ReadStyle.Hide && item.url in read) }
        val per = sourcesOf(section).map { results[it.id]?.items.orEmpty() }
        val (items, limit) =
            when (section) {
                Section.Hn -> per.flatten() to hnCount
                Section.X -> {
                    val posts = per.flatten().filter { (xReplies || !it.reply) && (xReposts || it.via == null) }
                    val sorted = if (xSort == XSort.Top) posts.filter { it.time >= now - DAY }.sortedByDescending { it.score ?: 0 } else posts.sortedByDescending { it.time }
                    sorted to MAX_POSTS
                }
                Section.Tech, Section.News -> per.flatMap { it.take(PER_SITE) }.sortedByDescending { it.time } to MAX_STORIES
            }
        return items.filter(shown).distinctBy { it.url }.take(limit)
    }

    fun toJson(): JSONObject =
        JSONObject()
            .put("enabled", enabled)
            .put("hidden", JSONArray(hidden.map { it.id }))
            .put("hn", hn.id)
            .put("hnCount", hnCount)
            .put("x", JSONArray(xHandles))
            .put("xSort", xSort.id)
            .put("xReplies", xReplies)
            .put("xReposts", xReposts)
            .put("tech", JSONArray(tech.toList()))
            .put("news", JSONArray(news.toList()))
            .put("newsOffered", JSONArray(FeedCatalog.addedNews.toList()))
            .put("mute", JSONArray(mute))
            .put("refresh", refreshMinutes)
            .put("read", read.id)

    companion object {
        /** The saved document's version (see Versioned). */
        const val VERSION = 1
        const val DAY = 24 * 60 * 60L
        const val PER_SITE = 12
        const val MAX_POSTS = 50
        const val MAX_STORIES = 60
        val REFRESH_MINUTES = listOf(15, 30, 60, 0)
        val HN_COUNTS = listOf(10, 30, 50)

        /** Reads saved settings; anything missing or unknown keeps its default, so older and newer documents both load. */
        fun fromJson(o: JSONObject): FeedSettings {
            val d = FeedSettings()

            fun strings(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifEmpty { null } } }
            return FeedSettings(
                enabled = o.optBoolean("enabled", d.enabled),
                hidden = strings("hidden")?.mapNotNull(Section::of)?.toSet() ?: d.hidden,
                hn = HnMode.entries.firstOrNull { it.id == o.optString("hn") } ?: d.hn,
                hnCount = o.optInt("hnCount", d.hnCount).coerceIn(1, 50),
                xHandles = strings("x")?.mapNotNull(FeedCatalog::handle)?.distinctBy { it.lowercase() } ?: d.xHandles,
                xSort = XSort.entries.firstOrNull { it.id == o.optString("xSort") } ?: d.xSort,
                xReplies = o.optBoolean("xReplies", d.xReplies),
                xReposts = o.optBoolean("xReposts", d.xReposts),
                tech = strings("tech")?.toSet() ?: d.tech,
                // A default added since this was saved is switched on once; turned off later, it stays off.
                news = strings("news")?.toSet()?.let { it + (FeedCatalog.addedNews - strings("newsOffered").orEmpty().toSet()) } ?: d.news,
                mute = strings("mute") ?: d.mute,
                refreshMinutes = o.optInt("refresh", d.refreshMinutes).coerceAtLeast(0),
                read = ReadStyle.entries.firstOrNull { it.id == o.optString("read") } ?: d.read,
            )
        }
    }
}

/** How long ago [time] (epoch seconds) was, terminal-short: "now", "5m", "3h", "2d"; "" when unknown. */
fun shortAgo(
    time: Long,
    now: Long,
): String {
    if (time <= 0) return ""
    val s = (now - time).coerceAtLeast(0)
    return when {
        s < 60 -> "now"
        s < 3600 -> "${s / 60}m"
        s < FeedSettings.DAY -> "${s / 3600}h"
        else -> "${s / FeedSettings.DAY}d"
    }
}

/** A count the way terminals print sizes: 999, 1.2k, 15k, 1.2m. */
fun shortCount(n: Int): String =
    when {
        n < 1000 -> "$n"
        n < 10_000 -> "%.1fk".format(java.util.Locale.ROOT, n / 1000f).replace(".0k", "k")
        n < 1_000_000 -> "${n / 1000}k"
        else -> "%.1fm".format(java.util.Locale.ROOT, n / 1_000_000f).replace(".0m", "m")
    }
