package com.ninjagoizlesene

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class NinjagoIzleseneProvider : MainAPI() {

    override var mainUrl = "https://ninjagoizlesene.com.tr"
    override var name = "Ninjago İzlesene"
    override var lang = "tr"

    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    // Başlığı bulmak için birkaç farklı yeri dener: linkin kendi metni,
    // title attribute'u, img alt'ı, ya da linkin içinde bulunduğu kartın
    // en yakın başlık elementi (h2/h3/strong).
    private fun extractTitle(link: Element, fallbackSlug: String): String {
        var title = link.text()
            .replace(Regex("\\s+"), " ")
            .trim()

        if (title.isBlank()) {
            title = link.attr("title").replace(Regex("\\s+"), " ").trim()
        }

        if (title.isBlank()) {
            title = link.selectFirst("img")?.attr("alt")
                ?.replace(Regex("\\s+"), " ")?.trim() ?: ""
        }

        if (title.isBlank()) {
            val container = link.closest("article, div, li, section") ?: link.parent()
            title = container?.selectFirst("h1, h2, h3, h4, strong")
                ?.text()?.replace(Regex("\\s+"), " ")?.trim() ?: ""
        }

        if (title.isBlank()) {
            title = fallbackSlug
                .replace("-", " ")
                .replace("_", " ")
                .split(" ")
                .joinToString(" ") { word ->
                    word.replaceFirstChar { it.uppercase() }
                }
        }

        return title
    }

    private fun extractPoster(link: Element): String? {
        val img = link.selectFirst("img")
            ?: link.closest("article, div, li, section")?.selectFirst("img")

        return img?.let {
            it.attr("src")
                .ifBlank { it.attr("data-src") }
                .ifBlank { it.attr("data-lazy-src") }
                .ifBlank { it.attr("data-original") }
                .takeIf { src -> src.isNotBlank() }
                ?.let { src -> fixUrl(src) }
        }
    }

    private fun collectSeriesLinks(doc: org.jsoup.nodes.Document): List<Pair<String, Element>> {
        val seen = HashSet<String>()
        val results = mutableListOf<Pair<String, Element>>()

        for (link in doc.select("a[href]")) {
            val rawHref = link.attr("href").trim()
            if (rawHref.isBlank()) continue

            val href = fixUrl(rawHref)
            if (!href.contains("/dizi/")) continue

            val path = href.substringAfter(mainUrl).trim('/')
            val parts = path.split("/").filter { it.isNotBlank() }

            if (parts.size != 2 || parts[0] != "dizi") continue

            if (!seen.add(href)) continue
            results.add(href to link)
        }
        return results
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/dizi-arsivi/").document

        val seriesLinks = collectSeriesLinks(doc)
        val results = mutableListOf<SearchResponse>()

        for ((href, link) in seriesLinks) {
            val slug = href.substringAfter("/dizi/").trim('/')
            val title = extractTitle(link, slug)
            if (title.isBlank()) continue

            if (query.isNotBlank() && !title.contains(query, ignoreCase = true)) continue

            val poster = extractPoster(link)

            results.add(
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            )
        }

        if (results.isEmpty()) {
            for ((href, link) in seriesLinks) {
                val slug = href.substringAfter("/dizi/").trim('/')
                val title = extractTitle(link, slug)
                if (title.isBlank()) continue
                val poster = extractPoster(link)
                results.add(
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                )
            }
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url)
        val doc = app.get(fixedUrl).document

        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: "Ninjago"

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            ?: doc.selectFirst("img")?.let { img ->
                img.attr("src").ifBlank { img.attr("data-src") }
                    .ifBlank { img.attr("data-lazy-src") }
                    .takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            }

        val plot = doc.selectFirst("meta[name='description']")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        val episodes = mutableListOf<Episode>()
        val seenEpisodes = HashSet<String>()

        for (link in doc.select("a[href]")) {
            val rawHref = link.attr("href").trim()
            if (rawHref.isBlank()) continue

            val href = fixUrl(rawHref)
            if (href == fixedUrl) continue

            val text = link.text().replace(Regex("\\s+"), " ").trim()
            if (text.isBlank()) continue

            val textMatch = Regex(
                """(\d+)\s*\.\s*Sezon\s+(\d+)\s*\.\s*Bölüm""",
                RegexOption.IGNORE_CASE
            ).find(text)

            val urlMatch = Regex(
                """(\d+)[^\d]+sezon[^\d]+(\d+)[^\d]+b[oö]l[uü]m""",
                RegexOption.IGNORE_CASE
            ).find(href)

            val season: Int
            val episodeNumber: Int

            when {
                textMatch != null -> {
                    season = textMatch.groupValues[1].toInt()
                    episodeNumber = textMatch.groupValues[2].toInt()
                }
                urlMatch != null -> {
                    season = urlMatch.groupValues[1].toInt()
                    episodeNumber = urlMatch.groupValues[2].toInt()
                }
                href.contains("/izle") -> {
                    season = 0
                    episodeNumber = episodes.count { it.season == 0 } + 1
                }
                else -> continue
            }

            if (!seenEpisodes.add(href)) continue

            var episodeName = text.replace(
                Regex(
                    """^\d+\s*\.\s*Sezon\s+\d+\s*\.\s*Bölüm\s*""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            ).trim().removePrefix("(").removeSuffix(")").trim()

            if (episodeName.isBlank()) {
                episodeName = "Bölüm $episodeNumber"
            }

            episodes.add(
                newEpisode(href) {
                    this.name = episodeName
                    this.season = season
                    this.episode = episodeNumber
                }
            )
        }

        val sortedEpisodes = episodes.sortedWith(
            compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode ?: Int.MAX_VALUE }
        )

        return newTvSeriesLoadResponse(title, fixedUrl, TvType.TvSeries, sortedEpisodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = fixUrl(data)
        val doc = app.get(pageUrl).document

        var foundLink = false

        val iframeUrls = doc.select("iframe").mapNotNull { iframe ->
            val dataSrc = iframe.attr("data-src").trim()
            val src = iframe.attr("src").trim()

            val selected = when {
                dataSrc.isNotBlank() && dataSrc != "about:blank" -> dataSrc
                src.isNotBlank() && src != "about:blank" -> src
                else -> null
            }

            selected?.let {
                when {
                    it.startsWith("//") -> "https:$it"
                    it.startsWith("http://") || it.startsWith("https://") -> it
                    else -> fixUrl(it)
                }
            }
        }.distinct()

        for (iframeUrl in iframeUrls) {
            try {
                loadExtractor(iframeUrl, pageUrl, subtitleCallback, callback)
                foundLink = true
            } catch (_: Exception) {
                // diğer kaynakları denemeye devam et
            }
        }

        val videoUrls = doc.select("video[src], source[src]").mapNotNull { element ->
            element.attr("src").trim().takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        }.distinct()

        for (videoUrl in videoUrls) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name Video",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = pageUrl
                }
            )
            foundLink = true
        }

        val html = doc.html()

        Regex("""https://drive\.google\.com/file/d/[^"'\\\s<>]+""")
            .findAll(html)
            .map { it.value }
            .distinct()
            .forEach { driveUrl ->
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = driveUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                    }
                )
                foundLink = true
            }

        Regex(
            """https?://[^"'\\\s<>]+?\.(?:mp4|m3u8)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .map { it.value.replace("&amp;", "&") }
            .distinct()
            .forEach { directUrl ->
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name Direct",
                        url = directUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = pageUrl
                    }
                )
                foundLink = true
            }

        return foundLink
    }

    private fun fixUrl(url: String): String {
        val value = url.trim()
        if (value.isBlank()) return mainUrl
        if (value.startsWith("//")) return "https:$value"
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        if (value.startsWith("/")) return mainUrl + value
        return "$mainUrl/$value"
    }
}
