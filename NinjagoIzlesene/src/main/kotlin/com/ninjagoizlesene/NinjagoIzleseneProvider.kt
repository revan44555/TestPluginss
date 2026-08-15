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

    // Film olarak işaretlenecek slug'lar (yalnızca "LEGO Ninjago Filmi").
    // Geri kalan her şey (mini diziler, sezonlar, kısa filmler koleksiyonu)
    // bölümlere ayrılmış bir dizi gibi ele alınıyor.
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

    /**
     * Bir linkin, verilen dizi/film slug'ına ait GERÇEK bir bölüm/oynatma
     * sayfası olup olmadığını, sitenin URL YAPISINA bakarak belirler.
     *
     * Site kuralı: bölüm/oynatma linkleri her zaman
     *   mainUrl/<dizi-slug>/<bölüm-slug>/
     * biçimindedir (BAŞINDA "/dizi/" YOKTUR). Detay/liste sayfası ise
     *   mainUrl/dizi/<dizi-slug>/
     * biçimindedir. Aradaki tek fark "dizi/" önekinin olup olmaması.
     *
     * Önceki sürüm bunun yerine link METNİNDE "sezon"/"bölüm" kelimesi
     * arıyor ya da href'in "izle" ile bitmesini bekliyordu. Bu, yalnızca
     * "LEGO Ninjago Filmi" (.../lego-ninjago-filmi/izle/) için doğruydu;
     * diğer tüm dizilerde bölüm slug'ları "izle" İÇERMEZ (örn.
     * .../ninjago-kisa-filmler/wunun-caylari/ veya
     * .../spinjitzunun-ustalari/1-sezon-1-bolum/), bu yüzden o kural
     * hangi başlığa girilirse girilsin kırılgandı. URL yapısına bakmak
     * metin/HTML değişikliklerinden etkilenmez ve tüm başlıklarda
     * (film + her dizi) aynı şekilde çalışır.
     */
    private fun isEpisodeLink(href: String, seriesSlug: String): Boolean {
        if (!href.startsWith(mainUrl)) return false
        val path = href.substringAfter(mainUrl).trim('/')
        val parts = path.split("/").filter { it.isNotBlank() }
        return parts.size >= 2 && parts[0] == seriesSlug
    }

    /**
     * Poster görselini bulur. Site, çoğu görseli lazy-load ile veriyor:
     * bir <a> içinde genelde İKİ <img> olabiliyor — biri boş/placeholder
     * "src" ile (gerçek adres data-src/data-lazy-src gibi bir attribute'ta
     * ya da <noscript> içindeki ikinci bir <img>'de duruyor), diğeri de
     * gerçek adresi doğrudan "src" ile taşıyabiliyor. Eski kod yalnızca
     * link.selectFirst("img") ile İLK img'i alıyordu; bu img'in "src"si
     * boşsa ve diğer data-* attribute'ları da boşsa, sonuç hep null
     * dönüyor ve poster hiç yüklenmiyordu (kartlarda boş/gri kutu).
     * Bunun yerine linkin içindeki ve <noscript> altındaki TÜM img
     * adaylarını gez, ilk GEÇERLİ (boş olmayan) kaynağı kullan.
     */
    private fun extractPoster(link: Element): String? {
        val container = link.closest("article, div, li, section") ?: link.parent()

        val candidates = mutableListOf<Element>()
        candidates.addAll(link.select("img"))
        link.select("noscript").forEach { noscript ->
            // <noscript> içeriği Jsoup tarafından metin olarak parse edilir;
            // gerçek img etiketlerini görmek için ayrıca parse etmemiz gerekir.
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
                .ifBlank { img.attr("srcset").substringBefore(" ") }
                .trim()
            if (src.isNotBlank() && !src.startsWith("data:")) {
                return fixUrl(src)
            }
        }
        return null
    }

    /**
     * Sayfadaki /dizi/<slug>/ formatındaki bağlantıları toplar ve
     * her slug için tekilleştirilmiş bir (href, temsil eden Element) listesi döner.
     * Site küçük, sabit bir arşiv olduğundan (dizi-arsivi sayfası) bu tek
     * fonksiyon hem anasayfa hem arama için kullanılabilir.
     */
    private fun collectSeriesLinks(doc: Document): List<Pair<String, Element>> {
        val seen = HashSet<String>()
        val results = mutableListOf<Pair<String, Element>>()
        for (link in doc.select("a[href]")) {
            val rawHref = link.attr("href").trim()
            if (rawHref.isBlank()) continue
            val href = fixUrl(rawHref)
            val path = href.substringAfter(mainUrl).trim('/')
            val parts = path.split("/").filter { it.isNotBlank() }
            if (parts.size != 2 || parts[0] != "dizi") continue
            if (!seen.add(href)) continue
            results.add(href to link)
        }
        return results
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
    // Site bir "arama motoru" değil, sabit sayıda (7-8) dizi/film içeren
    // küçük bir arşiv. Bu yüzden kullanıcı hiç arama yapmadan da tüm
    // içeriği anasayfada görebilmeli.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = headers).document
        val seriesLinks = collectSeriesLinks(doc)

        val items = seriesLinks.map { (href, link) -> toSearchResponse(href, link) }

        // Sabit arşiv sayfası; sayfalama yok, tek seferde tüm içerik dönüyor.
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    // ---------------------------------------------------------------
    // Arama
    // ---------------------------------------------------------------
    // ÖNEMLİ: Bu site kelime bazlı bir arama motoruna sahip değil; sadece
    // sabit bir dizi arşivi var (~7-8 başlık: "Ejderhaların Yükselişi",
    // "Spinjitzu'nun Ustaları", "LEGO Ninjago Filmi" vb.). Kullanıcı
    // "Iron Man" gibi alakasız bir şey ararsa hiçbir sonuç bulunamaması
    // NORMALDİR ve bir hata değildir — bu yüzden artık boş sonuçta
    // exception fırlatmıyoruz (eski kod bunu "teşhis" amaçlı hata olarak
    // fırlatıyordu, bu da CloudStream'de her aramanın "Api ... did not
    // return any search responses" ile başarısız görünmesine sebep oluyordu).
    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(archiveUrl, headers = headers)
        val doc = response.document
        val seriesLinks = collectSeriesLinks(doc)

        // TEŞHİS LOGU: exception fırlatmıyoruz (CloudStream'de arama
        // "başarısız" görünmesin diye), ama Logcat'e (adb logcat | grep
        // NinjagoIzlesene) gerçek durumu yazıyoruz. Bu sayede search()
        // hep boş dönerse sebebi görülebilir: httpCode 200 değilse site
        // engelliyordur; totalLinks 0 ise HTML hiç link içermiyordur;
        // totalLinks > 0 ama diziLinks 0 ise collectSeriesLinks'in CSS/
        // path varsayımı ("/dizi/<slug>/") yanlıştır.
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

        // Sonuç bulunamadı: bu, sitenin küçük ve sabit arşivi göz önüne
        // alındığında beklenen bir durumdur. Boş liste dönmek CloudStream'in
        // beklediği doğru davranıştır (exception DEĞİL). Gerçek sebep için
        // yukarıdaki Logcat satırına bak.
        return filtered.map { (href, link) -> toSearchResponse(href, link) }
    }

    // Türkçe karakter/case duyarsız karşılaştırma için basit normalize.
    // "İ/I/i/ı" karışıklığı yüzünden ignoreCase=true tek başına
    // Türkçe metinlerde güvenilmez sonuçlar verebiliyor.
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

        // Film sayfası: bölüm listesi yok, ama detay sayfasının kendisi de
        // oynatma sayfası DEĞİL. Gerçek oynatma linki mainUrl/<slug>/izle/
        // gibi ayrı bir sayfada duruyor (örn. .../lego-ninjago-filmi/izle/).
        // Bunu isEpisodeLink ile URL yapısına bakarak buluyoruz; bulamazsak
        // (site değişirse) son çare olarak detay sayfasına düşüyoruz.
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

        return newTvSeriesLoadResponse(title, fixedUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    /**
     * Bölüm bağlantılarını çıkarır. Sitedeki farklı bölüm koleksiyonları
     * (normal sezonlar, tek seferlik "Anma Günü" gibi özel filmler, "Kısa
     * Filmler" gibi numaralandırılmış mini bölümler) hepsi aynı sayfa
     * düzenini paylaşmayabilir; bu yüzden birden fazla eşleşme deseni
     * sırayla deneniyor ve hiçbiri tutmazsa "/izle" içeren her link
     * bölüm olarak sayılıyor (en toleranslı, en son çare).
     */
    private fun extractEpisodes(doc: Document, pageUrl: String, slug: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seenEpisodes = HashSet<String>()

        // "N. Sezon M. Bölüm" biçimi hem metinde hem satır başında boşluk
        // farklılıklarıyla (nbsp dahil) gelebiliyor; \s+ bunu tolere eder.
        val textSeasonEpisode = Regex(
            """(\d+)\s*\.?\s*Sezon\s+(\d+)\s*\.?\s*B[oö]l[uü]m""",
            RegexOption.IGNORE_CASE
        )
        // Sadece "M. Bölüm" (sezon belirtilmemiş, tek sezonlu diziler için).
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
            // Bölüm linkleri her zaman aynı dizinin altında olmalı; site
            // içi navigasyon (menü, "Anasayfa", "İletişim" vb.) elenir.
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
                // Son çare: yalnızca gerçek bölüm izleme sayfaları içindir.
                // ÖNEMLİ: eskiden burada href.contains("/izle") kullanılıyordu,
                // ama bu "/izleme-sirasi/" gibi navigasyon linklerini de
                // yanlışlıkla eşleştiriyordu ("izleme-sirasi" içinde "izle"
                // geçiyor) — bu da sahte bir "bölüm" üretip CloudStream'in
                // test aracının o linki seçip loadLinks()'te 0 sonuç almasına
                // sebep oluyordu. ARTIK metin/kelime aramıyoruz: href'in
                // mainUrl/<dizi-slug>/<...>/ yapısında olup olmadığına
                // bakıyoruz (isEpisodeLink). Bu, "izle" kelimesi geçmeyen
                // bölüm slug'larında da (örn. .../wunun-caylari/,
                // .../1-sezon-1-bolum/) doğru çalışır — eski kural sadece
                // filmde ("izle" ile bitiyordu) işe yarıyordu.
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
        var doc = app.get(pageUrl, headers = headers).document

        // GÜVENLİK AĞI: CloudStream'in kendi "Sağlayıcı testi" aracı bazen
        // gerçek bölüm/oynatma linki yerine BAŞKA bir sayfayı doğrudan
        // loadLinks()'e gönderebiliyor. Bunun iki farklı hali gözlemlendi:
        //  1) Dizinin kendi detay sayfası ("/dizi/<slug>/") — video yok,
        //     sadece bölüm listesi var.
        //  2) Sitenin tamamen genel bir sayfası, örn. "/izleme-sirasi/"
        //     ("İzleme Sırası") — bu da bir dizinin altında DEĞİL, ama
        //     içinde tüm dizilerin bölümlerine giden linkler var.
        // Her iki durumda da bu sayfada oynatıcı/iframe/video YOKTUR.
        // Bu yüzden path'in "dizi/" ile başlayıp başlamadığına bakmak
        // yeterli değil: sayfada gerçek video/iframe bulunamazsa, sayfadaki
        // linkler arasında GERÇEK bir bölüm linki (isEpisodeLink) arayıp
        // ona yöneliyoruz. slug'ı biliyorsak (dizi/<slug>/ sayfasındaysak)
        // o slug'a öncelik veriyoruz; bilmiyorsak (örn. izleme-sirasi gibi
        // genel bir sayfadaysak) sayfadaki İLK geçerli bölüm linkini
        // (mainUrl/<bir-slug>/<bir-şey>/ yapısında olan, "dizi/" ile
        // başlamayan herhangi bir link) kullanıyoruz — çünkü CloudStream
        // zaten hangi başlığı test ettiğini biliyor, bu yalnızca "video
        // yok, boş sayfaya düşme" durumunu telafi etmek için son çare.
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
            val knownSlug = if (path.startsWith("dizi/")) {
                path.removePrefix("dizi/").substringBefore("/")
            } else null

            val allLinks = doc.select("a[href]").map { fixUrl(it.attr("href").trim()) }

            // Önce, path'ten çıkarabildiğimiz dizi slug'ına ait bir bölüm
            // linki var mı diye bak (dizi/<slug>/ sayfasındaysak).
            val episodeUrl = knownSlug?.let { slug ->
                allLinks.firstOrNull { it != pageUrl && isEpisodeLink(it, slug) }
            }
                // Slug bilmiyorsak (örn. izleme-sirasi gibi genel bir
                // sayfadaysak): sitedeki HERHANGİ bir gerçek bölüm linkini
                // kabul et. Bunu şu şekilde ayırt ediyoruz: mainUrl'den
                // sonraki ilk parça "dizi" DEĞİL ve en az 2 path parçası
                // var (yani mainUrl/<slug>/<bolum>/ formatında).
                ?: allLinks.firstOrNull { candidate ->
                    if (candidate == pageUrl || !candidate.startsWith(mainUrl)) return@firstOrNull false
                    val cPath = candidate.substringAfter(mainUrl).trim('/')
                    val cParts = cPath.split("/").filter { it.isNotBlank() }
                    cParts.size >= 2 && cParts[0] != "dizi"
                }

            if (episodeUrl != null) {
                pageUrl = episodeUrl
                doc = app.get(pageUrl, headers = headers).document
            }
        }

        var foundLink = false

        // Site "Oynatıcı 1 / Oynatıcı 2 / Oynatıcı 3" gibi birden fazla
        // player seçeneği sunuyor; bunlar JS ile aynı sayfada değişen bir
        // sekme DEĞİL, her biri ayrı bir alt-sayfa (".../2/", ".../3/").
        // Sayfadaki "Oynatıcı N" linklerini bulup her birini de ayrıca
        // çekiyoruz, böylece Oynatıcı 1 çalışmazsa diğerleri denenir.
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
                runCatching { app.get(playerUrl, headers = headers).document }.getOrNull()
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

            for (iframeUrl in iframeUrls) {
                // Her extractor ayrı denenir; biri başarısız olsa bile
                // diğerlerinin denenmesine devam edilir (sessizce yutulmuyor,
                // sadece bu extractor için akış kesilmiyor).
                runCatching {
                    loadExtractor(iframeUrl, playerUrl, subtitleCallback, callback)
                }.onSuccess { success ->
                    if (success) foundLink = true
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

            val playerHtml = playerDoc.html()

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

            // Sayfanın ham HTML/script içeriğine gömülü embed URL'lerini de
            // tarıyoruz (about:blank iframe'lerin arkasında gerçek player
            // linki JS ile sonradan yazılıyor olabilir). Site şu ana kadar
            // gözlemlenen kaynaklarda YouTube ve Rumble embed'i kullanıyor;
            // her ikisi de loadExtractor()'a veriliyor, CloudStream tanımazsa
            // sessizce false döner ve akış bozulmaz.
            Regex(
                """https?://(?:www\.)?youtube\.com/embed/[^"'\\\s<>]+|https?://(?:www\.)?rumble\.com/embed/[^"'\\\s<>]+"""
            ).findAll(playerHtml).map { it.value }.distinct()
                .forEach { embedUrl ->
                    runCatching {
                        loadExtractor(embedUrl, playerUrl, subtitleCallback, callback)
                    }.onSuccess { success ->
                        if (success) foundLink = true
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

        return foundLink
    }
}
