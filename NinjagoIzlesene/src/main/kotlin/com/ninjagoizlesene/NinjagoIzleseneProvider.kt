package com.ninjagoizlesene

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import android.util.Log

class NinjagoIzleseneProvider : MainAPI() {
    override var mainUrl = "https://ninjagoizlesene.com.tr"
    override var name = "Ninjago İzlesene"
    override var lang = "tr"
    override val hasMainPage = true

    // Site sabit tek bir arşiv sayfasına sahip (sayfalama/kategori yok),
    // bu yüzden tek bir giriş yeterli.
    override val mainPage = mainPageOf(
        "https://ninjagoizlesene.com.tr/dizi-arsivi/" to "Dizi Arşivi"
    )

    // Site tek bir sabit "LEGO Ninjago" evreni etrafında kurulu; hem dizi
    // (mini seriler, sezonlar) hem de tek film ("LEGO Ninjago Filmi") içeriyor.
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    // ---------------------------------------------------------------
    // Google Drive kaynağı (elle yüklenen bölümler)
    // ---------------------------------------------------------------
    // Ana klasör: sezon klasörleri içeriyor, her sezon klasöründe bölümler
    // "01", "02" ... şeklinde numaralı dosya adlarıyla duruyor.
    private val driveRootFolderId = "1apHuLaPeIzGqIaHMTaglBrCErhdHQoKo"
    private val driveApiKey = "AIzaSyCw33oyN8L8YRyyFH_MjSveP_TsOJ0bJBc"

    // Basit bellek-içi önbellek: klasör taramaları tekrar tekrar yapılmasın.
    private val driveSeasonFolderCache = HashMap<Int, String?>()
    private var driveRootScanned = false

    private data class DriveFile(val id: String, val name: String, val mimeType: String)

    private suspend fun driveListChildren(folderId: String): List<DriveFile> {
        Log.d("NinjagoIzlesene", "DRIVE-DEBUG: driveListChildren çağrıldı, folderId=$folderId")
        return runCatching {
            val url = "https://www.googleapis.com/drive/v3/files" +
                "?q=" + java.net.URLEncoder.encode("'$folderId' in parents and trashed = false", "UTF-8") +
                "&fields=" + java.net.URLEncoder.encode("files(id,name,mimeType)", "UTF-8") +
                "&key=$driveApiKey"
            Log.d("NinjagoIzlesene", "DRIVE-DEBUG: istek atılıyor -> $url")
            val res = app.get(url).text
            Log.d("NinjagoIzlesene", "DRIVE-DEBUG: ham yanıt (ilk 500 karakter) = ${res.take(500)}")
            val json = org.json.JSONObject(res)
            if (json.has("error")) {
                Log.d("NinjagoIzlesene", "DRIVE-DEBUG: API HATA DÖNDÜ = ${json.optJSONObject("error")}")
            }
            val filesArray = json.optJSONArray("files")
            if (filesArray == null) {
                Log.d("NinjagoIzlesene", "DRIVE-DEBUG: yanıtta 'files' alanı YOK, boş liste dönülüyor")
                return@runCatching emptyList()
            }
            Log.d("NinjagoIzlesene", "DRIVE-DEBUG: 'files' alanında ${filesArray.length()} öğe bulundu")
            val result = (0 until filesArray.length()).map { i ->
                val obj = filesArray.getJSONObject(i)
                DriveFile(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    mimeType = obj.getString("mimeType")
                )
            }
            result.forEachIndexed { i, f ->
                Log.d("NinjagoIzlesene", "DRIVE-DEBUG: [$i] isim='${f.name}' mimeType='${f.mimeType}' id=${f.id}")
            }
            result
        }.onFailure {
            Log.d("NinjagoIzlesene", "DRIVE-DEBUG: driveListChildren İSTİSNA fırlattı ($folderId): ${it::class.simpleName}: ${it.message}")
        }.getOrDefault(emptyList())
    }

    // Sezon klasörünün adından sezon numarasını çıkarır (örn. "Sezon 1", "1. Sezon", "S1").
    private fun parseSeasonNumberFromFolderName(name: String): Int? {
        val patterns = listOf(
            Regex("""(\d+)\s*\.?\s*Sezon""", RegexOption.IGNORE_CASE),
            Regex("""Sezon\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""S(\d+)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            pattern.find(name)?.let {
                val num = it.groupValues[1].toIntOrNull()
                Log.d("NinjagoIzlesene", "DRIVE-DEBUG: parseSeasonNumberFromFolderName('$name') pattern='${pattern.pattern}' -> $num")
                return num
            }
        }
        Log.d("NinjagoIzlesene", "DRIVE-DEBUG: parseSeasonNumberFromFolderName('$name') -> HİÇBİR PATTERN EŞLEŞMEDİ, null")
        return null
    }

    // Bölüm dosyasının adından bölüm numarasını çıkarır (örn. "01", "02 - isim.mp4").
    private fun parseEpisodeNumberFromFileName(name: String): Int? {
        val match = Regex("""^0*(\d+)""").find(name.trim())
        val num = match?.groupValues?.get(1)?.toIntOrNull()
        Log.d("NinjagoIzlesene", "DRIVE-DEBUG: parseEpisodeNumberFromFileName('$name') -> $num")
        return num
    }

    private suspend fun findDriveSeasonFolderId(season: Int): String? {
        Log.d("NinjagoIzlesene", "DRIVE-DEBUG: findDriveSeasonFolderId(season=$season) çağrıldı, cache=$driveSeasonFolderCache, driveRootScanned=$driveRootScanned")
        if (driveSeasonFolderCache.containsKey(season)) {
            Log.d("NinjagoIzlesene", "DRIVE-DEBUG: cache'de bulundu -> ${driveSeasonFolderCache[season]}")
            return driveSeasonFolderCache[season]
        }
        if (!driveRootScanned) {
            driveRootScanned = true
            Log.d("NinjagoIzlesene", "DRIVE-DEBUG: ana klasör taranıyor, driveRootFolderId=$driveRootFolderId")
            val children = driveListChildren(driveRootFolderId)
            Log.d("NinjagoIzlesene", "DRIVE-DEBUG: ana klasörde ${children.size} öğe var")
            for (child in children) {
                Log.d("NinjagoIzlesene", "DRIVE-DEBUG: kontrol ediliyor -> isim='${child.name}' mimeType='${child.mimeType}'")
                if (child.mimeType != "application/vnd.google-apps.folder") {
                    Log.d("NinjagoIzlesene", "DRIVE-DEBUG: '${child.name}' klasör DEĞİL, atlanıyor")
                    continue
                }
                val seasonNum = parseSeasonNumberFromFolderName(child.name)
                if (seasonNum == null) {
                    Log.d("NinjagoIzlesene", "DRIVE-DEBUG: '${child.name}' klasöründen sezon numarası çıkarılamadı, atlanıyor")
                    continue
                }
                Log.d("NinjagoIzlesene", "DRIVE-DEBUG: sezon $seasonNum -> klasör id ${child.id} olarak kaydedildi")
                driveSeasonFolderCache[seasonNum] = child.id
            }
            Log.d("NinjagoIzlesene", "DRIVE-DEBUG: tarama bitti, cache=$driveSeasonFolderCache")
        }
        return driveSeasonFolderCache[season]
    }

    // Sezon/bölüm numarasına karşılık gelen Drive dosyasını bulup oynatılabilir
    // link olarak döndürür. Bulunamazsa null döner (site kaynaklarına devam edilir).
    private suspend fun resolveDriveEpisode(season: Int, episode: Int): String? {
        Log.d("NinjagoIzlesene", "DRIVE-DEBUG: resolveDriveEpisode(season=$season, episode=$episode) başladı")
        val seasonFolderId = findDriveSeasonFolderId(season)
        if (seasonFolderId == null) {
            Log.d("NinjagoIzlesene", "DRIVE-DEBUG: sezon $season için klasör BULUNAMADI, null dönülüyor")
            return null
        }
        Log.d("NinjagoIzlesene", "DRIVE-DEBUG: sezon $season klasör id=$seasonFolderId, dosyalar taranıyor")
        val files = driveListChildren(seasonFolderId)
        Log.d("NinjagoIzlesene", "DRIVE-DEBUG: sezon klasöründe ${files.size} dosya var")
        val match = files.firstOrNull {
            val isFolder = it.mimeType == "application/vnd.google-apps.folder"
            val epNum = parseEpisodeNumberFromFileName(it.name)
            Log.d("NinjagoIzlesene", "DRIVE-DEBUG: dosya '${it.name}' -> bölüm no=$epNum, klasör mü=$isFolder, aranan=$episode")
            !isFolder && epNum == episode
        }
        if (match == null) {
            Log.d("NinjagoIzlesene", "DRIVE-DEBUG: bölüm $episode ile eşleşen dosya BULUNAMADI")
            return null
        }
        val link = "https://drive.google.com/file/d/${match.id}/view"
        Log.d("NinjagoIzlesene", "DRIVE-DEBUG: eşleşme bulundu -> $link")
        return link
    }

    // Sayfa URL'sinden "N. Sezon M. Bölüm" bilgisini çıkarır.
    private val urlSeasonEpisodeRegex = Regex(
        """(\d+)[^\d]+sezon[^\d]+(\d+)[^\d]+b[oö]l[uü]m""",
        RegexOption.IGNORE_CASE
    )

    // ---------------------------------------------------------------
    // Dzen.ru (Yandex Zen) çözücüsü — DOKUNULMADI
    // ---------------------------------------------------------------
    private fun normalizeEscapedHtml(html: String): String {
        var result = html.replace("""\/""", "/")
        result = Regex("""\\u([0-9a-fA-F]{4})""").replace(result) { match ->
            val codePoint = match.groupValues[1].toInt(16)
            codePoint.toChar().toString()
        }
        result = result.replace("&amp;", "&")
        return result
    }

    private val dzenEmbedRegex = Regex("""https?://(?:www\.)?dzen\.ru/embed/[^"'\\\s<>]+""")

    private fun guessQualityLabel(context: String): Int {
        val qualityMatch = Regex("""(\d{3,4})p""").find(context)
        val heightGuess = qualityMatch?.groupValues?.get(1)?.toIntOrNull()
        return when {
            heightGuess == null -> com.lagradost.cloudstream3.utils.Qualities.Unknown.value
            heightGuess >= 1080 -> com.lagradost.cloudstream3.utils.Qualities.P1080.value
            heightGuess >= 720 -> com.lagradost.cloudstream3.utils.Qualities.P720.value
            heightGuess >= 480 -> com.lagradost.cloudstream3.utils.Qualities.P480.value
            heightGuess >= 360 -> com.lagradost.cloudstream3.utils.Qualities.P360.value
            else -> com.lagradost.cloudstream3.utils.Qualities.Unknown.value
        }
    }

    private suspend fun resolveDzenEmbed(
        embedUrl: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val fixedEmbedUrl = embedUrl.replace("&amp;", "&")
        val dzenHeaders = headers + mapOf(
            "Referer" to refererUrl,
            "Origin" to mainUrl,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site"
        )
        val embedResponse = runCatching {
            app.get(fixedEmbedUrl, headers = dzenHeaders)
        }.getOrNull()
        if (embedResponse == null) {
            Log.d("NinjagoIzlesene", "Dzen: embed sayfası hiç açılamadı: $fixedEmbedUrl")
            return false
        }
        if (!embedResponse.isSuccessful) {
            Log.d(
                "NinjagoIzlesene",
                "Dzen: embed sayfası HTTP ${embedResponse.code} döndü: $fixedEmbedUrl"
            )
            return false
        }
        val embedHtml = normalizeEscapedHtml(embedResponse.text)
        val m3u8Matches = Regex("""https?://[^"'\\\s<>]+?\.m3u8[^"'\\\s<>]*""")
            .findAll(embedHtml)
            .map { it.value }
            .distinct()
            .toList()
        if (m3u8Matches.isEmpty()) {
            Log.d(
                "NinjagoIzlesene",
                "Dzen: embed HTML'de .m3u8 bulunamadı (htmlLen=${embedHtml.length}): $fixedEmbedUrl"
            )
        }
        m3u8Matches.forEach { m3u8Url ->
            val idx = embedHtml.indexOf(m3u8Url)
            val contextStart = if (idx < 0) 0 else maxOf(0, idx - 120)
            val context = embedHtml.substring(
                contextStart,
                minOf(embedHtml.length, contextStart + 200)
            )
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name (Dzen)",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = fixedEmbedUrl
                    this.quality = guessQualityLabel(context)
                }
            )
            found = true
        }
        if (!found) {
            val mp4Matches = Regex("""https?://[^"'\\\s<>]+?\.mp4[^"'\\\s<>]*""")
                .findAll(embedHtml)
                .map { it.value }
                .distinct()
                .toList()
            if (mp4Matches.isEmpty()) {
                Log.d(
                    "NinjagoIzlesene",
                    "Dzen: embed HTML'de ne .m3u8 ne de .mp4 bulundu, çözüm başarısız: $fixedEmbedUrl"
                )
            }
            mp4Matches.forEach { mp4Url ->
                val idx = embedHtml.indexOf(mp4Url)
                val contextStart = if (idx < 0) 0 else maxOf(0, idx - 120)
                val context = embedHtml.substring(
                    contextStart,
                    minOf(embedHtml.length, contextStart + 200)
                )
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name (Dzen)",
                        url = mp4Url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = fixedEmbedUrl
                        this.quality = guessQualityLabel(context)
                    }
                )
                found = true
            }
        }
        return found
    }

    // ---------------------------------------------------------------
    // YouTube URL normalizasyonu
    // ---------------------------------------------------------------
    private val youtubeVideoIdRegex = Regex(
        """(?:youtube\.com/(?:embed/|watch\?v=|shorts/)|youtu\.be/)([a-zA-Z0-9_-]{6,})"""
    )

    private fun normalizeYoutubeUrl(url: String): String? {
        val videoId = youtubeVideoIdRegex.find(url)?.groupValues?.getOrNull(1)
            ?: return null
        return "https://www.youtube.com/watch?v=$videoId"
    }

    private suspend fun tryYoutubeExtractor(
        url: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val candidates = buildList {
            normalizeYoutubeUrl(url)?.let(::add)
            if (url !in this) add(url)
        }.distinct()
        for (candidate in candidates) {
            var linkCount = 0
            val countingCallback: (ExtractorLink) -> Unit = { link ->
                linkCount++
                callback(link)
            }
            val success = runCatching {
                loadExtractor(candidate, refererUrl, subtitleCallback, countingCallback)
            }.onFailure {
                Log.d(
                    "NinjagoIzlesene",
                    "YouTube extractor başarısız ($candidate): ${it.message}"
                )
            }.getOrDefault(false)
            Log.d(
                "NinjagoIzlesene",
                "YouTube extractor ($candidate): sonuç=$success, gerçek link sayısı=$linkCount"
            )
            if (linkCount > 0) return true
        }
        return false
    }

    // ---------------------------------------------------------------
    // YouTube -> Piped API yedek çözücüsü
    // ---------------------------------------------------------------
    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi-libre.kavin.rocks",
        "https://pipedapi.adminforge.de"
    )

    private suspend fun resolveYoutubeViaPiped(
        youtubeUrl: String,
        refererUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = youtubeVideoIdRegex.find(youtubeUrl)?.groupValues?.get(1)
        if (videoId == null) {
            Log.d("NinjagoIzlesene", "Piped: video ID çıkarılamadı: $youtubeUrl")
            return false
        }
        for (instance in pipedInstances) {
            val response = runCatching {
                app.get(
                    "$instance/streams/$videoId",
                    headers = mapOf(
                        "User-Agent" to headers.getValue("User-Agent"),
                        "Accept" to "application/json"
                    )
                )
            }.getOrNull()
            if (response == null || !response.isSuccessful) {
                Log.d(
                    "NinjagoIzlesene",
                    "Piped: $instance başarısız (kod=${response?.code}), sıradaki instance denenecek"
                )
                continue
            }
            val json = runCatching { org.json.JSONObject(response.text) }.getOrNull()
            if (json == null) {
                Log.d("NinjagoIzlesene", "Piped: $instance geçersiz JSON döndürdü")
                continue
            }
            var found = false
            val hlsUrl = json.optString("hls", null)
            if (!hlsUrl.isNullOrBlank()) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name (YouTube)",
                        url = hlsUrl,
                        type = ExtractorLinkType.M3U8
                    ) { this.referer = refererUrl }
                )
                found = true
            }
            if (!found) {
                val videoStreams = json.optJSONArray("videoStreams")
                if (videoStreams != null) {
                    for (i in 0 until videoStreams.length()) {
                        val stream = videoStreams.optJSONObject(i) ?: continue
                        if (stream.optBoolean("videoOnly", true)) continue
                        val streamUrl = stream.optString("url", null) ?: continue
                        val qualityLabel = stream.optString("quality", "")
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name (YouTube $qualityLabel)",
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = refererUrl
                                this.quality = guessQualityLabel(qualityLabel)
                            }
                        )
                        found = true
                    }
                }
            }
            if (found) {
                Log.d("NinjagoIzlesene", "Piped: $instance üzerinden çözüldü ($videoId)")
                return true
            } else {
                Log.d(
                    "NinjagoIzlesene",
                    "Piped: $instance yanıt verdi ama kullanılabilir stream yok, sıradaki instance denenecek"
                )
            }
        }
        Log.d("NinjagoIzlesene", "Piped: tüm instance'lar başarısız oldu ($videoId)")
        return false
    }

    // Film olarak işaretlenecek slug'lar (yalnızca "LEGO Ninjago Filmi").
    private val movieSlugs = setOf("lego-ninjago-filmi")
    private val archiveUrl = "$mainUrl/dizi-arsivi/"

    // ---------------------------------------------------------------
    // Ortak yardımcılar
    // ---------------------------------------------------------------
    private fun fixUrl(url: String): String {
        val value = url.trim()
        if (value.isBlank()) return mainUrl
        if (value.startsWith("//")) return "https:$value"
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        if (value.startsWith("/")) return mainUrl + value
        return "$mainUrl/$value"
    }

    private fun extractTitle(link: Element, fallbackSlug: String): String {
        var title = link.attr("title").replace(Regex("\\s+"), " ").trim()
        if (title.isBlank()) {
            title = link.text().replace(Regex("\\s+"), " ").trim()
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
                .replace("-", " ").replace("_", " ")
                .split(" ")
                .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        }
        return title
    }

    private fun isEpisodeLink(href: String, seriesSlug: String): Boolean {
        if (!href.startsWith(mainUrl)) return false
        val path = href.substringAfter(mainUrl).trim('/')
        val parts = path.split("/").filter { it.isNotBlank() }
        return parts.size >= 2 && parts[0] == seriesSlug
    }

    private fun extractPoster(link: Element): String? {
        val container = link.closest("article, div, li, section") ?: link.parent()
        val candidates = mutableListOf<Element>()
        candidates.addAll(link.select("img"))
        link.select("noscript").forEach { noscript ->
            candidates.addAll(org.jsoup.Jsoup.parse(noscript.data()).select("img"))
        }
        if (container != null) {
            candidates.addAll(container.select("img"))
            container.select("noscript").forEach { noscript ->
                candidates.addAll(org.jsoup.Jsoup.parse(noscript.data()).select("img"))
            }
        }
        for (img in candidates) {
            val src = img.attr("src")
                .ifBlank { img.attr("data-src") }
                .ifBlank { img.attr("data-lazy-src") }
                .ifBlank { img.attr("data-original") }
                .ifBlank { img.attr("data-lazy") }
                .ifBlank { img.attr("srcset").substringBefore(" ") }
                .ifBlank { img.attr("data-srcset").substringBefore(" ") }
                .ifBlank { img.attr("data-lazy-srcset").substringBefore(" ") }
                .trim()
            if (src.isNotBlank() && !src.startsWith("data:")) {
                return fixUrl(src)
            }
        }
        return null
    }

    private fun collectSeriesLinks(doc: Document): List<Pair<String, Element>> {
        val grouped = LinkedHashMap<String, MutableList<Element>>()
        for (link in doc.select("a[href]")) {
            val rawHref = link.attr("href").trim()
            if (rawHref.isBlank()) continue
            val href = fixUrl(rawHref)
            val path = href.substringAfter(mainUrl).trim('/')
            val parts = path.split("/").filter { it.isNotBlank() }
            if (parts.size != 2 || parts[0] != "dizi") continue
            grouped.getOrPut(href) { mutableListOf() }.add(link)
        }
        return grouped.map { (href, candidates) ->
            val withImage = candidates.firstOrNull { it.select("img").isNotEmpty() }
            href to (withImage ?: candidates.first())
        }
    }

    private fun toSearchResponse(href: String, link: Element): SearchResponse {
        val slug = href.substringAfter("/dizi/").trim('/')
        val title = extractTitle(link, slug)
        val poster = extractPoster(link)
        val type = if (slug in movieSlugs) TvType.Movie else TvType.TvSeries
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    // ---------------------------------------------------------------
    // Ana sayfa
    // ---------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = headers).document
        val seriesLinks = collectSeriesLinks(doc)
        val items = seriesLinks.map { (href, link) -> toSearchResponse(href, link) }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    // ---------------------------------------------------------------
    // Arama
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(archiveUrl, headers = headers)
        val doc = response.document
        val seriesLinks = collectSeriesLinks(doc)
        val totalLinks = doc.select("a[href]").size
        Log.d(
            "NinjagoIzlesene",
            "search('$query') diagnostic: httpCode=${response.code} " +
                "totalLinks=$totalLinks diziLinks=${seriesLinks.size} " +
                "bodyLen=${response.text.length} " +
                "bodyStart=${response.text.take(200).replace("\n", " ")}"
        )
        if (query.isBlank()) {
            return seriesLinks.map { (href, link) -> toSearchResponse(href, link) }
        }
        val normalizedQuery = normalizeTr(query)
        val filtered = seriesLinks.filter { (href, link) ->
            val slug = href.substringAfter("/dizi/").trim('/')
            val title = extractTitle(link, slug)
            normalizeTr(title).contains(normalizedQuery)
        }
        return filtered.map { (href, link) -> toSearchResponse(href, link) }
    }

    private fun normalizeTr(text: String): String {
        return text
            .lowercase()
            .replace("ı", "i")
            .replace("İ", "i")
            .trim()
    }

    // ---------------------------------------------------------------
    // Dizi / film detay sayfası
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url)
        val doc = app.get(fixedUrl, headers = headers).document
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
        val slug = fixedUrl.substringAfter("/dizi/").trim('/').substringBefore("/")
        if (slug in movieSlugs) {
            val playUrl = doc.select("a[href]")
                .map { fixUrl(it.attr("href").trim()) }
                .firstOrNull { isEpisodeLink(it, slug) }
                ?: fixedUrl
            return newMovieLoadResponse(title, fixedUrl, TvType.Movie, playUrl) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
        val episodes = extractEpisodes(doc, fixedUrl, slug)
        val episodesWithPoster = if (poster != null) {
            episodes.map { ep ->
                newEpisode(ep.data) {
                    this.name = ep.name
                    this.season = ep.season
                    this.episode = ep.episode
                    this.posterUrl = poster
                }
            }
        } else episodes
        return newTvSeriesLoadResponse(title, fixedUrl, TvType.TvSeries, episodesWithPoster) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    private fun extractEpisodes(doc: Document, pageUrl: String, slug: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seenEpisodes = HashSet<String>()
        val textSeasonEpisode = Regex(
            """(\d+)\s*\.?\s*Sezon\s+(\d+)\s*\.?\s*B[oö]l[uü]m""",
            RegexOption.IGNORE_CASE
        )
        val textEpisodeOnly = Regex(
            """(\d+)\s*\.?\s*B[oö]l[uü]m""",
            RegexOption.IGNORE_CASE
        )
        val urlSeasonEpisode = Regex(
            """(\d+)[^\d]+sezon[^\d]+(\d+)[^\d]+b[oö]l[uü]m""",
            RegexOption.IGNORE_CASE
        )
        for (link in doc.select("a[href]")) {
            val rawHref = link.attr("href").trim()
            if (rawHref.isBlank()) continue
            val href = fixUrl(rawHref)
            if (href == pageUrl) continue
            if (!href.startsWith(mainUrl)) continue
            val text = link.text().replace(Regex("\\s+"), " ").trim()
            val textMatch = if (text.isNotBlank()) textSeasonEpisode.find(text) else null
            val urlMatch = urlSeasonEpisode.find(href)
            val episodeOnlyMatch = if (text.isNotBlank() && textMatch == null)
                textEpisodeOnly.find(text) else null
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
                episodeOnlyMatch != null -> {
                    season = 1
                    episodeNumber = episodeOnlyMatch.groupValues[1].toInt()
                }
                isEpisodeLink(href, slug) -> {
                    season = 1
                    episodeNumber = episodes.count { it.season == 1 } + 1
                }
                else -> continue
            }
            if (!seenEpisodes.add(href)) continue
            var episodeName = text
                .replace(textSeasonEpisode, "")
                .replace(textEpisodeOnly, "")
                .trim()
                .removePrefix("(").removeSuffix(")").trim()
            if (episodeName.isBlank()) episodeName = "Bölüm $episodeNumber"
            episodes.add(
                newEpisode(href) {
                    this.name = episodeName
                    this.season = season
                    this.episode = episodeNumber
                }
            )
        }
        return episodes.sortedWith(
            compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode ?: Int.MAX_VALUE }
        )
    }

    // ---------------------------------------------------------------
    // Video linkleri
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var pageUrl = fixUrl(data)
        Log.d("NinjagoIzlesene", "DRIVE-DEBUG: loadLinks başladı, data='$data', pageUrl='$pageUrl'")

        // Önce elle yüklenen Google Drive kaynağına bak. URL'den sezon/bölüm
        // bilgisi çıkarılabiliyorsa ve Drive'da karşılığı varsa direkt onu kullan.
        run {
            val match = urlSeasonEpisodeRegex.find(pageUrl)
            if (match == null) {
                Log.d("NinjagoIzlesene", "DRIVE-DEBUG: regex EŞLEŞMEDİ, pageUrl='$pageUrl' -> Drive atlanıyor")
                return@run
            }
            val season = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull()
            Log.d("NinjagoIzlesene", "DRIVE-DEBUG: regex eşleşti -> season=$season episode=$episode (ham=${match.value})")
            if (season == null || episode == null) {
                Log.d("NinjagoIzlesene", "DRIVE-DEBUG: season veya episode null çıktı, Drive atlanıyor")
                return@run
            }
            val driveLink = runCatching { resolveDriveEpisode(season, episode) }
                .onFailure {
                    Log.d("NinjagoIzlesene", "DRIVE-DEBUG: resolveDriveEpisode İSTİSNA fırlattı ($season-$episode): ${it::class.simpleName}: ${it.message}")
                }
                .getOrNull()
            Log.d("NinjagoIzlesene", "DRIVE-DEBUG: resolveDriveEpisode sonucu = $driveLink")
            if (driveLink != null) {
                Log.d("NinjagoIzlesene", "DRIVE-DEBUG: loadExtractor çağrılıyor -> $driveLink")
                val extracted = runCatching {
                    loadExtractor(driveLink, pageUrl, subtitleCallback, callback)
                }.onFailure {
                    Log.d("NinjagoIzlesene", "DRIVE-DEBUG: loadExtractor İSTİSNA fırlattı ($driveLink): ${it::class.simpleName}: ${it.message}")
                }.getOrDefault(false)
                Log.d("NinjagoIzlesene", "DRIVE-DEBUG: loadExtractor sonucu = $extracted")
                if (extracted) {
                    Log.d("NinjagoIzlesene", "DRIVE-DEBUG: Drive linki BAŞARILI, true ile dönülüyor")
                    return true
                } else {
                    Log.d("NinjagoIzlesene", "DRIVE-DEBUG: loadExtractor false döndü, site akışına devam ediliyor")
                }
            } else {
                Log.d("NinjagoIzlesene", "DRIVE-DEBUG: driveLink null, site akışına devam ediliyor")
            }
        }

        var doc = app.get(pageUrl, headers = headers).document

        fun pageHasPlayableContent(document: org.jsoup.nodes.Document): Boolean {
            val hasIframe = document.select("iframe").any {
                val src = it.attr("src").trim()
                val dataSrc = it.attr("data-src").trim()
                (src.isNotBlank() && src != "about:blank") ||
                    (dataSrc.isNotBlank() && dataSrc != "about:blank")
            }
            val hasVideoTag = document.select("video[src], source[src]").isNotEmpty()
            return hasIframe || hasVideoTag
        }
        val path = pageUrl.substringAfter(mainUrl).trim('/')
        if (!pageHasPlayableContent(doc)) {
            val contentRoot = doc.selectFirst(
                "main, article, .entry-content, #content, .content, .episode-list, table"
            ) ?: doc.body()
            val knownSlug = if (path.startsWith("dizi/")) {
                path.removePrefix("dizi/").substringBefore("/")
            } else null
            val navigationSlugs = setOf(
                "izleme-sirasi", "tum-bolumler", "dizi-arsivi", "iletisim",
                "hakkimizda", "uye-ol", "profil", "spinjitzu-monastery"
            )
            fun collectCandidateLinks(root: org.jsoup.nodes.Element): List<String> =
                root.select("a[href]").mapNotNull { link ->
                    val href = fixUrl(link.attr("href").trim())
                    if (href == pageUrl || !href.startsWith(mainUrl)) return@mapNotNull null
                    val cPath = href.substringAfter(mainUrl).trim('/')
                    val cParts = cPath.split("/").filter { it.isNotBlank() }
                    if (cParts.isEmpty() || cParts[0] in navigationSlugs) return@mapNotNull null
                    href
                }
            var candidateLinks = collectCandidateLinks(contentRoot)
            if (candidateLinks.isEmpty() && contentRoot != doc.body()) {
                candidateLinks = collectCandidateLinks(doc.body())
            }
            val episodeUrl = knownSlug?.let { slug ->
                candidateLinks.firstOrNull { isEpisodeLink(it, slug) }
            }
                ?: candidateLinks.firstOrNull()
            if (episodeUrl != null) {
                pageUrl = episodeUrl
                doc = app.get(pageUrl, headers = headers).document
            }
        }

        var foundLink = false
        val playerPageUrls = mutableListOf(pageUrl)
        doc.select("a[href]").forEach { link ->
            val text = link.text().trim()
            if (Regex("""Oynat[ıi]c[ıi]\s*\d+""", RegexOption.IGNORE_CASE).matches(text)) {
                val href = fixUrl(link.attr("href").trim())
                if (href.startsWith(mainUrl)) playerPageUrls.add(href)
            }
        }
        for (playerUrl in playerPageUrls.distinct()) {
            val playerDoc = if (playerUrl == pageUrl) doc else
                runCatching { app.get(playerUrl, headers = headers).document }
                    .onFailure {
                        Log.d("NinjagoIzlesene", "Oynatıcı sayfası açılamadı ($playerUrl): ${it.message}")
                    }
                    .getOrNull()
                    ?: continue
            val iframeUrls = playerDoc.select("iframe").mapNotNull { iframe ->
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
            Log.d(
                "NinjagoIzlesene",
                "Oynatıcı sayfası: $playerUrl -> ${iframeUrls.size} iframe bulundu: $iframeUrls"
            )
            for (iframeUrl in iframeUrls) {
                // DZEN: aynı, dokunulmadı.
                if (dzenEmbedRegex.containsMatchIn(iframeUrl)) {
                    runCatching {
                        resolveDzenEmbed(iframeUrl, playerUrl, subtitleCallback, callback)
                    }.onSuccess { success ->
                        Log.d("NinjagoIzlesene", "Dzen çözümü ($iframeUrl): sonuç=$success")
                        if (success) foundLink = true
                    }.onFailure {
                        Log.d("NinjagoIzlesene", "Dzen çözümü istisna fırlattı ($iframeUrl): ${it.message}")
                    }
                    continue
                }
                // YOUTUBE: extractor -> Piped.
                val isYoutube = iframeUrl.contains("youtube.com/embed/", ignoreCase = true) ||
                    iframeUrl.contains("youtu.be/", ignoreCase = true)
                if (isYoutube) {
                    if (!tryYoutubeExtractor(iframeUrl, playerUrl, subtitleCallback, callback)) {
                        runCatching {
                            resolveYoutubeViaPiped(iframeUrl, playerUrl, callback)
                        }.onSuccess { success ->
                            if (success) foundLink = true
                        }.onFailure {
                            Log.d("NinjagoIzlesene", "Piped yedeği istisna fırlattı ($iframeUrl): ${it.message}")
                        }
                    } else {
                        foundLink = true
                    }
                } else {
                    runCatching {
                        loadExtractor(iframeUrl, playerUrl, subtitleCallback, callback)
                    }.onSuccess { success ->
                        if (success) foundLink = true
                    }.onFailure {
                        Log.d("NinjagoIzlesene", "loadExtractor istisna fırlattı ($iframeUrl): ${it.message}")
                    }
                }
            }
            val videoUrls = playerDoc.select("video[src], source[src]").mapNotNull { element ->
                element.attr("src").trim().takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            }.distinct()
            for (videoUrl in videoUrls) {
                callback(
                    newExtractorLink(
                        source = name, name = "$name Video", url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) { this.referer = playerUrl }
                )
                foundLink = true
            }
            val playerHtml = normalizeEscapedHtml(playerDoc.html())
            Regex("""https://drive\.google\.com/file/d/[^"'\\\s<>]+""")
                .findAll(playerHtml).map { it.value }.distinct()
                .forEach { driveUrl ->
                    callback(
                        newExtractorLink(
                            source = name, name = name, url = driveUrl,
                            type = ExtractorLinkType.VIDEO
                        ) { this.referer = mainUrl }
                    )
                    foundLink = true
                }
            Regex(
                """https?://(?:www\.)?youtube\.com/embed/[^"'\\\s<>]+|https?://(?:www\.)?rumble\.com/embed/[^"'\\\s<>]+"""
            ).findAll(playerHtml).map { it.value }.distinct()
                .forEach { embedUrl ->
                    val isYoutube = embedUrl.contains("youtube.com/embed/", ignoreCase = true) ||
                        embedUrl.contains("youtu.be/", ignoreCase = true)
                    if (isYoutube) {
                        if (!tryYoutubeExtractor(embedUrl, playerUrl, subtitleCallback, callback)) {
                            runCatching {
                                resolveYoutubeViaPiped(embedUrl, playerUrl, callback)
                            }.onSuccess { success ->
                                if (success) foundLink = true
                            }.onFailure {
                                Log.d("NinjagoIzlesene", "Piped yedeği istisna fırlattı ($embedUrl): ${it.message}")
                            }
                        } else {
                            foundLink = true
                        }
                    } else {
                        runCatching {
                            loadExtractor(embedUrl, playerUrl, subtitleCallback, callback)
                        }.onSuccess { success ->
                            if (success) foundLink = true
                        }.onFailure {
                            Log.d("NinjagoIzlesene", "Gömülü YT/Rumble linki başarısız ($embedUrl): ${it.message}")
                        }
                    }
                }
            dzenEmbedRegex.findAll(playerHtml).map { it.value }.distinct()
                .forEach { embedUrl ->
                    runCatching {
                        resolveDzenEmbed(embedUrl, playerUrl, subtitleCallback, callback)
                    }.onSuccess { success ->
                        if (success) foundLink = true
                    }.onFailure {
                        Log.d("NinjagoIzlesene", "Gömülü Dzen linki başarısız ($embedUrl): ${it.message}")
                    }
                }
            Regex(
                """https?://[^"'\\\s<>]+?\.(?:mp4|m3u8)(?:\?[^"'\\\s<>]*)?""",
                RegexOption.IGNORE_CASE
            ).findAll(playerHtml)
                .map { it.value.replace("&amp;", "&") }.distinct()
                .forEach { directUrl ->
                    callback(
                        newExtractorLink(
                            source = name, name = "$name Direct", url = directUrl,
                            type = ExtractorLinkType.VIDEO
                        ) { this.referer = playerUrl }
                    )
                    foundLink = true
                }
        }
        Log.d(
            "NinjagoIzlesene",
            "loadLinks tamamlandı. data=$data, foundLink=$foundLink, denenen oynatıcı sayısı=${playerPageUrls.distinct().size}"
        )
        return foundLink
    }
}