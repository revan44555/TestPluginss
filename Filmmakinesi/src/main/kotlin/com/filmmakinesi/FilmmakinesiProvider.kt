package com.filmmakinesi

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException

/**
 * FilmMakinesi provider.
 *
 * Turkish movie site with server-rendered pages, so everything is plain
 * okhttp + jsoup scraping: no WebView, no tokens, no cookie games. Players
 * live on first-party subdomains (rapid.filmmakinesi.to/embed-...,
 * closeload.filmmakinesi.to/video/embed/...); each embed page carries the
 * HLS manifest URL in its markup, and the "Alternatif" tabs give natural
 * redundancy per film.
 */
class FilmmakinesiProvider : MainAPI() {
    override var mainUrl = "https://filmmakinesi.to"
    override var name = "FilmMakinesi"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ana Sayfa",
        "$mainUrl/filmler-1/" to "Tüm Filmler"
    )

    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val base = request.data
        val pageUrl = if (page > 1 && base.endsWith("/filmler-1/")) {
            base.replace("/filmler-1/", "/filmler-$page/")
        } else {
            base
        }
        val doc = try {
            getDoc(pageUrl)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Liste sayfasi acilamadi (" + error.javaClass.simpleName + "): " + pageUrl)
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val items = parseFilmCards(doc)
        return newHomePageResponse(
            request.name,
            items,
            hasNext = pageUrl.contains("/filmler-") && items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val doc = try {
            getDoc("$mainUrl/?s=$encoded")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Arama sayfasi acilamadi (" + error.javaClass.simpleName + ")")
            return emptyList()
        }
        return parseFilmCards(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = getDoc(url)
        // DUZELTME: Baslik hic bulunamazsa IllegalStateException firlatici
        // kod vardi; bu, CloudStream tarafinda hata/cokus olarak
        // goruntuleniyordu. Artik URL slug'indan turetilmis guvenli bir
        // geri dusus (fallback) baslik kullaniliyor.
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: titleFromUrl(url)
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let { FilmmakinesiParsing.safeAbsoluteUrl(it, mainUrl) }
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val infoRoot = doc.selectFirst("h1")?.parent()
        val infoText = infoRoot?.text() ?: doc.text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            // DUZELTME: Once metindeki ilk yil aliniyordu; artik once URL
            // slug'indaki "-YYYY/" kalibi deneniyor, o yoksa metne dusuluyor.
            this.year = FilmmakinesiParsing.extractYear(infoText, url)
            FilmmakinesiParsing.extractImdb(infoText)?.let { this.score = Score.from10(it) }
        }
    }

    /**
     * Re-fetch the film page (cheap, server-rendered) and walk every
     * first-party player embed, exposing each HLS manifest CloudStream's
     * M3u8Helper can expand. Any alternatif that resolves counts as success;
     * failures are logged per player for the next iteration.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = getDoc(data)
        val rawHtml = FilmmakinesiParsing.normalizeEscaped(doc.html())

        val players = LinkedHashSet<String>()
        FilmmakinesiParsing.extractPlayerUrls(rawHtml).forEach {
            players.add(FilmmakinesiParsing.safeAbsoluteUrl(it, mainUrl))
        }
        // Defensive fallback: any first-party iframe not caught above.
        for (frame in doc.select("iframe[src]")) {
            val absolute = FilmmakinesiParsing.safeAbsoluteUrl(frame.attr("src"), mainUrl)
            if (FilmmakinesiParsing.isPlayerUrl(absolute)) players.add(absolute)
        }

        if (players.isEmpty()) {
            Log.w(TAG, "Sayfada oynatici bulunamadi: " + data)
            return false
        }

        var found = false
        for (player in players) {
            try {
                if (resolvePlayer(player, data, callback)) {
                    found = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Oynatici cozulemedi (" + error.javaClass.simpleName + "): " + player)
            }
        }
        if (!found) {
            Log.w(TAG, "Hicbir alternatif oynatilabilir kaynak vermedi: " + data)
        }
        return found
    }

    private suspend fun resolvePlayer(
        playerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(playerUrl, headers = headers + mapOf("Referer" to referer))
        if (!response.isSuccessful) {
            Log.w(TAG, "Embed sayfasi HTTP " + response.code + ": " + playerUrl)
            return false
        }
        val raw = FilmmakinesiParsing.normalizeEscaped(response.text)
        val manifest = FilmmakinesiParsing.extractM3u8(raw)
        if (manifest == null) {
            Log.w(TAG, "Embed sayfasinda m3u8 bulunamadi: " + playerUrl)
            return false
        }

        val origin = runCatching {
            "https://" + FilmmakinesiParsing.hostOf(manifest)
        }.getOrDefault("https://" + FilmmakinesiParsing.hostOf(playerUrl))

        val links = M3u8Helper.generateM3u8(
            source = name,
            streamUrl = manifest,
            referer = referer,
            headers = mapOf(
                "Referer" to referer,
                "Origin" to origin,
                "User-Agent" to (headers["User-Agent"] ?: "")
            ),
            name = "FM " + FilmmakinesiParsing.playerLabel(playerUrl)
        )
        var emitted = false
        for (link in links) {
            callback(link)
            emitted = true
        }
        return emitted
    }

    private suspend fun getDoc(url: String): Document {
        val response = app.get(url, headers = headers + mapOf("Referer" to mainUrl))
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP " + response.code + ": " + url)
        }
        return response.document
    }

    private fun parseFilmCards(doc: Document): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<SearchResponse>()
        for (anchor in doc.select("a[href]")) {
            val href = anchor.attr("href")
            if (!FilmmakinesiParsing.isFilmUrl(href)) continue
            val url = FilmmakinesiParsing.safeAbsoluteUrl(href, mainUrl)
            if (!seen.add(url)) continue

            val card = anchor.closest("article, li, div")
            val img = card?.selectFirst("img")
            val title = anchor.attr("title").ifBlank {
                img?.attr("alt")?.trim().orEmpty()
            }.ifBlank { anchor.text().trim() }
            if (title.isBlank()) continue

            val cardText = card?.text().orEmpty()
            out.add(newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = img?.let { bestImage(it) }
                // DUZELTME: Kart metnindeki ilk yil yerine once URL slug'i
                // deneniyor (detay icin FilmmakinesiParsing.extractYear).
                this.year = FilmmakinesiParsing.extractYear(cardText, url)
                FilmmakinesiParsing.extractImdb(cardText)?.let { this.score = Score.from10(it) }
            })
        }
        return out
    }

    /**
     * URL slug'indan gorunur bir baslik turetir (or. ".../film/disco-2026/"
     * -> "Disco"). Yil eki ve kalici anlam tasimayan artik kelimeler
     * dusurulur; hicbir sey cikmazsa "Film" geri dususunu dondurur.
     */
    private fun titleFromUrl(url: String): String {
        val slug = url.trimEnd('/').substringAfterLast('/')
        val words = slug.split("-")
            .filter { it.isNotBlank() }
            .filterNot { it.toIntOrNull() != null } // yil/numara eklerini at
        if (words.isEmpty()) return "Film"
        return words.joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun bestImage(img: Element): String? {
        val candidates = listOfNotNull(
            img.attr("data-src").ifBlank { null },
            img.attr("data-original").ifBlank { null },
            img.attr("data-lazy-src").ifBlank { null },
            img.attr("src").ifBlank { null }
        )
        return candidates.firstOrNull()?.let { FilmmakinesiParsing.safeAbsoluteUrl(it, mainUrl) }
    }

    companion object {
        private const val TAG = "Filmmakinesi"
    }
}
