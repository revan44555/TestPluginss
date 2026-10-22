package com.filmmakinesi

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
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
        // Ana sayfa (birinci sayfa): sayfadaki tema bolumlerini ayri ayri
        // baslikli listelere bol; oynatilamayan bolumleri ele. Bolum ayrisi
        // basarisiz olursa eski duz liste davranisina dus (guvenli geri dusus).
        if (page <= 1 && request.data.removeSuffix("/") == mainUrl) {
            val doc = try {
                getDoc(request.data)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Ana sayfa acilamadi (" + error.javaClass.simpleName + ")")
                null
            }
            if (doc != null) {
                try {
                    parseHomepageSections(doc)?.let { return it }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Bolum ayrisi basarisiz, duz listeye dusuluyor: " + error.javaClass.simpleName)
                }
            }
        }

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

    /**
     * Ana sayfayi tema bolumlerine ayirir: her bolum basligi kendi film
     * kartlariyla eslestirilir ve ayri baslikli bir CloudStream listesi
     * olur (or. "One Cikanlar", "Son Filmler"). Oynatilamayan bolumler
     * (yaklasan vizyon, dizi, bolum, fragman) elenir — bu icerikler
     * TvType.Movie provider'inda "baglanti bulunamadi" hatasina yol
     * aciyordu. Bolum yapisi cozulemezse null doner; duz listeye dusulur.
     */
    private fun parseHomepageSections(doc: Document): HomePageResponse? {
        val sections = LinkedHashMap<String, MutableList<SearchResponse>>()
        val headings = doc.select(
            "h1, h2, h3, .section-title, .items-head, .widget-title, " +
                "[class*=section-title], [class*=bolum]"
        )
        for (heading in headings) {
            val name = heading.text().trim().replace(Regex("\\s+"), " ")
            if (name.isBlank() || name.length > 60) continue
            if (FilmmakinesiParsing.isSkippedHomeSection(name)) continue
            // Nav/menusu/footer icindeki basliklar icerik bolumu degildir.
            if (heading.closest("nav, header, footer, [class*=menu], [class*=nav]") != null) continue
            // Film kartinin kendi basligi bolum basligi degildir: kart
            // basliklari filmin /film/ baglantisini tasir.
            val headingLink = heading.selectFirst("a[href]")
            if (headingLink != null && FilmmakinesiParsing.isFilmUrl(headingLink.attr("href"))) continue
            val scope = smallestScopeWithFilms(heading, minFilms = 3, maxUp = 5) ?: continue
            val cards = parseFilmCards(scope)
            if (cards.size >= 2) {
                sections.getOrPut(name) { mutableListOf() }.addAll(cards)
            }
        }
        if (sections.isEmpty()) return null
        // Dogrudan HomePageResponse yapicisi yerine MainAPI fabrikasi:
        // derlendigi CloudStream surumune gore dogru cagriyi icra eder.
        return newHomePageResponse(
            sections.map { (name, items) -> HomePageList(name, items) },
            hasNext = false
        )
    }

    /** Basliktan yukari cikarak icinde en az minFilms film baglantisi
     *  olan en kucuk kapsayiciyi dondurur — bolum blogunun kendisidir;
     *  daha yukari cikmak baska bolumleri yutup tek dev liste uretirdi. */
    private fun smallestScopeWithFilms(heading: Element, minFilms: Int, maxUp: Int): Element? {
        var node: Element? = heading
        var up = 0
        while (node != null && up <= maxUp) {
            val films = node.select("a[href]").count {
                FilmmakinesiParsing.isFilmUrl(it.attr("href"))
            }
            if (films >= minFilms) return node
            node = node.parent()
            up++
        }
        return null
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

    /**
     * Tek bir oyuncu embed'ini cozer. Iki katmanli strateji:
     *
     * 1) Yonlendirme zinciri: "yavas ag" interstitial'i gibi araci
     *    sayfalar akis barindirmaz; meta refresh / location.replace /
     *    location.assign ile gercek oyuncu sayfasina gonderir. Bu
     *    hedefleri takip edip (en fazla 3 adim) her adimda akis ara.
     *
     * 2) Cookie-then-reload: interstitial cookie set edip ayni URL'yi
     *    yeniden yukluyorsa yonlendirme hedefi bulunamaz; bu durumda
     *    kisa bir bekleyisle ayni sayfayi bir kez daha dene.
     *
     * Hicbir sey bulunamazsa teşhis amacli HTML basini loglar.
     */
    private suspend fun resolvePlayer(
        playerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val visited = LinkedHashSet<String>()
        var currentUrl = playerUrl
        var lastRaw = ""
        var lastStatus = 0
        var followedRedirect = false

        repeat(3) { hop ->
            if (!visited.add(currentUrl)) return@repeat
            val response = try {
                app.get(currentUrl, headers = headers + mapOf("Referer" to referer))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Embed sayfasina ulasilamadi (" + error.javaClass.simpleName + "), host=" + FilmmakinesiParsing.hostOf(currentUrl))
                return false
            }
            lastStatus = response.code
            if (!response.isSuccessful) {
                Log.w(TAG, "Embed sayfasi HTTP " + response.code + ", host=" + FilmmakinesiParsing.hostOf(currentUrl))
                return false
            }
            lastRaw = FilmmakinesiParsing.normalizeEscaped(response.text)
            val streams = FilmmakinesiParsing.extractStreamUrls(lastRaw)
            if (streams.isNotEmpty()) {
                // DUZELTME (logcat 2026-10-22 10:46): Akis bulundugu halde
                // hepsi olu yem (decoy) cikarsa emitStreams false doner.
                // Eskiden return ile kisa devre yapiliyor; script taramasi,
                // cookie-reload ve teshis adimlari hic calistirilmiyordu
                // (film2 icin logda teshis satirlarinin yoklugu bunu
                // kanitliyor). false durumunda kalan stratejilerle devam et.
                if (emitStreams(streams, playerUrl, referer, callback)) return true
            }
            val next = FilmmakinesiParsing.extractRedirectTargets(lastRaw)
                .map { FilmmakinesiParsing.safeAbsoluteUrl(it, currentUrl) }
                .firstOrNull { it !in visited }
            if (next == null) {
                // Yonlendirme yok: bu sayfa son durak (interstitial olabilir).
                return@repeat
            }
            Log.i(TAG, "Embed yonlendirmesi takip ediliyor (adim " + (hop + 1) + ", host=" + FilmmakinesiParsing.hostOf(next) + ")")
            followedRedirect = true
            currentUrl = next
        }

        // 3) Akis hala yok: JWPlayer kurulum JS'i sayfada gomulu degil,
        //    ayri bir <script src> dosyasinda olabilir. Ayni host'daki
        //    script'leri tarayip akis ara (en fazla 4 dosya).
        if (lastRaw.isNotEmpty()) {
            val scripts = FilmmakinesiParsing.extractScriptSrcs(lastRaw, playerUrl).take(4)
            for (script in scripts) {
                if (!visited.add(script)) continue
                val scriptResponse = try {
                    app.get(script, headers = headers + mapOf("Referer" to currentUrl))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    null
                }
                if (scriptResponse != null && scriptResponse.isSuccessful) {
                    val scriptText = FilmmakinesiParsing.normalizeEscaped(scriptResponse.text)
                    val scriptStreams = FilmmakinesiParsing.extractStreamUrls(scriptText)
                    if (scriptStreams.isNotEmpty()) {
                        Log.i(TAG, "Script icinde stream adayi bulundu (host=" + FilmmakinesiParsing.hostOf(script) + ")")
                        if (emitStreams(scriptStreams, playerUrl, referer, callback)) return true
                        Log.w(TAG, "Script stream adaylari oynatilamadi; diger kaynaklar denenecek (host=" + FilmmakinesiParsing.hostOf(script) + ")")
                    }
                }
            }
        }

        // 4) Interstitial cookie set edip ayni URL'yi yeniden yukluyor
        // olabilir; tek seferlik kisa bekleyisle son kez dene.
        if (lastRaw.isNotEmpty() && !followedRedirect) {
            // OkHttp yanittaki Set-Cookie basliklarini cevap donmeden once isler;
            // sunucu tarafli cookie icin bloklayan/artificial gecikme gerekmiyor.
            val retry = try {
                app.get(playerUrl, headers = headers + mapOf("Referer" to referer))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                null
            }
            if (retry != null && retry.isSuccessful) {
                lastRaw = FilmmakinesiParsing.normalizeEscaped(retry.text)
                val streams = FilmmakinesiParsing.extractStreamUrls(lastRaw)
                if (streams.isNotEmpty()) {
                    Log.i(TAG, "Cookie/reload sonrasi stream adayi bulundu (host=" + FilmmakinesiParsing.hostOf(playerUrl) + ")")
                    if (emitStreams(streams, playerUrl, referer, callback)) return true
                    Log.w(TAG, "Cookie/reload stream adaylari oynatilamadi; hata teshisine devam edilir")
                }
            }
        }

        // Teshis: embed HTML'inin basini logla (sitenin yeni oyuncusunun
        // yapisini bir sonraki logcat'te gormek icin). 300 yerine 2000
        // karakter: interstitial'lerde asil ipucu head/script bolumunde.
        // Logcat'e ham HTML, imzali stream URL'leri veya gizlenmis JS parcalarini yazma.
        val markers = listOf(
            "jwplayer", "setup(", "sources", "file:", "playlist", "atob", "eval(",
            "m3u8", "master", ".txt", "file_link", "reverse", "fromCharCode"
        ).filter { lastRaw.contains(it, ignoreCase = true) }
        Log.w(
            TAG,
            "Embed teshisi: HTTP=$lastStatus, bytes=${lastRaw.length}, host=${FilmmakinesiParsing.hostOf(playerUrl)}, markers=$markers"
        )
        Log.w(TAG, "Embed sayfasinda oynatilabilir akis bulunamadi (host=" + FilmmakinesiParsing.hostOf(playerUrl) + ")")
        return false
    }

    /** Cozulen akis URL'lerini CloudStream linklerine donusturur. */
    private suspend fun emitStreams(
        streams: List<String>,
        playerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = false
        for (streamUrl in streams) {
            // Her CDN icin kendi Origin degerini kullan; ilk stream'in host'unu
            // tum alternatiflere uygulamak bazi kaynaklarda 403 uretebilir.
            val streamHost = FilmmakinesiParsing.hostOf(streamUrl)
                .ifBlank { FilmmakinesiParsing.hostOf(playerUrl) }
            val origin = "https://" + streamHost
            // DUZELTME: HLS karari artik yalnizca ".m3u8" uzantisina
            // dayanmiyor; manifest'ler "/master.", "/index." ve "/hls/"
            // path'leriyle de sunulabiliyor (or.
            // hls*.playmix.uno/hls/...mp4/master.txt). Uzantisina bakip
            // yanlis siniflandirilan manifestler progressive VIDEO ile
            // acilmaya calisilip HTTP 404 aliyordu.
            if (FilmmakinesiParsing.isLikelyHls(streamUrl)) {
                // ".txt" uzantili manifest'ler statik yer tutucu (decoy)
                // olabiliyor: logcat'te uc farkli filmde ayni playmix
                // token'i dondu ve M3u8Helper icerigi playlist olmadigini
                // reddetti. Yayindan once ucuz bir HEAD ile 404/410
                // kontrolu yap; canli degilse hic link uretme.
                if (streamUrl.substringBefore("?").endsWith(".txt", ignoreCase = true)) {
                    // DUZELTME (logcat 2026-10-22 10:46): Yanit yalnizca
                    // .code icin acilip kapatilmadigi icin okhttp
                    // "response.body().close()" uyarisi veriyordu; her yem
                    // kontrolu bir baglanti sizdiriyordu. okhttpResponse
                    // Closeable oldugu icin use bloguyla garanti kapatin.
                    val headCode = try {
                        app.head(
                            streamUrl,
                            headers = mapOf(
                                "Referer" to referer,
                                "Origin" to origin,
                                "User-Agent" to (headers["User-Agent"] ?: "")
                            )
                        ).okhttpResponse.use { it.code }
                    } catch (error: Exception) {
                        -1
                    }
                    if (headCode == 404 || headCode == 410) {
                        Log.w(TAG, "Manifest kapali (HTTP " + headCode + "), host=" + FilmmakinesiParsing.hostOf(streamUrl))
                        continue
                    }
                }
                val links = M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = streamUrl,
                    referer = referer,
                    headers = mapOf(
                        "Referer" to referer,
                        "Origin" to origin,
                        "User-Agent" to (headers["User-Agent"] ?: "")
                    ),
                    name = "FM " + FilmmakinesiParsing.playerLabel(playerUrl)
                )
                for (link in links) {
                    callback(link)
                    emitted = true
                }
            } else {
                // Progressive mp4 (veya diger dogrudan video)
                callback(
                    newExtractorLink(
                        source = name,
                        name = "FM " + FilmmakinesiParsing.playerLabel(playerUrl),
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                    }
                )
                emitted = true
            }
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

    private fun parseFilmCards(doc: Element): List<SearchResponse> {
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
