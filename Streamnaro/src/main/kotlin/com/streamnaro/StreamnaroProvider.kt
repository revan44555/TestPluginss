package com.streamnaro

import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.network.WebViewResolver
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException

/**
 * Streamnaro catalog provider.
 *
 * Cinemeta supplies catalog metadata. For playback, this provider opens public
 * embed player pages that accept IMDb IDs and asks CloudStream's WebViewResolver
 * to capture the HLS manifest requested by each player's script. M3u8Helper
 * expands a master manifest into CloudStream playback-quality links.
 *
 * The original NHD player is gone: as of October 2026 its three front domains
 * present third-party certificates (privatemedia.app / api.autoembed.in), so
 * the embed layer now walks a chain of independent IMDb-compatible providers
 * until one yields a capturable HLS manifest. Each candidate is probed cheaply
 * over plain HTTP first, so dead providers are tried last.
 */
class StreamnaroProvider : MainAPI() {
    override var mainUrl = "https://streamnaro.online"
    override var name = "Streamnaro"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val providerType = ProviderType.MetaProvider
    // Playback resolution depends on a WebView to run the embed player page.
    override val usesWebView = true
    // Captured HLS sources may require headers that are not portable to Cast devices.
    override val hasChromecastSupport = false
    // Embed pages are ad-heavy; downloads are not offered.
    override val hasDownloadSupport = false

    override val mainPage = mainPageOf(
        "$CINEMETA_URL/catalog/movie/top.json" to "Popular Movies",
        "$CINEMETA_URL/catalog/series/top.json" to "Popular TV Series"
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
        val upstreamItemCount = response
            ?.get("metas")
            ?.takeIf { it.isArray }
            ?.size()
            ?: 0
        val items = parseSearchResponses(response, mediaType)
        return newHomePageResponse(
            request.name,
            items,
            hasNext = StreamnaroParsing.hasNextPage(upstreamItemCount, PAGE_SIZE)
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val movieResponse = getJson("$CINEMETA_URL/catalog/movie/top/search=$encoded.json")
        val seriesResponse = getJson("$CINEMETA_URL/catalog/series/top/search=$encoded.json")
        val movieResults = parseSearchResponses(movieResponse, "movie")
        val seriesResults = parseSearchResponses(seriesResponse, "series")

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
                this.backgroundPosterUrl = safeHttpsUrl(textField(meta, "background"))
                this.plot = description
                this.year = yearField(meta)
                this.score = scoreField(meta)
                this.tags = stringArrayField(meta, "genres")
            }
        } else {
            val episodes = parseEpisodes(meta, route.second)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = safeHttpsUrl(textField(meta, "background"))
                this.plot = description
                this.year = yearField(meta)
                this.score = scoreField(meta)
                this.tags = stringArrayField(meta, "genres")
            }
        }
    }

    /**
     * Resolve an item through public embed pages that accept IMDb IDs.
     * The player pages request HLS dynamically, so we capture that request
     * instead of hard-coding short-lived CDN URLs.
     *
     * Candidates come from [StreamnaroParsing.playbackPageCandidates]: one per
     * configured provider, in preference order. They are probed cheaply over
     * plain HTTP, then walked in a WebView until one produces a playable
     * manifest, so a single provider outage degrades start time instead of
     * failing playback.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val candidates = StreamnaroParsing.playbackPageCandidates(
            internalRoute = data,
            routePrefix = routePrefix,
            providers = EMBED_PROVIDERS
        )
        if (candidates.isEmpty()) {
            Log.w(TAG, "Ignoring unsupported internal playback route.")
            return false
        }

        val orderedCandidates = reorderByProbe(candidates)
        for (candidate in orderedCandidates) {
            if (resolveManifestLinks(candidate.url, candidate.providerName, callback)) {
                return true
            }
        }

        Log.w(TAG, "No playable HLS manifest was captured from any embed provider.")
        return false
    }

    /**
     * Split candidates into probe-reachable and probe-unreachable buckets while
     * preserving the original order inside each bucket. A failed probe does not
     * disqualify a candidate: many providers front their pages with Cloudflare,
     * which may answer okhttp with a challenge yet still render in the app's
     * WebView.
     */
    private suspend fun reorderByProbe(
        candidates: List<StreamnaroParsing.PlaybackCandidate>
    ): List<StreamnaroParsing.PlaybackCandidate> {
        val reachable = mutableListOf<StreamnaroParsing.PlaybackCandidate>()
        val unreachable = mutableListOf<StreamnaroParsing.PlaybackCandidate>()
        for (candidate in candidates) {
            if (probePlayerPage(candidate.url)) {
                reachable.add(candidate)
            } else {
                unreachable.add(candidate)
            }
        }
        return reachable + unreachable
    }

    private suspend fun probePlayerPage(url: String): Boolean {
        return try {
            val response = app.get(url, headers = probeHeaders)
            response.code in 200..399
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Embed probe failed (" + error.javaClass.simpleName + "): " + url)
            false
        }
    }

    /**
     * Open one candidate player page in the WebView resolver, capture the HLS
     * manifest request its player makes, and expose every parsed quality as an
     * extractor link labelled with the provider name. Returns true only when at
     * least one link was emitted.
     */
    private suspend fun resolveManifestLinks(
        playerPage: String,
        providerName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val manifestPattern = Regex("""(?i)(?:\.m3u8(?:[?#]|$)|(?:^|/)master\.txt(?:[?#]|$))""")
        val resolver = WebViewResolver(
            interceptUrl = manifestPattern,
            useOkhttp = false,
            script = AUTO_PLAY_SCRIPT,
            timeout = WEBVIEW_TIMEOUT_MS
        )

        return try {
            val referer = originOf(playerPage)
            val (capturedRequest, additionalRequests) = resolver.resolveUsingWebView(
                url = playerPage,
                referer = referer
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
                        source = "$name · $providerName",
                        streamUrl = streamUrl,
                        referer = request.header("Referer") ?: referer,
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
                Log.w(TAG, "No playable HLS manifest was captured from " + playerPage)
            }
            foundLinks
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Playback resolution failed for " + playerPage + " (" + error.javaClass.simpleName + ")")
            false
        }
    }

    private fun originOf(url: String): String =
        runCatching {
            val uri = URI(url)
            uri.scheme + "://" + uri.authority + "/"
        }.getOrDefault(url)

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
            val id = listOfNotNull(
                textField(meta, "imdb_id"),
                textField(meta, "id")
            ).firstOrNull(StreamnaroParsing::isImdbId) ?: return@mapNotNull null

            val title = textField(meta, "name") ?: return@mapNotNull null
            val poster = safeHttpsUrl(textField(meta, "poster"))
            val route = "$routePrefix$mediaType/$id"

            when (mediaType) {
                "movie" -> newMovieSearchResponse(title, route, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = yearField(meta)
                    this.score = scoreField(meta)
                }
                "series" -> newTvSeriesSearchResponse(title, route, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = yearField(meta)
                    this.score = scoreField(meta)
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

    private fun yearField(node: JsonNode): Int? =
        intField(node, "year")
            ?: textField(node, "year")?.take(4)?.toIntOrNull()
            ?: textField(node, "releaseInfo")?.take(4)?.toIntOrNull()

    private fun scoreField(node: JsonNode): Score? {
        val rating = textField(node, "imdbRating")?.toFloatOrNull() ?: return null
        if (!rating.isFinite() || rating !in 0f..10f) return null
        return Score.from10(rating)
    }

    private fun stringArrayField(node: JsonNode, field: String): List<String>? {
        val array = node.get(field)?.takeIf { it.isArray } ?: return null
        return array.mapNotNull { item ->
            item.asText().trim().takeIf { it.isNotEmpty() }
        }.distinct().takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val TAG = "Streamnaro"
        private const val CINEMETA_URL = "https://v3-cinemeta.strem.io"
        private const val PAGE_SIZE = 100
        private const val WEBVIEW_TIMEOUT_MS = 45_000L

        // Embed providers whose public players accept IMDb IDs directly, in
        // preference order. NHD (nhdapi.st/.com, player.nhdapi.com) was removed:
        // all three domains now present third-party certificates (privatemedia.app
        // and api.autoembed.in) — the service is defunct as of October 2026.
        // 2embed.online is verified alive; vidsrc.sh and multiembed.mov publish
        // IMDb-based embed APIs; AutoEmbed is kept last as the unverified
        // successor hypothesis for the defunct NHD infrastructure.
        private val EMBED_PROVIDERS = listOf(
            StreamnaroParsing.EmbedProvider(
                name = "2Embed",
                movieTemplate = "https://www.2embed.online/embed/movie/{id}",
                episodeTemplate = "https://www.2embed.online/embed/tv/{id}/{season}/{episode}"
            ),
            StreamnaroParsing.EmbedProvider(
                name = "VidSrc",
                movieTemplate = "https://vidsrc.sh/embed/movie/{id}",
                episodeTemplate = "https://vidsrc.sh/embed/tv/{id}/{season}/{episode}"
            ),
            StreamnaroParsing.EmbedProvider(
                name = "SuperEmbed",
                movieTemplate = "https://multiembed.mov/?video_id={id}",
                episodeTemplate = "https://multiembed.mov/?video_id={id}&s={season}&e={episode}"
            ),
            StreamnaroParsing.EmbedProvider(
                name = "AutoEmbed",
                movieTemplate = "https://api.autoembed.in/movie/{id}",
                episodeTemplate = "https://api.autoembed.in/tv/{id}/{season}/{episode}"
            )
        )

        // Browser-like headers for the cheap provider probe; Cloudflare-fronted
        // pages are far more likely to answer a real desktop UA with a 2xx.
        private val probeHeaders = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        )

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
