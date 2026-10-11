package dev.omalauncher.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedParseTest {
    @Test
    fun readsRss2Items() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:atom="http://www.w3.org/2005/Atom">
              <channel>
                <title>Lobsters</title>
                <atom:link href="https://lobste.rs/rss" rel="self"/>
                <item>
                  <title><![CDATA[Writing a kernel in   Zig]]></title>
                  <link>https://example.org/zig-kernel</link>
                  <comments>https://lobste.rs/s/abc123</comments>
                  <pubDate>Sun, 11 Oct 2026 01:00:05 +0000</pubDate>
                </item>
                <item>
                  <title>It&amp;#8217;s AT&amp;amp;T</title>
                  <link>https://example.org/att</link>
                  <dc:date>2026-10-10T20:00:00Z</dc:date>
                </item>
                <item><title>No link, dropped</title></item>
              </channel>
            </rss>
            """.trimIndent()
        val items = FeedParse.rss(xml.toByteArray(), "lobsters")
        assertEquals(2, items.size)
        assertEquals(FeedItem("lobsters", "Writing a kernel in Zig", "https://example.org/zig-kernel", 1791680405, discussion = "https://lobste.rs/s/abc123"), items[0])
        assertEquals("It’s AT&T", items[1].title)
        assertEquals(1791662400, items[1].time)
    }

    @Test
    fun readsAtomEntriesByTheirAlternateLink() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>The Verge</title>
              <entry>
                <title type="html">Pixel 11 review</title>
                <link rel="replies" href="https://example.org/comments"/>
                <link rel="alternate" type="text/html" href="https://example.org/pixel-11"/>
                <published>2026-10-10T08:30:00-04:00</published>
                <source><title>Not this title</title></source>
              </entry>
              <entry>
                <title>Link without rel</title>
                <link href="https://example.org/plain"/>
                <updated>2026-10-09T00:00:00.000Z</updated>
              </entry>
            </feed>
            """.trimIndent()
        val items = FeedParse.rss(xml.toByteArray(), "verge")
        assertEquals(listOf("Pixel 11 review", "Link without rel"), items.map { it.title })
        assertEquals(listOf("https://example.org/pixel-11", "https://example.org/plain"), items.map { it.url })
        assertEquals(1791635400, items[0].time)
    }

    @Test
    fun readsRdfFeedsInTheirOwnEncoding() {
        // Slashdot: RSS 1.0, items beside the channel, declared as ISO-8859-1.
        val xml =
            """
            <?xml version="1.0" encoding="ISO-8859-1"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel rdf:about="https://slashdot.org/"><title>Slashdot</title></channel>
              <item rdf:about="https://slashdot.org/story/1">
                <title>Caf${'é'} servers</title>
                <link>https://slashdot.org/story/1</link>
                <dc:date>2026-10-10T20:00:00+00:00</dc:date>
              </item>
            </rdf:RDF>
            """.trimIndent()
        val items = FeedParse.rss(xml.toByteArray(Charsets.ISO_8859_1), "slashdot")
        assertEquals("Café servers", items.single().title)
        assertEquals(1791662400, items.single().time)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesEntityDeclarations() {
        val bomb = """<?xml version="1.0"?><!DOCTYPE rss [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;">]><rss><channel><item><title>&b;</title></item></channel></rss>"""
        FeedParse.rss(bomb.toByteArray(), "evil")
    }

    @Test
    fun cleansTitlesWithoutEatingAngleBrackets() {
        assertEquals("The <dialog> element", FeedParse.cleanText("The <dialog> element"))
        assertEquals("a — b", FeedParse.cleanText("a &mdash;\n  b"))
        assertEquals("😀", FeedParse.cleanText("&#x1F600;"))
        assertEquals("&unknown;", FeedParse.cleanText("&unknown;"))
    }

    @Test
    fun parsesFeedDates() {
        assertEquals(1791680405, FeedParse.parseDate("Sun, 11 Oct 2026 01:00:05 GMT"))
        assertEquals(1791680405, FeedParse.parseDate("Sat, 10 Oct 2026 21:00:05 EDT"))
        assertEquals(1791680405, FeedParse.parseDate("Sat, 10 Oct 2026 18:00:05 PDT"))
        assertEquals(1791680405, FeedParse.parseDate("11 Oct 2026 01:00:05 +0000"))
        assertEquals(1791162005, FeedParse.parseDate("Mon, 5 Oct 2026 01:00:05 +0000"))
        assertEquals(1791680405, FeedParse.parseDate("2026-10-11T01:00:05Z"))
        assertEquals(1791680405, FeedParse.parseDate("2026-10-11T03:00:05+02:00"))
        assertEquals(1791680405, FeedParse.parseDate("2026-10-11T01:00:05"))
        assertEquals(0, FeedParse.parseDate("yesterday"))
        assertEquals(0, FeedParse.parseDate(null))
    }

    @Test
    fun takesDomainsWithoutWww() {
        assertEquals("github.com", FeedParse.domain("https://www.github.com/omacom/omarchy"))
        assertEquals("rea.tools", FeedParse.domain("https://rea.tools/"))
        assertNull(FeedParse.domain("not a url"))
    }

    @Test
    fun readsHackerNewsStories() {
        val json =
            """
            {"hits":[
              {"objectID":"2","title":"Ask HN: What are you working on?","url":null,"points":120,"num_comments":300,"author":"pg","created_at_i":1791600000},
              {"objectID":"1","title":"REA – Reverse Engineer Anything","url":"https://www.rea.tools/","points":694,"num_comments":302,"author":"modinfo","created_at_i":1791592627},
              {"objectID":"3","title":"","url":"https://example.org"}
            ]}
            """.trimIndent()
        val items = FeedParse.hnAlgolia(json, FeedCatalog.HN_TOP)
        assertEquals(2, items.size)
        val ask = items[0]
        assertEquals("https://news.ycombinator.com/item?id=2", ask.url)
        assertEquals(ask.url, ask.discussion)
        assertNull(ask.site)
        assertEquals(FeedItem(FeedCatalog.HN_TOP, "REA – Reverse Engineer Anything", "https://www.rea.tools/", 1791592627, "modinfo", 694, 302, "https://news.ycombinator.com/item?id=1", "rea.tools"), items[1])
        // The front page's own ranking wins over the search's.
        assertEquals(listOf("1", "2"), FeedParse.hnAlgolia(json, FeedCatalog.HN_FRONT, listOf(1, 2)).map { it.discussion!!.substringAfter("id=") })
        assertEquals(listOf(50037949L, 50038634L), FeedParse.hnTopIds("[50037949,50038634]"))
    }

    @Test
    fun readsPostsFromX() {
        val json =
            """
            {"code":200,"results":[
              {"url":"https://x.com/omarchy/status/1","text":"Caps Lock will be left alone","author":{"screen_name":"omarchy"},"created_timestamp":1791677276,"likes":145,"replies":24,"reposted_by":{"screen_name":"dhh"},"replying_to":null,"media":{"all":[],"videos":[{}]}},
              {"url":"https://x.com/simonw/status/2","text":"Example prompt:","author":{"screen_name":"simonw"},"created_timestamp":1791600000,"likes":3,"replies":0,"replying_to":{"screen_name":"simonw","status":"1"},"media":{"photos":[{}]}},
              {"url":"https://x.com/dhh/status/3","text":"","author":{"screen_name":"dhh"},"created_timestamp":1791600001,"quote":{"text":"Omarchy 3","author":{"screen_name":"omarchy"}}},
              {"url":"https://x.com/dhh/status/4","text":"","author":{"screen_name":"dhh"}}
            ]}
            """.trimIndent()
        val posts = FeedParse.fxStatuses(json, "x:dhh")
        assertEquals(3, posts.size)
        val repost = posts[0]
        assertEquals("omarchy", repost.author)
        assertEquals("dhh", repost.via)
        assertEquals("video", repost.media)
        assertEquals(145, repost.score)
        assertFalse(repost.reply)
        assertTrue(posts[1].reply)
        assertEquals("photo", posts[1].media)
        assertEquals("↪ @omarchy: Omarchy 3", posts[2].title)
    }

    @Test(expected = java.io.IOException::class)
    fun failsWhenXSendsNoPosts() {
        FeedParse.fxStatuses("""{"code":404,"message":"User not found"}""", "x:nobody")
    }

    @Test
    fun cachedItemsRoundTrip() {
        val item = FeedItem("x:dhh", "post", "https://x.com/dhh/status/1", 5, "omarchy", 10, 2, null, null, "dhh", reply = true, media = "photo")
        assertEquals(item, FeedItem.fromJson(item.toJson()))
        val bare = FeedItem("ars", "title", "https://arstechnica.com/a")
        assertEquals(bare, FeedItem.fromJson(bare.toJson()))
    }
}
