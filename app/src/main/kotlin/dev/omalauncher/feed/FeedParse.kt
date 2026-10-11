package dev.omalauncher.feed

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URI
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/** Turns what the sources answer into [FeedItem]s. Plain JVM code (javax.xml, org.json), so tests run it as is. */
object FeedParse {
    /** Items of an RSS 2.0, RSS 1.0 (RDF) or Atom document, in the document's order. */
    fun rss(
        bytes: ByteArray,
        source: String,
    ): List<FeedItem> {
        // Entity declarations are how XML bombs expand; no feed needs one. Checked before the parser sees them.
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        require("<!ENTITY" !in head) { "feed declares entities" }
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                // The JVM's parser would fetch DTDs and external entities; Android's never does and rejects these flags.
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val atom = doc.documentElement.tagName.substringAfter(':') == "feed"
        val nodes = doc.getElementsByTagName(if (atom) "entry" else "item")
        return (0 until nodes.length).mapNotNull { i ->
            val e = nodes.item(i) as? Element ?: return@mapNotNull null
            val title = cleanText(e.child("title")?.textContent.orEmpty())
            val link =
                if (atom) {
                    e.children("link").firstOrNull { it.getAttribute("rel").ifEmpty { "alternate" } == "alternate" }?.getAttribute("href")
                } else {
                    e.child("link")?.textContent?.trim()?.ifEmpty { null }
                        ?: e.getAttribute("rdf:about").ifEmpty { null }
                        ?: e.child("guid")?.textContent?.trim()?.takeIf { it.startsWith("http") }
                }
            if (title.isEmpty() || link.isNullOrBlank() || !link.startsWith("http")) return@mapNotNull null
            val date = listOf("pubDate", "dc:date", "published", "updated").firstNotNullOfOrNull { e.child(it)?.textContent }
            // Lobsters and Slashdot point at their own discussion besides the story.
            val comments = e.child("comments")?.textContent?.trim()?.takeIf { it.startsWith("http") && it != link }
            FeedItem(source = source, title = title, url = link.trim(), time = parseDate(date), discussion = comments)
        }
    }

    /** Hacker News stories from an Algolia search; [order] (story ids) re-sorts them, since a search ranks by its own measure. */
    fun hnAlgolia(
        json: String,
        source: String,
        order: List<Long>? = null,
    ): List<FeedItem> {
        val hits = JSONObject(json).getJSONArray("hits")
        val items =
            (0 until hits.length()).mapNotNull { i ->
                val h = hits.optJSONObject(i) ?: return@mapNotNull null
                val id = h.optString("objectID").ifEmpty { return@mapNotNull null }
                val title = cleanText(h.optString("title")).ifEmpty { return@mapNotNull null }
                val thread = "https://news.ycombinator.com/item?id=$id"
                // Ask and Tell HN have no link of their own: the thread is the story.
                val url = h.optString("url").takeIf { it.startsWith("http") } ?: thread
                id to
                    FeedItem(
                        source = source,
                        title = title,
                        url = url,
                        time = h.optLong("created_at_i"),
                        author = h.optString("author").ifEmpty { null },
                        score = h.optInt("points"),
                        comments = h.optInt("num_comments"),
                        discussion = thread,
                        site = if (url == thread) null else domain(url),
                    )
            }
        if (order == null) return items.map { it.second }
        val rank = order.withIndex().associate { (i, id) -> id.toString() to i }
        return items.sortedBy { rank[it.first] ?: Int.MAX_VALUE }.map { it.second }
    }

    /** The story ids of Hacker News' front page, best first. */
    fun hnTopIds(json: String): List<Long> = JSONArray(json).let { a -> (0 until a.length()).map { a.getLong(it) } }

    /** An X account's recent posts, as FxTwitter's profile statuses API returns them. */
    fun fxStatuses(
        json: String,
        source: String,
    ): List<FeedItem> {
        val root = JSONObject(json)
        val results = root.optJSONArray("results") ?: throw java.io.IOException("x: ${root.optString("message").ifEmpty { "no posts" }}")
        return (0 until results.length()).mapNotNull { i ->
            val r = results.optJSONObject(i) ?: return@mapNotNull null
            val url = r.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val author = r.optJSONObject("author")?.optString("screen_name")?.ifEmpty { null }
            val quote = r.optJSONObject("quote")
            val media = r.optJSONObject("media")
            val kind =
                when {
                    (media?.optJSONArray("videos")?.length() ?: 0) > 0 -> "video"
                    (media?.optJSONArray("photos")?.length() ?: 0) > 0 -> "photo"
                    else -> null
                }
            // A post that's only a picture or a quote still needs a line to show.
            val text =
                cleanText(r.optString("text")).ifEmpty {
                    quote?.let { q -> "↪ @${q.optJSONObject("author")?.optString("screen_name")}: ${cleanText(q.optString("text"))}" } ?: kind?.let { "[$it]" } ?: ""
                }
            if (text.isEmpty()) return@mapNotNull null
            FeedItem(
                source = source,
                title = text,
                url = url,
                time = r.optLong("created_timestamp"),
                author = author,
                score = r.optInt("likes"),
                comments = r.optInt("replies"),
                via = r.optJSONObject("reposted_by")?.optString("screen_name")?.ifEmpty { null },
                reply = r.optJSONObject("replying_to") != null,
                media = kind,
            )
        }
    }

    /**
     * A title as plain text: entities decoded (feeds often escape them twice) and whitespace collapsed. Angle
     * brackets stay: on Hacker News "The <dialog> element" is the title, not markup.
     */
    fun cleanText(s: String): String {
        var t = s
        // Twice: "&amp;#8217;" is an escaped entity, and decoding it once leaves "&#8217;".
        repeat(2) { t = decodeEntities(t) }
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "hellip" to "…", "mdash" to "—", "ndash" to "–", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“")

    private fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let(::codePoint)
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.let(::codePoint)
                else -> NAMED[e]
            } ?: m.value
        }
    }

    private fun codePoint(c: Int) = if (Character.isValidCodePoint(c)) String(Character.toChars(c)) else null

    /** Zone names RFC 822 dates still use, which java.time's RFC 1123 parser doesn't read. */
    private val ZONES = mapOf("UT" to "+0000", "Z" to "+0000", "EST" to "-0500", "EDT" to "-0400", "CST" to "-0600", "CDT" to "-0500", "MST" to "-0700", "MDT" to "-0600", "PST" to "-0800", "PDT" to "-0700")

    /** Epoch seconds of an RSS (RFC 822) or Atom (ISO 8601) date; 0 if it can't be read. */
    fun parseDate(text: String?): Long {
        val s = text?.trim()?.ifEmpty { null } ?: return 0
        runCatching { return OffsetDateTime.parse(s).toEpochSecond() }
        runCatching { return LocalDateTime.parse(s).toEpochSecond(ZoneOffset.UTC) }
        val zone = s.substringAfterLast(' ')
        val rfc = ZONES[zone]?.let { s.dropLast(zone.length) + it } ?: s
        runCatching { return OffsetDateTime.parse(rfc, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() }
        return 0
    }

    /** "github.com" for "https://www.github.com/x"; null if [url] has no host. */
    fun domain(url: String): String? = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()

    private fun Element.children(tag: String): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName == tag }
    }

    /** The first direct child named [tag]: a nested element (an Atom entry's source, say) has titles of its own. */
    private fun Element.child(tag: String) = children(tag).firstOrNull()
}
