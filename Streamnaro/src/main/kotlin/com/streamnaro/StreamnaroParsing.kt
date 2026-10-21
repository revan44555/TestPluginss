package com.streamnaro

import java.net.URI

/**
 * Pure parsing and validation helpers kept separate so they can be tested without
 * constructing a CloudStream provider or making network requests.
 */
internal object StreamnaroParsing {
    private val imdbIdPattern = Regex("^tt\\d{5,10}$")

    data class EpisodeRoute(
        val imdbId: String,
        val season: Int,
        val episode: Int
    )

    /**
     * A named embed provider. Both templates must be absolute HTTPS URLs that
     * contain an {id} placeholder; episode templates may also use {season} and
     * {episode}. All placeholders are filled with the item's IMDb ID, so a
     * provider only qualifies if its public embed accepts IMDb IDs.
     */
    data class EmbedProvider(
        val name: String,
        val movieTemplate: String,
        val episodeTemplate: String
    )

    /**
     * A concrete player page rendered from a provider template, paired with the
     * provider name so resolved streams can be labelled by their source.
     */
    data class PlaybackCandidate(
        val url: String,
        val providerName: String
    )

    fun isImdbId(value: String): Boolean = imdbIdPattern.matches(value)

    /**
     * Pagination must use the upstream metas count, before invalid IDs are filtered.
     */
    fun hasNextPage(upstreamItemCount: Int, pageSize: Int): Boolean =
        pageSize > 0 && upstreamItemCount >= pageSize

    /**
     * Only accept absolute HTTPS URLs with a host and without embedded user info.
     * The helper is for display metadata (posters), never playback URLs.
     */
    fun safeHttpsUrl(value: String?): String? {
        val candidate = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val uri = URI(candidate)
            if (
                uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null
            ) {
                candidate
            } else {
                null
            }
        }.getOrNull()
    }

    /**
     * Validate an HLS manifest URL before exposing it as a playable link.
     * Only absolute HTTPS URLs with a host and no embedded credentials are accepted.
     * A manifest suffix must appear in the URL path, not merely in its query string.
     */
    fun safeHttpsManifestUrl(value: String?): String? {
        val candidate = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val uri = URI(candidate)
            val path = uri.path
            val isManifestPath = path != null && (
                path.endsWith(".m3u8", ignoreCase = true) ||
                    path.equals("master.txt", ignoreCase = true) ||
                    path.endsWith("/master.txt", ignoreCase = true)
                )
            // hls.js-style players mark manifest requests with a client-side
            // fragment ("...#file.m3u8"). The fragment is never sent to the
            // server, but it is a reliable signal that the URL is a playlist,
            // so path-based checks alone must not reject it. The fragment is
            // stripped from the returned URL because it carries no server
            // meaning and some HTTP stacks refuse URLs containing one.
            val fragment = uri.fragment
            val isManifestFragment = fragment != null &&
                fragment.endsWith(".m3u8", ignoreCase = true)
            if (
                uri.scheme?.equals("https", ignoreCase = true) == true &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                (isManifestPath || isManifestFragment)
            ) {
                candidate.substringBefore('#')
            } else {
                null
            }
        }.getOrNull()
    }

    fun catalogPageUrl(catalogBaseUrl: String, skip: Int): String {
        val base = catalogBaseUrl.trimEnd('/').removeSuffix(".json")
        val safeSkip = skip.coerceAtLeast(0)
        return if (safeSkip == 0) {
            "$base.json"
        } else {
            "$base/skip=$safeSkip.json"
        }
    }

    /**
     * Read season/episode numbers from Cinemeta fields, falling back to a video
     * ID formatted as <series-imdb-id>:<season>:<episode>.
     */
    fun parseEpisodeCoordinates(
        videoId: String?,
        season: Int?,
        episode: Int?
    ): Pair<Int, Int>? {
        val idParts = videoId?.split(':').orEmpty()
        val parsedSeason = season ?: idParts.getOrNull(1)?.toIntOrNull() ?: return null
        val parsedEpisode = episode ?: idParts.getOrNull(2)?.toIntOrNull() ?: return null
        if (parsedSeason < 0 || parsedEpisode < 1) return null
        return parsedSeason to parsedEpisode
    }

    /**
     * Parse the internal route stored on an episode. This route is not a media
     * URL: it identifies the IMDb ID and episode coordinates used to build the
     * embed player page when playback is requested.
     */
    fun parseEpisodeRoute(url: String, routePrefix: String): EpisodeRoute? {
        if (!url.startsWith(routePrefix)) return null
        val parts = url.removePrefix(routePrefix).split('/')
        if (parts.size != 4 || parts[0] != "episode") return null

        val imdbId = parts[1]
        val season = parts[2].toIntOrNull() ?: return null
        val episode = parts[3].toIntOrNull() ?: return null
        if (!isImdbId(imdbId) || season < 0 || episode < 1) return null

        return EpisodeRoute(imdbId, season, episode)
    }

    /**
     * Build the player page for a movie or episode on a single legacy NHD-style
     * base URL (uniform /movie/{id} and /tv/{id}/{s}/{e} shapes).
     *
     * Retained for backward compatibility; new code should prefer
     * [playbackPageCandidates] with explicit [EmbedProvider] templates, because
     * real embed providers use heterogeneous route shapes.
     */
    fun playbackPageUrl(
        internalRoute: String,
        routePrefix: String,
        playerBaseUrl: String
    ): String? {
        val base = playerBaseUrl.trim().trimEnd('/')
        val baseIsSecure = runCatching {
            val uri = URI(base)
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
        if (!baseIsSecure) return null

        val itemRoute = parseItemRoute(internalRoute, routePrefix)
        if (itemRoute != null) {
            if (itemRoute.first != "movie") return null
            return "$base/movie/${itemRoute.second}"
        }

        val episode = parseEpisodeRoute(internalRoute, routePrefix) ?: return null
        return "$base/tv/${episode.imdbId}/${episode.season}/${episode.episode}"
    }

    /**
     * Build one player-page candidate per provider, in provider order. Each
     * candidate carries its provider's name for source labelling. Providers
     * whose templates are not absolute HTTPS URLs (or lack the placeholders
     * required for the item type) are skipped individually, so one malformed
     * entry can never silence the rest of the chain.
     *
     * "series" item routes are intentionally not playable directly; only their
     * episode routes produce candidates.
     */
    fun playbackPageCandidates(
        internalRoute: String,
        routePrefix: String,
        providers: List<EmbedProvider>
    ): List<PlaybackCandidate> {
        val itemRoute = parseItemRoute(internalRoute, routePrefix)
        if (itemRoute != null) {
            if (itemRoute.first != "movie") return emptyList()
            return providers.mapNotNull { provider ->
                renderTemplate(provider.movieTemplate, itemRoute.second, null, null)
                    ?.let { PlaybackCandidate(it, provider.name) }
            }
        }

        val episode = parseEpisodeRoute(internalRoute, routePrefix) ?: return emptyList()
        return providers.mapNotNull { provider ->
            renderTemplate(
                provider.episodeTemplate,
                episode.imdbId,
                episode.season,
                episode.episode
            )?.let { PlaybackCandidate(it, provider.name) }
        }
    }

    private fun renderTemplate(
        template: String,
        id: String,
        season: Int?,
        episode: Int?
    ): String? {
        val trimmed = template.trim()
        if (!trimmed.contains("{id}")) return null

        val needsEpisode = trimmed.contains("{season}") || trimmed.contains("{episode}")
        if (needsEpisode && (season == null || episode == null)) return null

        // Substitute first, validate second: the raw template contains "{id}"
        // placeholders, and java.net.URI rejects braces as illegal characters,
        // so validating the template itself would silently drop every provider.
        val rendered = trimmed
            .replace("{id}", id)
            .replace("{season}", season?.toString() ?: "")
            .replace("{episode}", episode?.toString() ?: "")
        return safeHttpsUrl(rendered)
    }

    fun parseItemRoute(url: String, routePrefix: String): Pair<String, String>? {
        if (!url.startsWith(routePrefix)) return null
        val parts = url.removePrefix(routePrefix).split('/')
        if (parts.size != 2) return null

        val type = parts[0]
        val id = parts[1]
        if (type !in setOf("movie", "series") || !isImdbId(id)) return null
        return type to id
    }
}
