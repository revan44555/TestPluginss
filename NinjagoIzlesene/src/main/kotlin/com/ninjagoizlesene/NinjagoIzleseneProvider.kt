package com.ninjagoizlesene

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app

class NinjagoIzleseneProvider : MainAPI() {

    override var mainUrl = "https://ninjagoizlesene.com.tr"
    override var name = "Ninjago İzlesene"
    override var lang = "tr"

    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    override suspend fun search(query: String): List<SearchResponse> {

        val searchQuery = query.trim()

        if (searchQuery.isBlank()) {
            return emptyList()
        }

        val doc = app.get(
            "$mainUrl/dizi-arsivi/"
        ).document

        val results = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()

        /*
         * Sitedeki dizi arşivindeki bütün bağlantıları alıyoruz.
         * Önceki kodda kullanılan:
         *
         * a[href*=/dizi/]
         *
         * yerine daha toleranslı bir seçim yapıyoruz.
         */
        for (link in doc.select("a[href]")) {

            val rawHref = link.attr("href").trim()

            if (rawHref.isBlank()) {
                continue
            }

            val href = fixUrl(rawHref)

            /*
             * Sadece /dizi/ bağlantıları.
             */
            if (!href.contains("/dizi/")) {
                continue
            }

            /*
             * Bölüm bağlantılarını dizi sonucu olarak almamak için
             * /dizi/slug/ şeklindeki ana dizi URL'lerini tercih ediyoruz.
             */
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

            /*
             * Önce link metnini deniyoruz.
             */
            var title = link.text()
                .replace(Regex("\\s+"), " ")
                .trim()

            /*
             * Link metni boşsa img alt/title kullan.
             */
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

            /*
             * Hâlâ başlık yoksa URL slug'ından başlık üret.
             *
             * Örneğin:
             * /dizi/ejderhalarin-yukselisi/
             *
             * -> Ejderhalarin Yukselisi
             */
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

            /*
             * Gerçek arama.
             */
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
                    .takeIf { it.isNotBlank() }
                    ?.let { fixUrl(it) }
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

    override suspend fun load(url: String): LoadResponse {

        val fixedUrl = fixUrl(url)

        val doc = app.get(fixedUrl).document

        /*
         * Dizi adı.
         */
        val title =
            doc.selectFirst("h1")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property='og:title']")
                    ?.attr("content")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: "Ninjago"

        /*
         * Poster.
         */
        val poster =
            doc.selectFirst("meta[property='og:image']")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
                ?: doc.selectFirst("img")
                    ?.let { img ->
                        img.attr("src")
                            .ifBlank {
                                img.attr("data-src")
                            }
                            .takeIf { it.isNotBlank() }
                            ?.let { fixUrl(it) }
                    }

        /*
         * Açıklama.
         */
        val plot =
            doc.selectFirst("meta[name='description']")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property='og:description']")
                    ?.attr("content")
                    ?.trim()

        val episodes = mutableListOf<Episode>()

        val seenEpisodes = HashSet<String>()

        /*
         * Site dizi sayfalarında bölümler:
         *
         * 1. Sezon 1. Bölüm (...)
         * 1. Sezon 2. Bölüm (...)
         *
         * şeklinde gerçek <a> bağlantıları olarak bulunuyor.
         */
        for (link in doc.select("a[href]")) {

            val rawHref = link.attr("href").trim()

            if (rawHref.isBlank()) {
                continue
            }

            val href = fixUrl(rawHref)

            if (href == fixedUrl) {
                continue
            }

            val text = link.text()
                .replace(Regex("\\s+"), " ")
                .trim()

            if (text.isBlank()) {
                continue
            }

            /*
             * Örneğin:
             *
             * "1. Sezon 1. Bölüm (Birleşme: 1. Kısım)"
             */
            val match = Regex(
                """(\d+)\s*\.\s*Sezon\s+(\d+)\s*\.\s*Bölüm"""
            ).find(text)

            /*
             * Bazı sayfalarda link metni farklı olabilir.
             * URL içinde bölüm bilgisi varsa onu da deniyoruz.
             */
            val urlMatch =
                Regex(
                    """(\d+)[^\d]+sezon[^\d]+(\d+)[^\d]+b[oö]l[uü]m""",
                    RegexOption.IGNORE_CASE
                ).find(href)

            val season: Int
            val episodeNumber: Int

            if (match != null) {
                season = match.groupValues[1].toInt()
                episodeNumber = match.groupValues[2].toInt()
            } else if (urlMatch != null) {
                season = urlMatch.groupValues[1].toInt()
                episodeNumber = urlMatch.groupValues[2].toInt()
            } else {

                /*
                 * Film veya bölüm olmayan bağlantıları atla.
                 */
                continue
            }

            /*
             * Aynı bölümü iki kez eklemeyelim.
             */
            if (!seenEpisodes.add(href)) {
                continue
            }

            /*
             * Bölüm adı.
             *
             * Örneğin:
             * 1. Sezon 1. Bölüm (Canavara Dönüşmek)
             *
             * -> Canavara Dönüşmek
             */
            var episodeName = text

            episodeName = episodeName
                .replace(
                    Regex(
                        """^\d+\s*\.\s*Sezon\s+\d+\s*\.\s*Bölüm\s*"""
                    ),
                    ""
                )
                .trim()

            episodeName = episodeName
                .removePrefix("(")
                .removeSuffix(")")
                .trim()

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

        /*
         * Bölümleri sezon + bölüm numarasına göre sırala.
         */
        val sortedEpisodes = episodes.sortedWith(
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

        /*
         * 1. iframe kaynaklarını bul.
         */
        val iframeUrls = doc
            .select("iframe[src]")
            .mapNotNull { iframe ->

                iframe
                    .attr("src")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { fixUrl(it) }
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

                foundLink = true

            } catch (_: Exception) {
                /*
                 * Bir iframe çalışmazsa diğer kaynakları denemeye devam.
                 */
            }
        }

        /*
         * 2. <video src=""> kaynaklarını bul.
         */
        val videoUrls = doc
            .select("video[src], source[src]")
            .mapNotNull { element ->

                element
                    .attr("src")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { fixUrl(it) }
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
                    this.referer = pageUrl
                }
            )

            foundLink = true
        }

        /*
         * 3. HTML içinde doğrudan MP4 / M3U8 URL'leri varsa yakala.
         */
        val html = doc.html()

        val directUrls = Regex(
            """https?://[^"'\\\s<>]+?\.(?:mp4|m3u8)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        )
            .findAll(html)
            .map { it.value }
            .map { it.replace("&amp;", "&") }
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
                    this.referer = pageUrl
                }
            )

            foundLink = true
        }

        return foundLink
    }
}
