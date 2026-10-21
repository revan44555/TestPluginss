package com.streamnaro

import java.net.URI

/**
 * Pure parsing and validation helpers kept separate so they can be tested without
 * constructing a CloudStream provider or making network requests.
 */
internal object StreamnaroParsing {
    private val imdbIdPattern = Regex("^tt\\d{5,10}$")

    fun isImdbId(value: String): Boolean = imdbIdPattern.matches(value)

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
