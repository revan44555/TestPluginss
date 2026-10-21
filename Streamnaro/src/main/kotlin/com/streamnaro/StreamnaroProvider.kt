package com.streamnaro

import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.network.WebViewResolver
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException

/**
 * Streamnaro catalog provider.
 *
 * Cinemeta supplies catalog metadata. For playback, this provider opens the
 * publicly documented NHD player page and asks CloudStream's WebViewResolver to
 * capture the HLS manifest requested by that player. M3u8Helper expands a
 * master manifest into CloudStream playback-quality links.
 */
class StreamnaroProvider : MainAPI() {
    override var mainUrl = "https://streamnaro.online"
    override var name = "Streamnaro Catalog"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    // No verified playable links exist yet, so download support would be misleading.
    override val hasDownloadSupport = false

    override val mainPage = mainPageOf(
        "$CINEMETA_URL/catalog/movie/top.json" to "Movies",
        "$CINEMETA_URL/catalog/series/top.json" to "TV Series"
    )

    // CloudStream resolves providers for saved items by matching URLs against mainUrl.
    // Keep catalog routes under that URL namespace rather than using a custom URI scheme.
    private val routePrefix: String
        get() = "${mainUrl.trimEnd('/')}/__catalog__/"

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
        val catalogBaseUrl = request.data.substringBefore('?').removeSuffix(".json")
        val skip = ((page.coerceAtLeast(1) - 1) * PAGE_SIZE)
        val pageUrl = StreamnaroParsing.catalogPageUrl(catalogBaseUrl, skip)
        val response = getJson(pageUrl)
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
     * Resolve an item through the NHD player's documented public embed page.
     * The player dynamically requests HLS, so we capture that request instead
     * of hard-coding a short-lived CDN URL from a browser session.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerPage = StreamnaroParsing.playbackPageUrl(
            internalRoute = data,
            routePrefix = routePrefix,
            playerBaseUrl = NHD_PLAYER_URL
        ) ?: run {
            Log.w(TAG, "Ignoring unsupported internal playback route.")
            return false
        }

        val manifestPattern = Regex("""(?i)(?:\.m3u8(?:[?#]|$)|(?:^|/)master\.txt(?:[?#]|$))""")
        val resolver = WebViewResolver(
            interceptUrl = manifestPattern,
            useOkhttp = false,
            script = AUTO_PLAY_SCRIPT,
            timeout = WEBVIEW_TIMEOUT_MS
        )

        return try {
            val (capturedRequest, additionalRequests) = resolver.resolveUsingWebView(
                url = playerPage,
                referer = "$NHD_PLAYER_URL/"
            )
            val requests = (listOfNotNull(capturedRequest) + additionalRequests)
                .distinctBy { it.url.toString() }

            var foundLinks = false
            for (request in requests) {
                val capturedUrl = request.url.toString()
                if (!manifestPattern.containsMatchIn(capturedUrl)) continue
                val streamUrl = StreamnaroParsing.safeHttpsManifestUrl(capturedUrl)
                if (streamUrl == null) {
                    Log.w(TAG, "Ignoring a non-HTTPS or malformed HLS manifest URL.")
                    continue
                }

                // Keep only headers commonly required by cross-origin HLS sources.
                // Do not forward session cookies to playlist segment hosts.
                val playbackHeaders = listOf(
                    "Origin",
                    "Referer",
                    "User-Agent",
                    "Accept",
                    "Accept-Language"
                ).mapNotNull { headerName ->
                    request.header(headerName)?.let { headerName to it }
                }.toMap()

                try {
                    val links = M3u8Helper.generateM3u8(
                        source = "$name · NHD",
                        streamUrl = streamUrl,
                        referer = request.header("Referer") ?: playerPage,
                        headers = playbackHeaders,
                        name = "Stream HD"
                    )
                    links.forEach { link ->
                        callback(link)
                        foundLinks = true
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Could not parse captured HLS manifest (" + error.javaClass.simpleName + ")")
                }
            }

            if (!foundLinks) {
                Log.w(TAG, "No playable HLS manifest was captured from the player.")
            }
            foundLinks
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Playback resolution failed (" + error.javaClass.simpleName + ")")
            false
        }
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
            val route = "$routePrefix$mediaType/$id"

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
            val route = "${routePrefix}episode/$imdbId/$season/$episodeNumber"

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
        StreamnaroParsing.parseItemRoute(url, routePrefix)

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
        private const val NHD_PLAYER_URL = "https://nhdapi.st"
        private const val PAGE_SIZE = 100
        private const val WEBVIEW_TIMEOUT_MS = 45_000L

        // Injected by WebViewResolver as page resources load. It clicks a visible
        // play control once and requests muted playback as a fallback; CloudStream
        // itself plays the captured HLS link.
        private const val AUTO_PLAY_SCRIPT = """
            (() => {
                if (window.__streamnaroAutoPlayInstalled) return;
                window.__streamnaroAutoPlayInstalled = true;
                let attempts = 0;
                let playClicked = false;

                const collectAccessibleDocuments = () => {
                    const documents = [document];
                    for (let index = 0; index < documents.length && documents.length < 12; index++) {
                        let frames = [];
                        try {
                            frames = Array.from(documents[index].querySelectorAll('iframe'));
                        } catch (_) {}
                        for (const frame of frames) {
                            try {
                                const nested = frame.contentDocument;
                                if (nested && !documents.includes(nested)) documents.push(nested);
                            } catch (_) {
                                // Cross-origin frames cannot be inspected from the parent page.
                            }
                        }
                    }
                    return documents;
                };

                const tick = () => {
                    attempts += 1;
                    const documents = collectAccessibleDocuments();

                    for (const doc of documents) {
                        if (!playClicked) {
                            let controls = [];
                            try {
                                controls = Array.from(doc.querySelectorAll(
                                    '.vjs-big-play-button, .jw-icon-display, .plyr__control--overlaid, button, [role="button"], a'
                                ));
                            } catch (_) {}

                            const play = controls.find((el) => {
                                const text = (el.innerText || el.textContent || '').trim();
                                const aria = el.getAttribute('aria-label') || '';
                                const cls = typeof el.className === 'string' ? el.className.toLowerCase() : '';
                                const visible = !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
                                return visible && (/^play( video)?$/i.test(text) || /play/i.test(aria) ||
                                    cls.includes('big-play-button') || cls.includes('play-button') ||
                                    cls.includes('icon-display') || cls.includes('control--overlaid'));
                            });

                            if (play) {
                                playClicked = true;
                                try { play.click(); } catch (_) {}
                            }
                        }

                        try {
                            const video = doc.querySelector('video');
                            if (video && video.paused) {
                                video.muted = true;
                                const result = video.play();
                                if (result && result.catch) result.catch(() => {});
                            }
                        } catch (_) {}
                    }

                    if (attempts >= 90) clearInterval(timer);
                };

                const timer = setInterval(tick, 400);
                tick();
            })();
        """
    }
}
