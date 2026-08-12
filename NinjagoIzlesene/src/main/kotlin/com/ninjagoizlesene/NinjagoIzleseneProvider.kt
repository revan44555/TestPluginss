package com.ninjagoizlesene

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
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
            val href = link.absUrl("href")

            if (href.isBlank()) continue
            if (!href.contains("/dizi/")) continue
            if (!seen.add(href)) continue

            val title = link.text().trim()
                .ifBlank {
                    link.attr("title").trim()
                }
                .ifBlank {
                    link.select("img").attr("alt").trim()
                }

            if (title.isBlank()) continue
            if (!title.contains(query, ignoreCase = true)) continue

            val poster: String? = link
                .select("img")
                .attr("src")
                .ifBlank {
                    link.select("img").attr("data-src")
                }
                .ifBlank {
                    null
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
        val doc = app.get(url).document

        val title = doc.select("h1").text().trim()
            .ifBlank {
                doc.select("title").text()
                    .substringBefore("|")
                    .trim()
            }
            .ifBlank {
                "Ninjago"
            }

        val poster: String? =
            doc.select("meta[property=og:image]")
                .attr("content")
                .ifBlank {
                    doc.select("img").attr("src")
                }
                .ifBlank {
                    doc.select("img").attr("data-src")
                }
                .ifBlank {
                    null
                }

        val plot: String? =
            doc.select("meta[name=description]")
                .attr("content")
                .ifBlank {
                    null
                }

        val episodeList = mutableListOf<Episode>()
        val episodeLinks = doc.select("a[href*=/izle]")

        var episodeNumber = 1

        for (epLink in episodeLinks) {
            val href = epLink.absUrl("href")

            if (href.isBlank()) continue

            val epName = epLink.text()
                .trim()
                .ifBlank {
                    "Bölüm $episodeNumber"
                }

            episodeList.add(
                newEpisode(href) {
                    this.name = epName
                    this.episode = episodeNumber
                }
            )

            episodeNumber++
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodeList
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

        val doc = app.get(data).document

        var foundLink = false

        // iframe video kaynakları
        for (iframe in doc.select("iframe")) {

            val iframeUrl = iframe
                .attr("src")
                .ifBlank {
                    iframe.attr("data-src")
                }

            if (iframeUrl.isBlank()) continue

            val absoluteIframeUrl = if (iframeUrl.startsWith("http")) {
                iframeUrl
            } else {
                mainUrl.trimEnd('/') + "/" + iframeUrl.trimStart('/')
            }

            try {
                loadExtractor(
                    absoluteIframeUrl,
                    data,
                    subtitleCallback,
                    callback
                )

                foundLink = true
            } catch (_: Exception) {
                // Bir iframe çalışmazsa diğerlerini denemeye devam et.
            }
        }

        // Google Drive bağlantıları
        val pageText = doc.html()

        val driveRegex = Regex(
            """https://drive\.google\.com/file/d/[^"'\s<>]+"""
        )

        for (match in driveRegex.findAll(pageText)) {

            val driveUrl = match.value
                .trimEnd('"', '\'', ')', ']', '>')

            try {
                loadExtractor(
                    driveUrl,
                    data,
                    subtitleCallback,
                    callback
                )

                foundLink = true
            } catch (_: Exception) {
                // Drive extractor desteklenmiyorsa diğer kaynakları dene.
            }
        }

        return foundLink
    }
} 
