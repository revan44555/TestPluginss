package com.streamnaro

import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException

/**
 * Metadata/catalog prototype for Streamnaro.
 *
 * Streamnaro's public page mentions Cinemeta catalog feeds, but this review did
 * not find a documented, verifiable playback API. Search and metadata therefore
 * use Cinemeta's documented public metadata interface. Playback intentionally
 * fails closed until an authorized and documented media endpoint is available.
 */
class StreamnaroProvider : MainAPI() {
    override var mainUrl = "https://streamnaro.online"
    override var name = "Streamnaro Catalog"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$CINEMETA_URL/catalog/movie/top.json" to "Movies",
        "$CINEMETA_URL/catalog/series/top.json" to "TV Series"
    )

    private val mapper = jacksonObjectMapper()
    private val headers = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "CloudStream-StreamnaroCatalog/1.0"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val mediaType = if (request.data.contains("/catalog/series/")) "series" else "movie"
        val baseUrl = request.data.substringBefore('?')
        val skip = ((page.coerceAtLeast(1) - 1) * PAGE_SIZE)
        val response = getJson("$baseUrl?skip=$skip")
        val items = parseSearchResponses(response, mediaType)
        return newHomePageResponse(
            request.name,
            items,
            hasNext = items.size >= PAGE_SIZE
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val movieResults = parseSearchResponses(
            getJson("$CINEMETA_URL/catalog/movie/top/search=$encoded.json"),
            "movie"
        )
        val seriesResults = parseSearchResponses(
            getJson("$CINEMETA_URL/catalog/series/top/search=$encoded.json"),
            "series"
        )

        return (movieResults + seriesResults).distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val route = parseItemRoute(url)
            ?: throw IllegalArgumentException("Unsupported Streamnaro catalog item")

        val metaUrl = CINEMETA_URL + "/meta/" + route.first + "/" + route.second + ".json"
        val meta = getJson(metaUrl)
            ?.get("meta")
            ?.takeUnless { it.isNull || it.isMissingNode }
            ?: throw IllegalStateException("Metadata is currently unavailable")

        val title = textField(meta, "name")
            ?: throw IllegalStateException("Metadata response has no title")
        val poster = safeHttpsUrl(textField(meta, "poster"))
        val description = textField(meta, "description")

        return if (route.first == "movie") {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val episodes = parseEpisodes(meta, route.second)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    /**
     * No Streamnaro playback endpoint has been verified. Returning false is
     * deliberate: never treat a guessed URL or an unverified player page as a
     * media stream. Implement this only against an authorized documented API.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.w(TAG, "Playback is not configured: no verified Streamnaro playback API.")
        return false
    }

    private suspend fun getJson(url: String): JsonNode? {
        return try {
            val response = app.get(url, headers = headers)
            if (!response.isSuccessful) {
                Log.w(TAG, "Metadata endpoint returned HTTP " + response.code)
                null
            } else {
                mapper.readTree(response.text)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Metadata request failed (" + error.javaClass.simpleName + ")")
            null
        }
    }

    private fun parseSearchResponses(
        json: JsonNode?,
        mediaType: String
    ): List<SearchResponse> {
        val metas = json?.get("metas")?.takeIf { it.isArray } ?: return emptyList()
        return metas.mapNotNull { meta ->
            val id = textField(meta, "imdb_id")
                ?: textField(meta, "id")
                ?: return@mapNotNull null
            if (!StreamnaroParsing.isImdbId(id)) return@mapNotNull null

            val title = textField(meta, "name") ?: return@mapNotNull null
            val poster = safeHttpsUrl(textField(meta, "poster"))
            val route = "$ROUTE_PREFIX$mediaType/$id"

            when (mediaType) {
                "movie" -> newMovieSearchResponse(title, route, TvType.Movie) {
                    this.posterUrl = poster
                }
                "series" -> newTvSeriesSearchResponse(title, route, TvType.TvSeries) {
                    this.posterUrl = poster
                }
                else -> null
            }
        }
    }

    private fun parseEpisodes(meta: JsonNode, imdbId: String): List<Episode> {
        val videos = meta.get("videos")?.takeIf { it.isArray } ?: return emptyList()

        return videos.mapNotNull { video ->
            val coordinates = StreamnaroParsing.parseEpisodeCoordinates(
                textField(video, "id"),
                intField(video, "season"),
                intField(video, "episode")
            ) ?: return@mapNotNull null
            val (season, episodeNumber) = coordinates

            val title = textField(video, "title") ?: "Episode $episodeNumber"
            val route = "$ROUTE_PREFIX" + "episode/$imdbId/$season/$episodeNumber"

            newEpisode(route) {
                this.name = title
                this.season = season
                this.episode = episodeNumber
                this.posterUrl = safeHttpsUrl(textField(video, "thumbnail"))
            }
        }.distinctBy { it.data }
            .sortedWith(
                compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                    .thenBy { it.episode ?: Int.MAX_VALUE }
            )
    }

    private fun parseItemRoute(url: String): Pair<String, String>? =
        StreamnaroParsing.parseItemRoute(url, ROUTE_PREFIX)

    private fun textField(node: JsonNode, field: String): String? {
        return node.get(field)
            ?.takeUnless { it.isNull || it.isMissingNode }
            ?.asText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun intField(node: JsonNode, field: String): Int? {
        val value = node.get(field) ?: return null
        if (value.isNull || value.isMissingNode || !value.canConvertToInt()) return null
        return value.asInt()
    }

    private fun safeHttpsUrl(value: String?): String? =
        StreamnaroParsing.safeHttpsUrl(value)

    companion object {
        private const val TAG = "Streamnaro"
        private const val CINEMETA_URL = "https://v3-cinemeta.strem.io"
        private const val ROUTE_PREFIX = "streamnaro://"
        private const val PAGE_SIZE = 100
    }
}
