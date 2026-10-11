package dev.omalauncher.feed

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/** The feed's sections, in the order they show. [id] is saved in settings: never rename. */
enum class Section(
    val id: String,
    val label: String,
) {
    X("x", "x"),
    Hn("hn", "hacker news"),
    Tech("tech", "tech"),
    News("news", "news"),
    ;

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** How a source is fetched and read. */
enum class Kind { Rss, HnTop, HnFront, X }

/**
 * One place the feed reads from: a site's RSS or Atom feed, Hacker News, or one X account.
 * [id] keys saved settings and the cache: never rename one. [tag] is the short name rows show it by.
 */
@Immutable
data class FeedSource(
    val id: String,
    val label: String,
    val tag: String,
    val section: Section,
    val url: String,
    val kind: Kind = Kind.Rss,
)

/**
 * A story, article or post. [time] is in epoch seconds (0 when the source didn't say). [score] is
 * Hacker News points or likes on X, [comments] its comments or replies, [discussion] where they are.
 */
@Immutable
data class FeedItem(
    val source: String,
    val title: String,
    val url: String,
    val time: Long = 0,
    val author: String? = null,
    val score: Int? = null,
    val comments: Int? = null,
    val discussion: String? = null,
    /** The link's domain, for stories that point elsewhere (Hacker News). */
    val site: String? = null,
    /** X: the account whose timeline reposted it. */
    val via: String? = null,
    /** X: it answers another post. */
    val reply: Boolean = false,
    /** X: what it carries besides text ("photo", "video"). */
    val media: String? = null,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("source", source)
            .put("title", title)
            .put("url", url)
            .put("time", time)
            .putOpt("author", author)
            .putOpt("score", score)
            .putOpt("comments", comments)
            .putOpt("discussion", discussion)
            .putOpt("site", site)
            .putOpt("via", via)
            .put("reply", reply)
            .putOpt("media", media)

    companion object {
        fun fromJson(o: JSONObject) =
            FeedItem(
                source = o.getString("source"),
                title = o.getString("title"),
                url = o.getString("url"),
                time = o.optLong("time"),
                author = o.optString("author").ifEmpty { null },
                score = if (o.has("score")) o.getInt("score") else null,
                comments = if (o.has("comments")) o.getInt("comments") else null,
                discussion = o.optString("discussion").ifEmpty { null },
                site = o.optString("site").ifEmpty { null },
                via = o.optString("via").ifEmpty { null },
                reply = o.optBoolean("reply"),
                media = o.optString("media").ifEmpty { null },
            )
    }
}

/** What one source last returned: its items from the last fetch that worked, and the error of the latest one, if it failed. */
@Immutable
data class SourceResult(
    val items: List<FeedItem>,
    /** Millis of the last fetch that worked; 0 if none has. */
    val fetchedAt: Long,
    val error: String? = null,
)

/** The sources the feed offers. Every list was checked to answer with a feed (October 2026). */
object FeedCatalog {
    const val HN_TOP = "hn:top24h"
    const val HN_FRONT = "hn:front"

    val hnTop = FeedSource(HN_TOP, "hacker news · top 24h", "hn", Section.Hn, "https://hn.algolia.com/api/v1/search", Kind.HnTop)
    val hnFront = FeedSource(HN_FRONT, "hacker news · front page", "hn", Section.Hn, "https://hacker-news.firebaseio.com/v0/topstories.json", Kind.HnFront)

    val tech =
        listOf(
            site("ars", "ars technica", "ars", Section.Tech, "https://feeds.arstechnica.com/arstechnica/index"),
            site("verge", "the verge", "vrg", Section.Tech, "https://www.theverge.com/rss/index.xml"),
            site("lwn", "lwn.net", "lwn", Section.Tech, "https://lwn.net/headlines/rss"),
            site("phoronix", "phoronix", "phx", Section.Tech, "https://www.phoronix.com/rss.php"),
            site("lobsters", "lobsters", "lob", Section.Tech, "https://lobste.rs/rss"),
            site("register", "the register", "reg", Section.Tech, "https://www.theregister.com/headlines.atom"),
            site("techcrunch", "techcrunch", "tc", Section.Tech, "https://techcrunch.com/feed/"),
            site("9to5google", "9to5google", "9to5", Section.Tech, "https://9to5google.com/feed/"),
            site("androidpolice", "android police", "apol", Section.Tech, "https://www.androidpolice.com/feed/"),
            site("404media", "404 media", "404", Section.Tech, "https://www.404media.co/rss/"),
            site("wired", "wired", "wrd", Section.Tech, "https://www.wired.com/feed/rss"),
            site("engadget", "engadget", "egt", Section.Tech, "https://www.engadget.com/rss.xml"),
            site("slashdot", "slashdot", "/.", Section.Tech, "https://rss.slashdot.org/Slashdot/slashdotMain"),
            site("tomshardware", "tom's hardware", "tom", Section.Tech, "https://www.tomshardware.com/feeds/all"),
        )

    val news =
        listOf(
            site("bbc", "bbc news", "bbc", Section.News, "https://feeds.bbci.co.uk/news/rss.xml"),
            site("npr", "npr", "npr", Section.News, "https://feeds.npr.org/1001/rss.xml"),
            site("guardian", "the guardian · world", "grd", Section.News, "https://www.theguardian.com/world/rss"),
            site("aljazeera", "al jazeera", "alj", Section.News, "https://www.aljazeera.com/xml/rss/all.xml"),
            site("nyt", "new york times", "nyt", Section.News, "https://rss.nytimes.com/services/xml/rss/nyt/HomePage.xml"),
            site("wapo", "washington post · world", "wapo", Section.News, "https://feeds.washingtonpost.com/rss/world"),
            site("cbs", "cbs news", "cbs", Section.News, "https://www.cbsnews.com/latest/rss/main"),
            site("fox", "fox news", "fox", Section.News, "https://moxie.foxnews.com/google-publisher/latest.xml"),
            site("bbctech", "bbc · technology", "bbct", Section.News, "https://feeds.bbci.co.uk/news/technology/rss.xml"),
        )

    val defaultTech = setOf("ars", "verge", "lwn", "phoronix", "lobsters", "register", "techcrunch", "9to5google")
    val defaultNews = setOf("bbc", "npr", "guardian", "aljazeera")

    /** Tech accounts on X to start from; the list is the user's to edit. */
    val defaultX = listOf("omarchy", "dhh", "karpathy", "simonw", "ThePrimeagen", "AnthropicAI", "OpenAI", "github", "AndroidDev", "linuxfoundation")

    /**
     * X has no free read API, and the Nitter bridges are gone (nitter.net answers 410, xcancel 451). FxTwitter's
     * open API (github.com/FxEmbed/FxEmbed) still serves an account's recent posts without a login.
     */
    const val X_API = "https://api.fxtwitter.com/2/profile/"

    fun x(handle: String) = FeedSource("x:${handle.lowercase()}", "@$handle", "@$handle", Section.X, X_API + handle + "/statuses", Kind.X)

    private fun site(
        id: String,
        label: String,
        tag: String,
        section: Section,
        url: String,
    ) = FeedSource(id, label, tag, section, url)

    /** Cleans what someone typed as an X account: "@dhh", "x.com/dhh" and "dhh" are all "dhh"; null if it can't be one. */
    fun handle(text: String): String? {
        val h = text.trim().substringAfterLast('/').removePrefix("@").trim()
        return h.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,15}")) }
    }
}
