package dev.omalauncher.feed

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.BuildConfig
import dev.omalauncher.upgrade.Versioned
import dev.omalauncher.upgrade.mapObjects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * The swipe-in feed's data: its settings, what each source last returned, and which stories were opened.
 * Nothing is fetched until the feed is first shown; after that each source keeps its last good items when a
 * fetch fails, and they're cached on disk so the feed opens with something to read, even offline.
 */
class FeedStore(
    private val context: Context,
) {
    private val prefs = context.getSharedPreferences("feed", Context.MODE_PRIVATE)
    private val cache = File(context.cacheDir, "feed.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var settings by mutableStateOf(readSettings())
        private set

    /** Source id -> its latest result. */
    val results = mutableStateMapOf<String, SourceResult>()

    /** URLs of opened stories, oldest first. */
    var read by mutableStateOf<Set<String>>(readMarks())
        private set

    /** Sources being fetched right now; 0 while idle. */
    var pending by mutableIntStateOf(0)
        private set

    /** Sources the current refresh fetches, for "3/15". */
    var fetching by mutableIntStateOf(0)
        private set

    val loading get() = pending > 0

    private var cacheRead = false
    private var job: Job? = null

    fun update(change: (FeedSettings) -> FeedSettings) {
        val next = change(settings)
        if (next == settings) return
        settings = next
        prefs.edit().putString(SETTINGS, Versioned.write(FeedSettings.VERSION, next.toJson())).apply()
        // A source just turned on has nothing yet: fetch only it, if the feed has been opened.
        if (cacheRead) refresh()
    }

    /** The feed came into view: show the cache, then refetch what's older than the refresh interval. */
    fun shown() {
        if (cacheRead) {
            refresh()
            return
        }
        cacheRead = true
        scope.launch {
            val cached = withContext(Dispatchers.IO) { readCache() }
            for ((id, r) in cached) if (id !in results) results[id] = r
            refresh()
        }
    }

    /** Fetches the shown sources that are due ([force]: all of them), several at a time, each showing as it arrives. */
    fun refresh(force: Boolean = false) {
        // Off means no requests, even from a settings change.
        if (!settings.enabled || job?.isActive == true) return
        val now = System.currentTimeMillis()
        val maxAge = settings.refreshMinutes * 60_000L
        val due =
            settings.sources().filter { s ->
                val r = results[s.id]
                force || r == null || r.fetchedAt == 0L || (maxAge > 0 && now - r.fetchedAt > maxAge)
            }
        if (due.isEmpty()) return
        fetching = due.size
        pending = due.size
        job =
            scope.launch {
                val gate = Semaphore(PARALLEL)
                due.map { s ->
                    launch {
                        val result =
                            gate.withPermit {
                                try {
                                    SourceResult(withContext(Dispatchers.IO) { fetch(s) }, System.currentTimeMillis())
                                } catch (e: Exception) {
                                    Log.w(TAG, "${s.id}: ${e.message}")
                                    (results[s.id] ?: SourceResult(emptyList(), 0)).copy(error = describe(s, e))
                                }
                            }
                        results[s.id] = result
                        pending--
                    }
                }.joinAll()
                val snapshot = results.toMap()
                withContext(Dispatchers.IO) { writeCache(snapshot) }
            }
        // Sources turned on while this ran have nothing yet.
        job?.invokeOnCompletion { if (settings.sources().any { it.id !in results }) scope.launch { refresh() } }
    }

    fun markRead(item: FeedItem) {
        if (item.url in read) return
        read = (read + item.url).let { if (it.size > MAX_READ) it.drop(it.size - MAX_READ).toSet() else it }
        prefs.edit().putString(READ, Versioned.write(1, JSONArray(read.toList()))).apply()
    }

    /** When the shown sources were last fetched, in millis; 0 if never. */
    fun updatedAt(): Long = settings.sources().maxOfOrNull { results[it.id]?.fetchedAt ?: 0L } ?: 0L

    /** Shown sources whose latest fetch failed, with why. */
    fun failures(): List<Pair<FeedSource, String>> = settings.sources().mapNotNull { s -> results[s.id]?.error?.let { s to it } }

    private suspend fun fetch(s: FeedSource): List<FeedItem> =
        when (s.kind) {
            Kind.Rss -> FeedParse.rss(get(s.url), s.id).take(MAX_PER_SOURCE)
            Kind.HnTop -> {
                val since = System.currentTimeMillis() / 1000 - FeedSettings.DAY
                // An empty query ranks by points: the day's top stories.
                FeedParse.hnAlgolia(text("${s.url}?tags=story&numericFilters=created_at_i%3E$since&hitsPerPage=50"), s.id)
            }
            Kind.HnFront -> {
                // The ranking is Hacker News' own; one search then fills in the stories' details.
                val ids = FeedParse.hnTopIds(text(s.url)).take(50)
                val tags = ids.joinToString(",") { "story_$it" }
                FeedParse.hnAlgolia(text("${FeedCatalog.hnTop.url}?tags=story,($tags)&hitsPerPage=50"), s.id, ids)
            }
            // FxTwitter answers 404 now and then for accounts that exist; a second try usually works.
            Kind.X -> retrying { FeedParse.fxStatuses(text(s.url), s.id) }
        }

    private suspend fun <T> retrying(block: () -> T): T {
        var last: Exception? = null
        for (wait in listOf(0L, 600L, 1500L)) {
            delay(wait)
            try {
                return block()
            } catch (e: IOException) {
                last = e
            }
        }
        throw last!!
    }

    private fun text(url: String) = String(get(url), Charsets.UTF_8)

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "Omalauncher/${BuildConfig.VERSION_NAME} (Android; feed)")
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) throw IOException("feed too large")
                }
                out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }

    private fun describe(
        s: FeedSource,
        e: Exception,
    ) = when {
        e is UnknownHostException -> "offline"
        e is SocketTimeoutException -> "timed out"
        // Even after retries; the account's last posts stay until the next refresh works.
        s.kind == Kind.X && e.message == "HTTP 404" -> "the bridge didn't answer, try r later"
        else -> (e.message ?: e.javaClass.simpleName).lowercase().take(60)
    }

    private fun readSettings(): FeedSettings =
        (Versioned.read(prefs.getString(SETTINGS, null), FeedSettings.VERSION) as? JSONObject)?.let {
            runCatching { FeedSettings.fromJson(it) }.getOrNull()
        } ?: FeedSettings()

    private fun readMarks(): Set<String> =
        (Versioned.read(prefs.getString(READ, null), 1) as? JSONArray)?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()

    private fun readCache(): Map<String, SourceResult> {
        if (!cache.exists()) return emptyMap()
        val root = Versioned.read(runCatching { cache.readText() }.getOrNull(), CACHE_VERSION) as? JSONObject ?: return emptyMap()
        return root.keys().asSequence().mapNotNull { id ->
            val o = root.optJSONObject(id) ?: return@mapNotNull null
            id to SourceResult(o.optJSONArray("items")?.mapObjects(FeedItem::fromJson).orEmpty(), o.optLong("at"))
        }.toMap()
    }

    private fun writeCache(results: Map<String, SourceResult>) {
        val root = JSONObject()
        for ((id, r) in results) {
            if (r.items.isEmpty()) continue
            root.put(id, JSONObject().put("at", r.fetchedAt).put("items", JSONArray(r.items.map { it.toJson() })))
        }
        runCatching {
            val tmp = File(cache.path + ".tmp")
            tmp.writeText(Versioned.write(CACHE_VERSION, root))
            tmp.renameTo(cache)
        }.onFailure { Log.w(TAG, "cache not written", it) }
    }

    private companion object {
        const val TAG = "feed"
        const val SETTINGS = "settings"
        const val READ = "read"
        const val CACHE_VERSION = 1
        const val PARALLEL = 6
        const val MAX_PER_SOURCE = 30
        const val MAX_READ = 600
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
