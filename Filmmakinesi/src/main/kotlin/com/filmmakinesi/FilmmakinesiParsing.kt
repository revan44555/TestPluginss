package com.filmmakinesi

/**
 * Pure parsing helpers for FilmMakinesi. No CloudStream or network
 * dependencies, so they can be unit tested on the JVM.
 */
internal object FilmmakinesiParsing {

    private val playerHostPattern =
        Regex("(?i)^https?://[a-z0-9-]*\\.(rapid|closeload)\\.filmmakinesi\\.to/")
    private val barePlayerPattern =
        Regex("https?://[a-z0-9-]*\\.(?:rapid|closeload)\\.filmmakinesi\\.to/[^\\s"'<>\\\\]+")
    private val attrCarrierPattern =
        Regex("(?:src|data-(?:src|link|url|file))=["'](https?://[^"']+)["']")
    private val m3u8Pattern =
        Regex("(?i)(https?://[^\\s"'<>\\\\]+\\.m3u8[^\\s"'<>\\\\]*)")
    private val yearPattern = Regex("\\b(19|20)\\d{2}\\b")
    private val ratingPattern = Regex("\\b([0-9]\\.[0-9])\\b")

    fun isFilmUrl(href: String): Boolean {
        if (href.startsWith("javascript:", ignoreCase = true)) return false
        val path = runCatching { java.net.URI(href).path }.getOrNull() ?: href
        return path.startsWith("/film/")
    }

    fun isPlayerUrl(url: String): Boolean = playerHostPattern.containsMatchIn(url)

    /**
     * Collect first-party player URLs (rapid./closeload. subdomains) from a
     * film page's raw HTML. Covers iframe src attributes, common data-*
     * carriers and bare URLs, so it survives theme markup changes.
     */
    fun extractPlayerUrls(html: String): List<String> {
        val found = LinkedHashSet<String>()
        for (match in attrCarrierPattern.findAll(html)) {
            val url = match.groupValues[1]
            if (isPlayerUrl(url)) found.add(url)
        }
        for (match in barePlayerPattern.findAll(html)) {
            found.add(match.value.trimEnd('.', ';'))
        }
        return found.toList()
    }

    /** Undo the escaping embed pages apply to stream URLs. */
    fun normalizeEscaped(raw: String): String =
        raw
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

    fun extractM3u8(raw: String): String? {
        val match = m3u8Pattern.find(raw) ?: return null
        return match.value.trimEnd('"', '\'', ';', ',')
    }

    fun extractYear(text: String): Int? =
        yearPattern.find(text)?.value?.toIntOrNull()

    fun extractImdb(text: String): Float? {
        if (!text.contains("imdb", ignoreCase = true)) return null
        val value = ratingPattern.find(text)?.groupValues?.get(1)?.toFloatOrNull()
            ?: return null
        return if (value in 0f..10f) value else null
    }

    fun safeAbsoluteUrl(url: String, base: String): String {
        val trimmed = url.trim()
        if (trimmed.startsWith("//")) return "https:" + trimmed
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return base.trimEnd('/') + "/" + trimmed.trimStart('/')
    }

    fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")

    fun playerLabel(url: String): String = when {
        url.contains("rapid", ignoreCase = true) -> "Rapid"
        url.contains("closeload", ignoreCase = true) -> "CloseLoad"
        else -> "Kaynak"
    }
}
