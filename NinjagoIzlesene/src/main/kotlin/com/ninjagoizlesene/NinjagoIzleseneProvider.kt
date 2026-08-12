package com.ninjagoizlesene

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class NinjagoIzleseneProvider : MainAPI() {
    override var mainUrl = "https://ninjagoizlesene.com.tr"
    override var name = "Ninjago İzlesene"
    override var supportedTypes = setOf(TvType.TvSeries)
    override var lang = "tr"

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/dizi-arsivi/").document
        val links = doc.select("a[href*=/dizi/]")

        val seen = HashSet<String>()
        val results = mutableListOf<SearchResponse>()

        for (link in links) {
            val href = link.attr("href")
            if (!href.contains("/dizi/")) continue
            if (!seen.add(href)) continue

            val title = link.text().ifBlank {
                link.attr("title").ifBlank { link.selectFirst("img")?.attr("alt") ?: "" }
            }
            if (title.isBlank()) continue
            if (!title.contains(query, ignoreCase = true)) continue

            val poster = link.selectFirst("img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            }

            results.add(
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            )
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore("|")?.trim()
            ?: "Ninjago"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img")?.let { it.attr("src").ifBlank { it.attr("data-src") } }

        val plot = doc.selectFirst("meta[name=description]")?.attr("content")

        val episodeList = mutableListOf<Episode>()
        val episodeLinks = doc.select("a[href*=/izle]")

        for ((index, epLink) in episodeLinks.withIndex()) {
            val href = epLink.attr("href")
            if (href.isBlank()) continue

            val epName = epLink.text().trim().ifBlank { "Bölüm ${index + 1}" }

            episodeList.add(
                newEpisode(href) {
                    this.name = epName
                    this.episode = index + 1
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
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
        val doc = app.get(data).document

        var foundLink = false

        val iframes = doc.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }
        for (iframe in iframes) {
            loadExtractor(iframe, data, subtitleCallback, callback)
            foundLink = true
        }

        val pageText = doc.html()
        val driveRegex = Regex("""https://drive\.google\.com/file/d/[^"'\s]+""")
        driveRegex.findAll(pageText).forEach { match ->
            val driveUrl = match.value
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

        return foundLink
    }
}
