package eu.kanade.tachiyomi.animeextension.en.anify.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class VidstackExtractor(private val client: OkHttpClient) {

    private val playlistUtils by lazy { PlaylistUtils(client, getHeaders()) }

    fun videosFromUrl(url: String, prefix: String): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val scriptData = document.select("script:containsData(m3u8)").first()!!.data()
        val fileUrl = scriptData.substringAfter("file\": '").substringBefore("'")
        return playlistUtils.extractFromHls(
            fileUrl,
            masterHeadersGen = { _, _ -> getHeaders() },
            videoHeadersGen = { _, _, _ -> getHeaders() },
            videoNameGen = { "$prefix - $it" },
        )
    }

    private fun getHeaders(): Headers = Headers.Builder()
        .set("Origin", "https://vidstack.xyz")
        .build()
}
