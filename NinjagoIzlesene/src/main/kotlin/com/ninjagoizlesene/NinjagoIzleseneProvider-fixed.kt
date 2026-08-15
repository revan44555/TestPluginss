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
    // Dzen.ru (Yandex Zen) çözücüsü
    // ---------------------------------------------------------------
    // Site, video kaynağı olarak YouTube/Rumble'ın yanı sıra dzen.ru
    // embed player'ı da kullanıyor (örn. .../ejderhalarin-yukselisi/.../
    // sayfasında "Oynatıcı 1" -> https://dzen.ru/embed/<id>?...).
    // CloudStream'in yerleşik loadExtractor() fonksiyonu bu domain'i
    // TANIMIYOR (henüz resmi bir Dzen extractor'ı yok, yt-dlp/youtube-dl
    // tarafında bile bu site için extractor'lar kırılgan/eksik durumda).
    // Bu yüzden dzen.ru linklerini loadExtractor()'a göndermek sessizce
    // false döner ve hiçbir şey bulunmaz — tam olarak logdaki
    // "0 links loaded" hatasının sebebi budur.
    //
    // Çözüm: dzen.ru embed sayfasını KENDİMİZ çekip HTML/JS içine gömülü
    // .m3u8/.mp4 linkini regex ile çıkarıyoruz. Dzen embed sayfaları
    // genelde bir <script> içine "streams"/"url" gibi alanlar taşıyan bir
    // JSON gömer; biz JSON yapısına bağımlı olmadan, sayfanın ham
    // metninde geçen tüm .m3u8/.mp4 adreslerini yakalıyoruz. Bu, site
    // Dzen'in iç JSON yapısını değiştirse bile kırılmaya karşı daha
    // dayanıklıdır (yapıya değil, dosya uzantısına bakıyoruz).
    // JSON/HTML içine gömülü URL'ler genelde birkaç farklı "kaçış"
    // (escape) biçimiyle karşımıza çıkabiliyor:
    //   - "\/"      -> "/"        (JSON'da eğik çizgi kaçışı)
    //   - "&amp;"   -> "&"        (HTML entity kaçışı)
    //   - "\u0026"  -> "&"        (JSON'da unicode kaçışlı & karakteri —
    //                              bazı sunucular & yerine bunu üretir)
    //   - genel "\uXXXX" -> gerçek karakter (diğer olası unicode kaçışları
    //                        için de aynı mantığı genelliyoruz)
    // Bunların hepsini regex ÇALIŞTIRMADAN ÖNCE normalize ediyoruz. Eskiden
    // sadece "\/" ve "&amp;" regex'ten SONRA düzeltiliyordu; "\u0026" hiç
    // ele alınmadığı için m3u8 URL'leri o noktada yarım kesiliyor ve
    // CloudStream'e bozuk bir link gidip ExoPlayer'ın
    // "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (3003)" hatası vermesine
    // sebep oluyordu.
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

    // Dzen embed sayfası bazen video bilgisini doğrudan HTML'e değil,
    // bir JSON script bloğuna (örn. "streams":[{"url":"...","name":"720p"}])
    // gömüyor. Kalite adını URL'in yanından çıkarabilirsek CloudStream'in
    // kalite menüsü daha anlamlı görünür (yalnızca "Unknown" yerine
    // "720p", "1080p" gibi etiketler). Bu regex bir m3u8/mp4 URL'inin
    // hemen öncesinde geçen "name"/"quality" gibi bir alanı YAKALAMAYA
    // ÇALIŞIR ama bulunamazsa sorun değil, Unknown'a düşülür — bu yüzden
    // tamamen JSON yapısına bağımlı değil, best-effort bir denemedir.
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

        // Dzen bazı isteklerde daha "tarayıcı gibi" görünen header'lar
        // bekleyebiliyor (Origin, Sec-Fetch-* gibi). Bunları genel
        // `headers` map'ine eklemek yerine burada ayrıca tanımlıyoruz,
        // çünkü bunlar dzen.ru'ya özel — sitenin kendi sayfalarına
        // gönderilen isteklerde gereksizler.
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

        // 1) Doğrudan .m3u8 adresleri. Birden fazla varyant (farklı
        //    kalite/CDN) bulunursa HEPSİ ayrı ExtractorLink olarak
        //    gönderiliyor — CloudStream player'da kullanıcı bunlar
        //    arasında seçim yapabilir. Her birinin kalite etiketini
        //    URL'in çevresindeki metinden tahmin etmeye çalışıyoruz.
        //
        // NOT: embedHtml artık normalizeEscapedHtml() ile önceden
        // normalize edildiği için burada ayrıca "\\/" veya "&amp;"
        // değiştirmeye gerek yok — regex zaten temiz metin üzerinde
        // çalışıyor. Eskiden bu normalizasyon regex'ten SONRA
        // yapılıyordu ve yalnızca "\/" ile "&amp;"yi kapsıyordu; JSON'da
        // sık kullanılan "\u0026" (& karakterinin unicode kaçışı) hiç
        // ele alınmıyordu. Bu da m3u8 URL'sinin "\u0026" görülen yerde
        // YARIM kesilmesine sebep oluyordu (regex "\u0026"yı geçerli bir
        // URL karakteri saymadığı için orada durur) — sonuçta CloudStream'e
        // eksik/bozuk bir URL gidiyor, ExoPlayer da bunu parse edemeyip
        // "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (3003)" hatası
        // veriyordu. Şimdi normalizasyon regex'ten ÖNCE yapıldığı için
        // "\u0026" zaten gerçek "&" karakterine dönüşmüş oluyor ve regex
        // URL'in tamamını eksiksiz yakalıyor.
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
            // URL'in bulunduğu yerin biraz öncesindeki bağlamı (JSON alan
            // adları vb.) kalite tahmini için kullanıyoruz.
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

        // 2) m3u8 bulunamazsa doğrudan .mp4 adreslerini dene (bazı Dzen
        //    videoları progresif mp4 de sunabiliyor).
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
    // YouTube -> Piped API yedek çözücüsü
    // ---------------------------------------------------------------
    // CloudStream'in yerleşik loadExtractor() fonksiyonu YouTube linkleri
    // için kendi YoutubeExtractor'ını (NewPipeExtractor tabanlı) kullanıyor.
    // YouTube'un sunucu tarafı "bot değilsin" doğrulamaları zaman zaman
    // değişiyor ve bu extractor'ı kırıyor — bu durumda loadExtractor()
    // EXCEPTION FIRLATMADAN "true" döner ama callback()'e HİÇBİR link
    // göndermez (bkz. logdaki "loadExtractor: sonuç=true" + ardından
    // "Bağlantı bulunamadı" çelişkisi). Bu yüzden loadExtractor()'ın
    // dönüş değerine güvenmek yerine, callback'e gerçekten link gelip
    // gelmediğini kendimiz sayıyoruz; gelmediyse Piped API üzerinden
    // (NewPipeExtractor tabanlı ama farklı/daha güncel bir çözüm yolu
    // kullanan açık kaynak bir YouTube proxy ağı) stream URL'sini kendimiz
    // çekiyoruz.
    //
    // Piped tek bir sunucu değil, gönüllülerin işlettiği federe bir
    // instance ağı; herhangi biri her an kapanabilir. Bu yüzden TeamPiped
    // tarafından resmi olarak izlenen (github.com/TeamPiped/piped-uptime)
    // en stabil üç instance'ı sırayla deniyoruz: önce resmi kavin.rocks,
    // sonra aynı ekibin CDN'siz "libre" yedeği, son olarak uzun süredir
    // stabil çalışan topluluk instance'ı adminforge.de. Biri başarısız
    // olursa (zaman aşımı, 403, boş yanıt vb.) otomatik olarak bir
    // sonrakine geçilir.
    // Piped public instances değişken olduğundan tek/az sayıda sunucuya
    // bağımlı kalmıyoruz. İlk üç adres TeamPiped dokümantasyonunda güncel
    // public instance olarak listelenen örneklerdir; devamındaki adresler
    // fallback olarak kullanılır. Her instance bağımsız denenir.
    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.leptons.xyz",
        "https://pipedapi.nosebs.ru",
        "https://pipedapi.tokhmi.xyz",
        "https://pipedapi.moomoo.me",
        "https://pipedapi.syncpundit.io",
        "https://api-piped.mha.fi",
        "https://piped-api.garudalinux.org",
        "https://piped-api.privacy.com.de",
        "https://api.piped.yt",
        "https://pipedapi.drgns.space",
        "https://pipedapi.owo.si",
        "https://pipedapi.ducks.party",
        "https://piped-api.codespace.cz",
        "https://pipedapi.reallyaweso.me",
        "https://pipedapi.adminforge.de"
    )

    private val youtubeIdRegex = Regex(
        """(?:youtube\.com/(?:embed/|watch\?v=|shorts/)|youtu\.be/)([a-zA-Z0-9_-]{6,})"""
    )

    private suspend fun resolveYoutubeViaPiped(
        youtubeUrl: String,
        refererUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = youtubeIdRegex.find(youtubeUrl)?.groupValues?.get(1)
        if (videoId == null) {
            Log.d("NinjagoIzlesene", "Piped: video ID çıkarılamadı: $youtubeUrl")
            return false
        }

        for (instance in pipedInstances) {
            val responseResult = runCatching {
                app.get(
                    "$instance/streams/$videoId",
                    headers = mapOf(
                        "User-Agent" to headers.getValue("User-Agent"),
                        "Accept" to "application/json",
                        "Accept-Language" to "en-US,en;q=0.8"
                    )
                )
            }

            val response = responseResult.getOrNull()
            if (response == null || !response.isSuccessful) {
                Log.d(
                    "NinjagoIzlesene",
                    "Piped: $instance başarısız (kod=${response?.code}), sıradaki instance denenecek"
                )
                continue
            }

            // NiceHttp response gövdesini mümkün olduğunca kısa ömürlü
            // tutuyoruz: yalnızca metni alıp hemen sonraki işleme geçiyoruz.
            // Böylece aynı anda çok sayıda instance denenirken açık response
            // birikmesi azaltılır.
            val responseText = runCatching { response.text }.getOrNull()
            if (responseText.isNullOrBlank()) {
                Log.d("NinjagoIzlesene", "Piped: $instance boş yanıt döndürdü")
                continue
            }

            val json = runCatching { org.json.JSONObject(responseText) }.getOrNull()
            if (json == null) {
                Log.d("NinjagoIzlesene", "Piped: $instance geçersiz JSON döndürdü")
                continue
            }

            var found = false

            // HLS (m3u8) varsa öncelik ver — tek adres, CloudStream kalite
            // menüsünü kendisi oluşturur.
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

            // HLS yoksa videoStreams listesindeki progresif mp4'leri
            // (video+ses birlikte, videoOnly=false olanlar) ayrı kalite
            // seçenekleri olarak ekle.
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

    /**
     * Sayfadaki /dizi/<slug>/ formatındaki bağlantıları toplar ve
     * her slug için tekilleştirilmiş bir (href, temsil eden Element) listesi döner.
     * Site küçük, sabit bir arşiv olduğundan (dizi-arsivi sayfası) bu tek
     * fonksiyon hem anasayfa hem arama için kullanılabilir.
     *
     * ÖNEMLİ: Sitenin kart düzeninde HER başlık için genelde birden fazla
     * <a href="...dizi/<slug>/"> linki bulunuyor — biri poster görselini
     * saran link, biri de yalnızca başlık METNİNİ saran ayrı bir link
     * (görselsiz). Eskiden "ilk görülen href kazanır" mantığı vardı; eğer
     * o sayfada metin-linki görsel-linkinden ÖNCE geliyorsa, kod görseli
     * hiç içermeyen linki seçip extractPoster()'a veriyordu — sonuç hep
     * null oluyor ve kartlarda gri/boş poster görünüyordu. Şimdi bunun
     * yerine aynı href için TÜM link adaylarını topluyoruz ve içlerinden
     * gerçekten bir <img> (veya lazy-load kaynağı) barındıranı öncelikli
     * olarak seçiyoruz; hiçbiri görsel içermiyorsa son çare olarak ilk
     * görüleni kullanıyoruz (böylece başlık en azından her zaman görünür).
     */
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
        // Bölüm linkleri kendi thumbnail'ini taşımıyor (site sadece düz
        // metin liste kullanıyor), bu yüzden CloudStream'de bölüm
        // kartlarının resimsiz kalmaması için dizinin posterini her
        // bölüme de miras bırakıyoruz.
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
            // ÖNEMLİ: doc.select("a[href]") TÜM sayfadaki linkleri toplar,
            // bu da her sayfada tekrar eden navigasyon menüsünü
            // ("İzleme Sırası", "Tüm Bölümler", "Dizi Arşivi" gibi üstteki
            // menü linkleri) de işin içine katar. Bu navigasyon linkleri
            // yanlışlıkla "geçerli bir bölüm linki" gibi görünüp fallback'i
            // yanlış sayfaya yönlendirebiliyordu (örn. /izleme-sirasi/'ne
            // düşüp orada takılı kalma). Bunu önlemek için, mümkünse SADECE
            // ana içerik alanındaki (bölüm tablosu / makale gövdesi)
            // linkleri kullanıyoruz; menü/header/footer'ı dışarıda
            // bırakıyoruz. Böyle bir konteyner bulunamazsa tüm sayfaya
            // geri düşüyoruz (eskisi gibi), ama artık öncelik her zaman
            // ana içerikte.
            val contentRoot = doc.selectFirst(
                "main, article, .entry-content, #content, .content, .episode-list, table"
            ) ?: doc.body()

            val knownSlug = if (path.startsWith("dizi/")) {
                path.removePrefix("dizi/").substringBefore("/")
            } else null

            // Bilinen navigasyon/statik sayfa slug'ları — bunlar asla bir
            // bölüm/oynatma sayfası olamaz, fallback'in yanlışlıkla
            // buralara düşmesini engellemek için hariç tutuyoruz.
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

            // 1) Önce ana içerik alanında ara (menü linkleri hariç).
            var candidateLinks = collectCandidateLinks(contentRoot)
            // 2) Ana içerikte hiçbir aday bulunamazsa, son çare olarak
            //    tüm sayfaya (menü dahil) geri düş — ama navigasyon
            //    slug'larını yine de hariç tutarak.
            if (candidateLinks.isEmpty() && contentRoot != doc.body()) {
                candidateLinks = collectCandidateLinks(doc.body())
            }

            // Önce, path'ten çıkarabildiğimiz dizi slug'ına ait GERÇEK bir
            // bölüm linki var mı diye bak (dizi/<slug>/ sayfasındaysak).
            // NOT: Bazı dizilerin (örn. "Ninjago: Legends") bölüm linkleri
            // mainUrl/<slug>/<bölüm>/ yerine mainUrl/<farklı-kategori>/<slug>/
            // gibi tamamen farklı bir üst klasör altında olabiliyor — yani
            // dizi sayfasının kendi slug'ı ile bölüm URL'lerindeki ilk
            // path parçası HER ZAMAN aynı olmayabilir. Bu yüzden slug
            // eşleşmesini ZORUNLU değil, sadece bir ÖNCELİK olarak
            // kullanıyoruz; eşleşme yoksa hemen ikinci koşula (herhangi bir
            // geçerli aday) düşüyoruz.
            val episodeUrl = knownSlug?.let { slug ->
                candidateLinks.firstOrNull { isEpisodeLink(it, slug) }
            }
                // Slug ile eşleşen bulunamadıysa (ya da slug bilinmiyorsa,
                // örn. genel bir sayfadaysak): ana içerikteki İLK geçerli
                // adayı kabul et. contentRoot zaten navigasyon menüsünü
                // dışarıda bıraktığı için bu artık güvenli.
                ?: candidateLinks.firstOrNull()

            if (episodeUrl != null) {
                pageUrl = episodeUrl
                doc = app.get(pageUrl, headers = headers).document
            }
        }

        var foundLink = false
        val emittedUrls = HashSet<String>()
        val emitLink: (ExtractorLink) -> Unit = { link ->
            if (link.url.isNotBlank() && emittedUrls.add(link.url)) {
                callback(link)
            }
        }

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
                if (dzenEmbedRegex.containsMatchIn(iframeUrl)) {
                    // Dzen.ru için loadExtractor() güvenilmez (bkz. yukarıdaki
                    // resolveDzenEmbed açıklaması), bu yüzden kendi
                    // çözücümüzü kullanıyoruz.
                    runCatching {
                        resolveDzenEmbed(iframeUrl, playerUrl, subtitleCallback, emitLink)
                    }.onSuccess { success ->
                        Log.d("NinjagoIzlesene", "Dzen çözümü ($iframeUrl): sonuç=$success")
                        if (success) foundLink = true
                    }.onFailure {
                        Log.d("NinjagoIzlesene", "Dzen çözümü istisna fırlattı ($iframeUrl): ${it.message}")
                    }
                    continue
                }
                // Her extractor ayrı denenir; biri başarısız olsa bile
                // diğerlerinin denenmesine devam edilir (sessizce yutulmuyor,
                // sadece bu extractor için akış kesilmiyor).
                //
                // ÖNEMLİ: loadExtractor()'ın "true" dönmesi TEK BAŞINA
                // güvenilir değil — YouTube linkleri için CloudStream'in
                // dahili extractor'ı (NewPipeExtractor tabanlı) exception
                // atmadan ama callback'e hiç link göndermeden "true"
                // dönebiliyor (bkz. resolveYoutubeViaPiped açıklaması).
                // Bu yüzden YouTube linklerinde callback'e gerçekten link
                // gelip gelmediğini kendimiz sayıp, gelmediyse Piped API
                // yedeğine düşüyoruz.
                val isYoutube = iframeUrl.contains("youtube.com/embed/", ignoreCase = true)
                var linksBeforeCall = 0
                val countingCallback: (ExtractorLink) -> Unit = { link ->
                    linksBeforeCall++
                    emitLink(link)
                }

                runCatching {
                    loadExtractor(iframeUrl, playerUrl, subtitleCallback, countingCallback)
                }.onSuccess { success ->
                    Log.d(
                        "NinjagoIzlesene",
                        "loadExtractor ($iframeUrl): sonuç=$success, gerçek link sayısı=$linksBeforeCall"
                    )
                    if (success && linksBeforeCall > 0) {
                        foundLink = true
                    } else if (isYoutube) {
                        // loadExtractor "true" dedi ama hiç link gelmedi
                        // (ya da hiç denemedi) — Piped yedeğine düş.
                        runCatching {
                            resolveYoutubeViaPiped(iframeUrl, playerUrl, emitLink)
                        }.onSuccess { pipedSuccess ->
                            if (pipedSuccess) foundLink = true
                        }.onFailure {
                            Log.d("NinjagoIzlesene", "Piped yedeği istisna fırlattı ($iframeUrl): ${it.message}")
                        }
                    }
                }.onFailure {
                    Log.d("NinjagoIzlesene", "loadExtractor istisna fırlattı ($iframeUrl): ${it.message}")
                    if (isYoutube) {
                        runCatching {
                            resolveYoutubeViaPiped(iframeUrl, playerUrl, emitLink)
                        }.onSuccess { pipedSuccess ->
                            if (pipedSuccess) foundLink = true
                        }
                    }
                }
            }

            val videoUrls = playerDoc.select("video[src], source[src]").mapNotNull { element ->
                element.attr("src").trim().takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            }.distinct()

            for (videoUrl in videoUrls) {
                emitLink(
                    newExtractorLink(
                        source = name, name = "$name Video", url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) { this.referer = playerUrl }
                )
                foundLink = true
            }

            // NOT: normalizeEscapedHtml() burada da uygulanıyor, aynı
            // "\u0026" kesilme sorununun bu genel taramalarda da (drive,
            // youtube/rumble, doğrudan m3u8/mp4) tekrarlanmaması için.
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

            // Sayfanın ham HTML/script içeriğine gömülü embed URL'lerini de
            // tarıyoruz (about:blank iframe'lerin arkasında gerçek player
            // linki JS ile sonradan yazılıyor olabilir). Site şimdiye kadar
            // gözlemlenen kaynaklarda YouTube, Rumble ve Dzen.ru embed'i
            // kullanıyor. YouTube/Rumble loadExtractor()'a veriliyor
            // (CloudStream tanımazsa sessizce false döner, akış bozulmaz);
            // Dzen.ru CloudStream'de tanınmadığı için kendi çözücümüze
            // yönlendiriliyor.
            Regex(
                """https?://(?:www\.)?youtube\.com/embed/[^"'\\\s<>]+|https?://(?:www\.)?rumble\.com/embed/[^"'\\\s<>]+"""
            ).findAll(playerHtml).map { it.value }.distinct()
                .forEach { embedUrl ->
                    // Aynı "loadExtractor true ama link yok" durumuna karşı
                    // aynı sayaç + Piped yedeği mantığı burada da uygulanır.
                    val isYoutube = embedUrl.contains("youtube.com/embed/", ignoreCase = true)
                    var linksBeforeCall = 0
                    val countingCallback: (ExtractorLink) -> Unit = { link ->
                        linksBeforeCall++
                        emitLink(link)
                    }

                    runCatching {
                        loadExtractor(embedUrl, playerUrl, subtitleCallback, countingCallback)
                    }.onSuccess { success ->
                        Log.d(
                            "NinjagoIzlesene",
                            "HTML extractor ($embedUrl): sonuç=$success, gerçek link sayısı=$linksBeforeCall"
                        )
                        if (success && linksBeforeCall > 0) {
                            foundLink = true
                        } else if (isYoutube) {
                            runCatching {
                                resolveYoutubeViaPiped(embedUrl, playerUrl, emitLink)
                            }.onSuccess { pipedSuccess ->
                                if (pipedSuccess) foundLink = true
                            }
                        }
                    }.onFailure {
                        Log.d("NinjagoIzlesene", "Gömülü YT/Rumble linki başarısız ($embedUrl): ${it.message}")
                        if (isYoutube) {
                            runCatching {
                                resolveYoutubeViaPiped(embedUrl, playerUrl, emitLink)
                            }.onSuccess { pipedSuccess ->
                                if (pipedSuccess) foundLink = true
                            }
                        }
                    }
                }

            dzenEmbedRegex.findAll(playerHtml).map { it.value }.distinct()
                .forEach { embedUrl ->
                    runCatching {
                        resolveDzenEmbed(embedUrl, playerUrl, subtitleCallback, emitLink)
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
