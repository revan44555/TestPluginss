package com.ninjagoizlesene

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

class NinjagoIzleseneProvider : MainAPI() {

    override var mainUrl = "https://ninjagoizlesene.com.tr"
    override var name = "Ninjago İzlesene"
    override var lang = "tr"

    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val searchQuery = query.trim()

        if (searchQuery.isBlank()) {
            return emptyList()
        }

        val doc = app.get(
            "$mainUrl/dizi-arsivi/"
        ).document

        val results = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()

        for (link in doc.select("a[href]")) {

            val rawHref = link.attr("href").trim()

            if (rawHref.isBlank()) {
                continue
            }

            val href = fixUrl(rawHref)

            if (!href.contains("/dizi/")) {
                continue
            }

            val path = href.substringAfter(mainUrl)

            val pathParts = path
                .trim('/')
                .split('/')
                .filter { it.isNotBlank() }

            if (pathParts.size != 2) {
                continue
            }

            if (pathParts[0] != "dizi") {
                continue
            }

            if (!seen.add(href)) {
                continue
            }

            var title = link.text()
                .replace(Regex("\\s+"), " ")
                .trim()

            if (title.isBlank()) {
                title = link.attr("title")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }

            if (title.isBlank()) {
                title = link.selectFirst("img")
                    ?.attr("alt")
                    ?.replace(Regex("\\s+"), " ")
                    ?.trim()
                    ?: ""
            }

            if (title.isBlank()) {
                title = pathParts[1]
                    .replace("-", " ")
                    .replace("_", " ")
                    .split(" ")
                    .joinToString(" ") {
                        it.replaceFirstChar { c ->
                            c.uppercase()
                        }
                    }
            }

            if (title.isBlank()) {
                continue
            }

            if (!title.contains(searchQuery, ignoreCase = true)) {
                continue
            }

            val poster = link.selectFirst("img")?.let { img ->

                img.attr("src")
                    .ifBlank {
                        img.attr("data-src")
                    }
                    .ifBlank {
                        img.attr("data-lazy-src")
                    }
                    .ifBlank {
                        img.attr("data-original")
                    }
                    .takeIf {
                        it.isNotBlank()
                    }
                    ?.let {
                        fixUrl(it)
                    }
            }

            results.add(
                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                ) {
                    this.posterUrl = poster
                }
            )
        }

        return results
    }

    override suspend fun load(
        url: String
    ): LoadResponse {

        val fixedUrl = fixUrl(url)

        val doc = app.get(fixedUrl).document

        val title =
            doc.selectFirst("h1")
                ?.text()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: doc.selectFirst(
                    "meta[property='og:title']"
                )
                    ?.attr("content")
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                ?: "Ninjago"

        val poster =
            doc.selectFirst(
                "meta[property='og:image']"
            )
                ?.attr("content")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let {
                    fixUrl(it)
                }
                ?: doc.selectFirst("img")
                    ?.let { img ->

                        img.attr("src")
                            .ifBlank {
                                img.attr("data-src")
                            }
                            .ifBlank {
                                img.attr("data-lazy-src")
                            }
                            .ifBlank {
                                img.attr("data-original")
                            }
                            .takeIf {
                                it.isNotBlank()
                            }
                            ?.let {
                                fixUrl(it)
                            }
                    }

        val plot =
            doc.selectFirst(
                "meta[name='description']"
            )
                ?.attr("content")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: doc.selectFirst(
                    "meta[property='og:description']"
                )
                    ?.attr("content")
                    ?.trim()

        val episodes =
            mutableListOf<Episode>()

        val seenEpisodes =
            HashSet<String>()

        for (link in doc.select("a[href]")) {

            val rawHref =
                link.attr("href").trim()

            if (rawHref.isBlank()) {
                continue
            }

            val href =
                fixUrl(rawHref)

            if (href == fixedUrl) {
                continue
            }

            val text =
                link.text()
                    .replace(Regex("\\s+"), " ")
                    .trim()

            if (text.isBlank()) {
                continue
            }

            val match =
                Regex(
                    """(\d+)\s*\.\s*Sezon\s+(\d+)\s*\.\s*Bölüm""",
                    RegexOption.IGNORE_CASE
                ).find(text)

            val urlMatch =
                Regex(
                    """(\d+)[^\d]+sezon[^\d]+(\d+)[^\d]+b[oö]l[uü]m""",
                    RegexOption.IGNORE_CASE
                ).find(href)

            val season: Int
            val episodeNumber: Int

            if (match != null) {

                season =
                    match.groupValues[1].toInt()

                episodeNumber =
                    match.groupValues[2].toInt()

            } else if (urlMatch != null) {

                season =
                    urlMatch.groupValues[1].toInt()

                episodeNumber =
                    urlMatch.groupValues[2].toInt()

            } else {
                continue
            }

            if (!seenEpisodes.add(href)) {
                continue
            }

            var episodeName =
                text.replace(
                    Regex(
                        """^\d+\s*\.\s*Sezon\s+\d+\s*\.\s*Bölüm\s*""",
                        RegexOption.IGNORE_CASE
                    ),
                    ""
                ).trim()

            episodeName =
                episodeName
                    .removePrefix("(")
                    .removeSuffix(")")
                    .trim()

            if (episodeName.isBlank()) {
                episodeName =
                    "Bölüm $episodeNumber"
            }

            episodes.add(
                newEpisode(href) {

                    this.name =
                        episodeName

                    this.season =
                        season

                    this.episode =
                        episodeNumber
                }
            )
        }

        val sortedEpisodes =
            episodes.sortedWith(
                compareBy<Episode> {
                    it.season ?: Int.MAX_VALUE
                }.thenBy {
                    it.episode ?: Int.MAX_VALUE
                }
            )

        return newTvSeriesLoadResponse(
            title,
            fixedUrl,
            TvType.TvSeries,
            sortedEpisodes
        ) {

            this.posterUrl =
                poster

            this.plot =
                plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val pageUrl =
            fixUrl(data)

        val doc =
            app.get(pageUrl).document

        var foundLink =
            false

        /*
         * Player 2:
         *
         * src="about:blank"
         *
         * gerçek kaynak:
         *
         * data-src="//ok.ru/videoembed/..."
         *
         */

        val iframeUrls =
            doc.select("iframe")
                .mapNotNull { iframe ->

                    val dataSrc =
                        iframe.attr("data-src")
                            .trim()

                    val src =
                        iframe.attr("src")
                            .trim()

                    val selected =
                        when {

                            dataSrc.isNotBlank() &&
                                dataSrc != "about:blank" ->
                                dataSrc

                            src.isNotBlank() &&
                                src != "about:blank" ->
                                src

                            else ->
                                null
                        }

                    selected?.let {

                        when {

                            it.startsWith("//") ->
                                "https:$it"

                            it.startsWith("http://") ||
                                it.startsWith("https://") ->
                                it

                            else ->
                                fixUrl(it)
                        }
                    }
                }
                .distinct()

        for (iframeUrl in iframeUrls) {

            try {

                loadExtractor(
                    iframeUrl,
                    pageUrl,
                    subtitleCallback,
                    callback
                )

                foundLink =
                    true

            } catch (_: Exception) {
                // Diğer playerları denemeye devam et.
            }
        }

        /*
         * Doğrudan video/source kaynakları.
         */

        val videoUrls =
            doc.select(
                "video[src], source[src]"
            )
                .mapNotNull { element ->

                    element
                        .attr("src")
                        .trim()
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            fixUrl(it)
                        }
                }
                .distinct()

        for (videoUrl in videoUrls) {

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name Video",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer =
                        pageUrl
                }
            )

            foundLink =
                true
        }

        /*
         * HTML içinde doğrudan MP4/M3U8 varsa.
         */

        val html =
            doc.html()

        val directUrls =
            Regex(
                """https?://[^"'\\\s<>]+?\.(?:mp4|m3u8)(?:\?[^"'\\\s<>]*)?""",
                RegexOption.IGNORE_CASE
            )
                .findAll(html)
                .map {
                    it.value
                }
                .map {
                    it.replace(
                        "&amp;",
                        "&"
                    )
                }
                .distinct()
                .toList()

        for (directUrl in directUrls) {

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name Direct",
                    url = directUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer =
                        pageUrl
                }
            )

            foundLink =
                true
        }

        return foundLink
    }

    private fun fixUrl(
        url: String
    ): String {

        val value =
            url.trim()

        if (value.isBlank()) {
            return mainUrl
        }

        if (value.startsWith("//")) {
            return "https:$value"
        }

        if (
            value.startsWith("http://") ||
            value.startsWith("https://")
        ) {
            return value
        }

        if (value.startsWith("/")) {
            return mainUrl + value
        }

        return "$mainUrl/$value"
    }
}
