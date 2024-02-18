package eu.kanade.tachiyomi.animeextension.en.anify

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.anify.extractors.VidstackExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Anify : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name: String = "Anify"

    override val baseUrl: String = "https://anify.to"

    override val lang: String = "en"

    override val supportsLatest: Boolean = true

    override val client: OkHttpClient = network.client

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeSelector(): String = "div#popular-tab-pane > div.row > div.col-md-6"

    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animelist/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val langPref = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)
        return SAnime.create().apply {
            setUrlWithoutDomain(element.select("div.animeinfo > a").attr("href"))
            thumbnail_url = element.select("img").attr("data-src")
            title = element.select("span.animename").attr(langPref.toString())
        }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesSelector(): String = "div#ongoing-tab-pane > div.row > div.col-md-6"

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/animelist/")

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // =============================== Search ===============================

    override fun searchAnimeSelector(): String = "div.col-12"

    override fun searchAnimeNextPageSelector(): String = "ul.pagination > li.page-item > a.page-link[rel]"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnifyFilters.getSearchParameters(filters)
        val cleanQuery = query.replace(" ", "+").lowercase()
        val multiString = buildString {
            if (params.genres.isNotEmpty()) append(params.genres + "&")
            if (params.score.isNotEmpty()) append(params.score + "&")
            if (params.years.isNotEmpty()) append(params.years + "&")
            if (params.ratings.isNotEmpty()) append(params.ratings + "&")
            if (params.status.isNotEmpty()) append(params.status + "&")
            if (params.order.isNotEmpty()) append(params.order + "&")
        }
        return if (query.isEmpty()) {
            GET("$baseUrl/search/?searchtext=&page=$page&$multiString")
        } else {
            GET("$baseUrl/search/?searchtext=$cleanQuery&page=$page&$multiString")
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val searchItems = document.select(searchAnimeSelector()).filter { element: Element? ->
            element?.select("h4 > b")?.first()?.ownText()
                ?.trim() == "Series"
        }.ifEmpty {
            return AnimesPage(emptyList(), false)
        }.first()

        val searchList = searchItems.select("div.card-body").map(::popularAnimeFromElement)
        val hasNextPage = document.selectFirst(searchAnimeNextPageSelector()) != null
        return AnimesPage(searchList, hasNextPage)
    }

    override fun searchAnimeFromElement(element: Element): SAnime = throw Exception("Not used")

    // ============================== Filters ===============================

    override fun getFilterList() = AnifyFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val langPref = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)
        val detailsSelector = "div.component-animeinfo > div.card-body > div.row"
        val details = document.select(detailsSelector)
        val anime = SAnime.create()
        anime.thumbnail_url = details.select("img").attr(langPref.toString())
        anime.title = details.select("h2.dynamic-name").attr(langPref.toString())
        anime.genre = details.select("span.badge-genre").joinToString {
            it.ownText().trim()
        }
        anime.description = details.select("span.description").first()!!.ownText().trimIndent()
        anime.status = parseStatus(details.select("span.badge-status").first()!!.ownText().trim())
        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector(): String = "div.episodelist"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(episodeListSelector()).map(::episodeFromElement).reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val epName = element.select("div.flex-grow-1 > span").first()?.ownText()
        val ep = element.select("span.animename").first()!!.ownText()
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.select("a").attr("href"))
            date_upload = 0L
            name = if (epName.isNullOrBlank()) {
                ep
            } else {
                "$ep: $epName"
            }
            episode_number = ep.split(" ").last().toFloatOrNull() ?: 0F
        }
    }

    // ============================ Video Links =============================

    private val vidstackExtractor by lazy { VidstackExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }

    override fun videoListSelector() = "script:containsData(iframe)"

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val scriptData = document.select(videoListSelector()).first()!!.data().trimIndent()
        val servers = getServersList(scriptData)
        return servers.parallelMap {
            when (it.first) {
                "cdn" -> vidstackExtractor.videosFromUrl(it.second, "CDN (Sub)")
                "cdn2" -> vidstackExtractor.videosFromUrl(it.second, "CDN2 (Sub)")
                "cdn_dubbed" -> vidstackExtractor.videosFromUrl(it.second, "CDN (Dub)")
                "fm" -> filemoonExtractor.videosFromUrl(it.second, "Filemoon (Sub) - ")
                "ds" -> doodExtractor.videosFromUrl(it.second, "Doodstream (Sub)")
                "ds_dubbed" -> doodExtractor.videosFromUrl(it.second, "Doodstream (Dub)")
                else -> emptyList()
            }
        }.flatten().ifEmpty { throw Exception("Failed to fetch videos") }
    }

    override fun videoFromElement(element: Element): Video = throw Exception("Not used")

    override fun videoUrlParse(document: Document): String = throw Exception("Not used")

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val type = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(type) },
                { it.quality.contains(server) },
            ),
        ).reversed()
    }

    private fun getServersList(scriptData: String): List<Pair<String, String>> {
        val regex = Regex("""function\s+(\w+)\s*\(\)\s*\{.*?src="([^"]+)".*?\}""")
        val matches = regex.findAll(scriptData.trim())
        val servers = mutableListOf<Pair<String, String>>()
        for (match in matches) {
            val server = match.groupValues[1]
            val url = match.groupValues[2]
            servers.add(server to "$baseUrl$url")
        }
        return servers
    }

    private fun extractIframeSrc(scriptData: String): String {
        val iframeRegex = "<iframe[^>]*>.*?</iframe>".toRegex()
        val iframe = iframeRegex.find(scriptData)!!.value
        val srcRegex = "(?<=src=\").*?(?=[\\*\"])".toRegex()
        return srcRegex.find(iframe)!!.value
    }

    private fun parseStatus(status: String): Int {
        return when (status) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> = runBlocking {
        map { async(Dispatchers.Default) { f(it) } }.awaitAll()
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "CDN"
        private val PREF_SERVER_ENTRIES = arrayOf("CDN", "FileMoon", "DoodStream")

        private const val PREF_TYPE_KEY = "preferred_type"
        private const val PREF_TYPE_DEFAULT = "Sub"
        private val PREF_TYPE_ENTRIES = arrayOf("Sub", "Dub")

        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_TITLE = "Preferred title language"
        private val PREF_LANG_ENTRIES = arrayOf("English", "Romaji")
        private val PREF_LANG_VALUES = arrayOf("data-en", "data-jp")
        private const val PREF_LANG_DEFAULT = "data-en"
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = PREF_LANG_TITLE
            entries = PREF_LANG_ENTRIES
            entryValues = PREF_LANG_VALUES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_ENTRIES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_TYPE_KEY
            title = "Preferred Type"
            entries = PREF_TYPE_ENTRIES
            entryValues = PREF_TYPE_ENTRIES
            setDefaultValue(PREF_TYPE_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
}
