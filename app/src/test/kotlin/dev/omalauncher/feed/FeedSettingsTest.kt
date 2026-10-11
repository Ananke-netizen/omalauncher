package dev.omalauncher.feed

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedSettingsTest {
    private val now = 1_791_680_000L

    private fun result(vararg items: FeedItem) = SourceResult(items.toList(), 1)

    @Test
    fun settingsRoundTrip() {
        val s =
            FeedSettings(
                enabled = false,
                hidden = setOf(Section.News),
                hn = HnMode.Front,
                hnCount = 30,
                xHandles = listOf("dhh", "omarchy"),
                xSort = XSort.Top,
                xReplies = true,
                xReposts = false,
                tech = setOf("lwn"),
                news = emptySet(),
                mute = listOf("crypto"),
                refreshMinutes = 0,
                read = ReadStyle.Hide,
            )
        assertEquals(s, FeedSettings.fromJson(JSONObject(s.toJson().toString())))
    }

    @Test
    fun missingOrUnknownValuesKeepTheirDefaults() {
        val s = FeedSettings.fromJson(JSONObject("""{"hn":"best","hidden":["x","weather"],"x":["@dhh","not a handle!","DHH"],"read":"burn"}"""))
        assertEquals(FeedSettings().hn, s.hn)
        assertEquals(setOf(Section.X), s.hidden)
        assertEquals(listOf("dhh"), s.xHandles)
        assertEquals(ReadStyle.Dim, s.read)
        assertEquals(FeedCatalog.defaultTech, s.tech)
        assertTrue(s.enabled)
    }

    @Test
    fun sourcesFollowTheShownSections() {
        val s = FeedSettings(hidden = setOf(Section.X, Section.News), tech = setOf("lwn", "ars"))
        assertEquals(listOf(FeedCatalog.HN_TOP, "ars", "lwn"), s.sources().map { it.id })
        assertEquals(listOf(FeedCatalog.HN_FRONT), s.copy(hn = HnMode.Front).sourcesOf(Section.Hn).map { it.id })
    }

    @Test
    fun hackerNewsKeepsItsOrderUpToTheCount() {
        val items = (1..60).map { FeedItem(FeedCatalog.HN_TOP, "story $it", "https://e.org/$it", time = it.toLong()) }
        val shown = FeedSettings(hnCount = 10).items(Section.Hn, mapOf(FeedCatalog.HN_TOP to SourceResult(items, 1)), emptySet(), now)
        assertEquals((1..10).map { "story $it" }, shown.map { it.title })
    }

    @Test
    fun xLeavesOutRepliesAndOrdersByTimeOrLikes() {
        val results =
            mapOf(
                "x:dhh" to result(
                    FeedItem("x:dhh", "old but loved", "https://x.com/1", now - 2 * FeedSettings.DAY, score = 9000),
                    FeedItem("x:dhh", "reply", "https://x.com/2", now - 10, reply = true),
                    FeedItem("x:dhh", "stranger", "https://x.com/5", now - 30, score = 70, via = "dhh"),
                    FeedItem("x:dhh", "omarchy post", "https://x.com/3", now - 20, score = 50, via = "dhh"),
                ),
                "x:omarchy" to result(
                    FeedItem("x:omarchy", "fresh", "https://x.com/4", now - 5, score = 10),
                    // Its own post, which dhh reposted: shown once.
                    FeedItem("x:omarchy", "omarchy post", "https://x.com/3", now - 20, score = 50),
                ),
            )
        val s = FeedSettings(xHandles = listOf("dhh", "omarchy"))
        fun titles(settings: FeedSettings) = settings.items(Section.X, results, emptySet(), now).map { it.title }
        assertEquals(listOf("fresh", "omarchy post", "stranger", "old but loved"), titles(s))
        // Without reposts the post still shows, from its author's own timeline.
        assertEquals(listOf("fresh", "omarchy post", "old but loved"), titles(s.copy(xReposts = false)))
        assertEquals(listOf("fresh", "reply", "omarchy post", "stranger", "old but loved"), titles(s.copy(xReplies = true)))
        // Top: the last day only, most liked first.
        assertEquals(listOf("stranger", "omarchy post", "fresh"), titles(s.copy(xSort = XSort.Top)))
    }

    @Test
    fun sitesMixNewestFirstWithoutOneSiteTakingOver() {
        val busy = (1..20).map { FeedItem("9to5google", "pixel $it", "https://9to5.org/$it", now - it) }
        val calm = listOf(FeedItem("lwn", "kernel", "https://lwn.net/1", now - 15), FeedItem("lwn", "undated", "https://lwn.net/2"))
        val s = FeedSettings(tech = setOf("9to5google", "lwn"))
        val shown = s.items(Section.Tech, mapOf("9to5google" to SourceResult(busy, 1), "lwn" to SourceResult(calm, 1)), emptySet(), now)
        assertEquals(FeedSettings.PER_SITE + 2, shown.size)
        assertEquals("pixel 1", shown.first().title)
        assertEquals("kernel", shown[FeedSettings.PER_SITE].title)
        assertEquals("undated", shown.last().title)
    }

    @Test
    fun mutedWordsMatchWholeWordsInAnyCase() {
        val items =
            listOf(
                FeedItem("ars", "New AI chips", "https://e.org/1"),
                FeedItem("ars", "He said so", "https://e.org/2"),
                FeedItem("ars", "C++ 26 is out", "https://e.org/3"),
                FeedItem("ars", "Rust in the kernel", "https://e.org/4"),
            )
        val s = FeedSettings(tech = setOf("ars"), mute = listOf("ai", "c++", " "))
        assertEquals(listOf("He said so", "Rust in the kernel"), s.items(Section.Tech, mapOf("ars" to SourceResult(items, 1)), emptySet(), now).map { it.title })
    }

    @Test
    fun hiddenReadStoriesAreLeftOut() {
        val items = listOf(FeedItem("ars", "read", "https://e.org/1"), FeedItem("ars", "new", "https://e.org/2"))
        val results = mapOf("ars" to SourceResult(items, 1))
        val s = FeedSettings(tech = setOf("ars"))
        assertEquals(2, s.items(Section.Tech, results, setOf("https://e.org/1"), now).size)
        assertEquals(listOf("new"), s.copy(read = ReadStyle.Hide).items(Section.Tech, results, setOf("https://e.org/1"), now).map { it.title })
    }

    @Test
    fun formatsAgesAndCounts() {
        assertEquals("", shortAgo(0, now))
        assertEquals("now", shortAgo(now - 59, now))
        assertEquals("5m", shortAgo(now - 300, now))
        assertEquals("3h", shortAgo(now - 3 * 3600 - 59, now))
        assertEquals("2d", shortAgo(now - 2 * FeedSettings.DAY, now))
        assertEquals("now", shortAgo(now + 100, now))
        assertEquals("999", shortCount(999))
        assertEquals("1.2k", shortCount(1234))
        assertEquals("10k", shortCount(9999))
        assertEquals("15k", shortCount(15_300))
        assertEquals("1.2m", shortCount(1_234_567))
    }

    @Test
    fun cleansTypedHandles() {
        assertEquals("dhh", FeedCatalog.handle("@dhh"))
        assertEquals("dhh", FeedCatalog.handle(" https://x.com/dhh "))
        assertEquals("The_Primeagen1", FeedCatalog.handle("The_Primeagen1"))
        assertNull(FeedCatalog.handle("not a handle"))
        assertNull(FeedCatalog.handle("@"))
        assertNull(FeedCatalog.handle("waytoolonghandle16"))
    }

    @Test
    fun catalogIdsAreUnique() {
        val ids = (FeedCatalog.tech + FeedCatalog.news).map { it.id } + listOf(FeedCatalog.HN_TOP, FeedCatalog.HN_FRONT)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(FeedCatalog.defaultTech.all { id -> FeedCatalog.tech.any { it.id == id } })
        assertTrue(FeedCatalog.defaultNews.all { id -> FeedCatalog.news.any { it.id == id } })
        assertTrue(FeedCatalog.defaultX.all { FeedCatalog.handle(it) == it })
    }
}
