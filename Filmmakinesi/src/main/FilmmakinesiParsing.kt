package com.filmmakinesi

/**
 * Pure parsing helpers for FilmMakinesi. No CloudStream or network
 * dependencies, so they can be unit tested on the JVM.
 */
internal object FilmmakinesiParsing {

    // NOT: Desenler cift tirnak karakteri icerdigi icin raw string
    // ("""...""") icinde yaziliyor. Normal "..." string icindeki "
    // karakteri string'i erken bitirip derleme hatasina yol aciyordu.
    // NOT 2: Alt alan adi oneki (or. "cdn.") opsiyonel — yayin URL'leri
    // hem "rapid.filmmakinesi.to" hem "xxx.rapid.filmmakinesi.to" seklinde
    // gorunebiliyor.
    private val playerHostPattern =
        Regex("""(?i)^https?://(?:[a-z0-9-]*\.)?(?:rapid|closeload)\.filmmakinesi\.to/""")
    private val barePlayerPattern =
        Regex("""https?://(?:[a-z0-9-]*\.)?(?:rapid|closeload)\.filmmakinesi\.to/[^\s"'<>\\]+""")
    private val attrCarrierPattern =
        Regex("""(?:src|data-(?:src|link|url|file))=["'](https?://[^"']+)["']""")
    private val m3u8Pattern =
        Regex("""(?i)(https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*)""")
    // DUZELTME: mp4 deseni artik ".mp4" uzantisini yalnizca path sonunda
    // kabul ediyor. Eski hali ".mp4" sonrasini de yutuyordu; bu yuzden
    // ".../hls/film.mp4/master.txt" bicimindeki HLS manifest URL'leri mp4
    // saniliyor, CloudStream oynatici onlari progressive VIDEO ile
    // acmaya calisip HTTP 404 aliyordu (logcat'te
    // "Response code: 404 ... master.txt" olarak goruldu).
    private val mp4Pattern =
        Regex("""(?i)(https?://[^\s"'<>\\]+\.mp4(?:\?[^\s"'<>\\]*)?)""")
    // HLS master/index manifest'leri ".txt" uzantisiyla da sunulabiliyor
    // (or. ".../hls/filmakinesimp4-xxx.mp4/master.txt"). Bunlar once
    // yakalanmali ki mp4 desenine dahil olup manifest mp4 sanilmasin.
    private val playlistTxtPattern =
        Regex("""(?i)(https?://[^\s"'<>\\]+/(?:master|index|playlist)[a-z0-9._-]*\.txt(?:\?[^\s"'<>\\]*)?)""")
    private val yearPattern = Regex("""\b(19|20)\d{2}\b""")
    // IMDB puani yalnizca "imdb" kelimesine yapisik bolgeden (en fazla 15
    // ayirici karakter) araniyor; "/10" oneki varsa tuketiliyor.
    private val imdbPattern =
        Regex("""(?i)imdb[\s:.\-]{0,15}?\b([0-9]\.[0-9])\b(?:\s*/\s*10)?""")
    // Film URL'lerindeki "...-2026/" bicimli yil onegi.
    private val slugYearPattern = Regex("""-(19|20)\d{2}(?:[/?#]|$)""")
    // Ana sayfa bolum basliklari icinden oynatilamayanlari elemek uzere:
    // yaklasan vizyon filmlerinin henuz oyuncusu yok; dizi/bolum/fragman
    // icerikleri ise bu provider yalnizca TvType.Movie sundugu icin
    // kullanici "baglanti bulunamadi" hatasi alirdi (logcat sikayetinin
    // bir baska kaynagi).
    private val skippedSectionPattern =
        Regex("""(?i)(yakla[sş]an|vizyon tarihi|dizi|b[oö]l[uü]m|fragman|sonu[cç]lar|men[uü])""")

    // ---------------------------------------------------------------
    // Dean Edwards JS packer cozucu + base64 cozucu
    // ---------------------------------------------------------------
    // Site, 2026-10 itibariyla embed sayfalarindaki akis URL'lerini duz
    // metin olarak vermeyi birakti; logcat'te "Embed sayfasinda m3u8
    // bulunamadi" goruluyor. En yaygin saklama bicimleri:
    //   1) eval(function(p,a,c,k,e,d){...}('...',R,C,'sozluk'.split('|'),...))
    //   2) atob("aHR0c...") / gozle gorulur base64 token'lar
    // Asagidaki cozuculer her iki durumu da aciga cikarir; cozulen metin
    // uzerinde ayni akis-URL desenleri tekrar uygulanir.
    private val packerPattern = Regex(
        """\}\('((?:\\.|[^'])*)',\s*(\d+),\s*(\d+),\s*'((?:\\.|[^'])*)'\.split\('\|'\)"""
    )
    private val base64TokenPattern = Regex("""\b([A-Za-z0-9+/]{20,}={0,2})\b""")

    // ---------------------------------------------------------------
    // "Yavas ag" interstitial'i gibi araci sayfalardan yonlendirme cikarma
    // ---------------------------------------------------------------
    // Logcat'te rapid embed sayfasi 41KB'lik bir kabuk donduruyor
    // (icinde akis URL'si yok, "pjw-yavas-ag" sinifi var). Bu sayfalar
    // gercek icerige meta refresh ya da location.* JS'i ile gecer.
    private val metaRefreshTagPattern =
        Regex("""(?i)<meta[^>]*http-equiv\s*=\s*["']?refresh["']?[^>]*>""")
    private val metaRefreshUrlPattern =
        Regex("""(?i)url\s*=\s*([^"'>;\s]+)""")
    // NOT: "location" kelimesinin onunde harf/rakam/$ olmamali — yoksa
    // "someLocation=" gibi alan adlari yanlis pozitif uretir.
    private val jsLocationPattern =
        Regex("""(?i)(?<![\w$])(?:(?:window|top|self)\.)?location(?:\.href)?\s*=\s*["'](https?://[^"']+)["']""")
    private val jsReplacePattern =
        Regex("""(?i)(?<![\w$])(?:(?:window|top|self)\.)?location\.replace\(\s*["'](https?://[^"']+)["']""")
    private val jsAssignPattern =
        Regex("""(?i)(?<![\w$])(?:(?:window|top|self)\.)?location\.assign\(\s*["'](https?://[^"']+)["']""")

    fun isFilmUrl(href: String): Boolean {
        if (href.startsWith("javascript:", ignoreCase = true)) return false
        val path = runCatching { java.net.URI(href).path }.getOrNull() ?: href
        return path.startsWith("/film/")
    }

    fun isPlayerUrl(url: String): Boolean = playerHostPattern.containsMatchIn(url)

    /**
     * Collect first-party player URLs (rapid./closeload. subdomains) from a
     * film page's raw HTML. Covers iframe src attributes, common data-*
     * carriers and bare URLs, so it survives theme markup changes.
     */
    fun extractPlayerUrls(html: String): List<String> {
        val found = LinkedHashSet<String>()
        for (match in attrCarrierPattern.findAll(html)) {
            val url = match.groupValues[1]
            if (isPlayerUrl(url)) found.add(url)
        }
        for (match in barePlayerPattern.findAll(html)) {
            found.add(match.value.trimEnd('.', ';'))
        }
        return found.toList()
    }

    /** Undo the escaping embed pages apply to stream URLs. */
    fun normalizeEscaped(raw: String): String =
        raw
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

    fun extractM3u8(raw: String): String? = extractStreamUrls(raw).firstOrNull()

    /**
     * Bir akis URL'sinin HLS olma olasiligini soyleyen islevsel kontrol.
     * Uzantiya (.m3u8) tek basina guvenilmez: manifest'ler "/master.",
     * "/index." veya "/playlist" path'leriyle ya da "/hls/" iceren CDN
     * path'leri (or. hls*.playmix.uno/hls/...) ile de sunulabiliyor.
     */
    fun isLikelyHls(url: String): Boolean {
        val path = url.substringBefore("?").substringBefore("#").lowercase()
        if (path.endsWith(".m3u8")) return true
        if (path.contains("/master.") || path.contains("/index.")) return true
        if (path.contains("/playlist")) return true
        if (path.contains("/hls/")) return true
        return false
    }

    /**
     * Tum oynatilabilir akis URL'lerini (m3u8, master/index.txt ve mp4)
     * cikarir. Once kacis normalize edilir; bulunamazsa paketlenmis JS
     * cozulup ve base64 token'lar acilip desenler tekrar denenir.
     */
    fun extractStreamUrls(raw: String): List<String> {
        val normalized = normalizeEscaped(raw)
        val found = LinkedHashSet<String>()

        fun collectFrom(text: String) {
            // Siralama onemli: ".txt" manifest'leri mp4'dan once topla ki
            // "...mp4/master.txt" iki kez farkli bicimde kaydedilmesin.
            for (match in playlistTxtPattern.findAll(text)) {
                found.add(match.value.trimEnd('"', '\'', ';', ',', '\\'))
            }
            for (match in m3u8Pattern.findAll(text)) {
                found.add(match.value.trimEnd('"', '\'', ';', ',', '\\'))
            }
            for (match in mp4Pattern.findAll(text)) {
                found.add(match.value.trimEnd('"', '\'', ';', ',', '\\'))
            }
        }

        // Tespit zinciri: dizgun metin -> packer cozumu -> base64 token'lar
        // -> base64 icinde packer. Her asama once bulunana kadar dener.
        fun attempt(text: String) {
            collectFrom(text)
            if (found.isEmpty()) {
                // JS string birlestirme temizligi: "https:"+"//cdn..." -> https://cdn...
                // (cozulmus kaynak kodda parcali string'ler tek URL'ye birlesir)
                unpackPackedJs(text)?.let { collectFrom(it.replace("\"+\"", "")) }
            }
            if (found.isEmpty()) {
                for (decoded in decodeBase64Tokens(text)) {
                    collectFrom(decoded)
                }
            }
            if (found.isEmpty()) {
                // Son care: ham base64 icinde gomulu m3u8 olabilir
                for (decoded in decodeBase64Tokens(text)) {
                    val inner = unpackPackedJs(decoded)
                    if (inner != null) collectFrom(inner)
                }
            }
        }

        attempt(normalized)

        // NOT: Kosul "found.isEmpty()" DEGIL — sayfadaki statik yer
        // tutucu (decoy) manifest ilk taramada bulunup burayi atlamamali;
        // yoksa gercek token (parcali JS'te) hic aciga cikmaz.
        run {
            // DUZELTME (logcat 2026-10-22 09:26): rapid embed sayfasi bir
            // JWPlayer kabugu ve akis URL'sini "https:"+"//cdn..." gibi
            // PARCALI string birlestirme ile gizliyor; dizgun metinde
            // hicbir desen eslesmiyor. Birlestirilmis kopya uzerinde tum
            // zinciri bastan deneyerek gercek akis adresini aciga cikar.
            val rejoined = normalized
                .replace("\"+\"", "")
                .replace("'+'", "")
                .replace("'\"+\"'", "")
            if (rejoined != normalized) attempt(rejoined)
        }

        val urls = found.toList()
        // mp4 deseni artik path sinirinda kaldigi icin
        // "...mp4/master.txt" gibi manifest URL'lerinin yalnizca "...mp4"
        // on eki ayrica yakalaniyor; bu kopuk parcalari (baska bir bulusun
        // on eki olanlari) listeden at ki oynatici olu URL denemesin.
        return urls.filterNot { m -> urls.any { o -> o.length > m.length && o.startsWith(m) } }
    }

    /**
     * Akis bulunamayinca sayfadaki oyuncu kurulum ipuclarini isaret
     * konumlariyla kisa parcalar halinde verir (teshis logu icin).
     * JWPlayer setup(), sources/file alanlari ve saklama bicimleri
     * hangi bolgedeyse bir sonraki duzeltme ona gore yapilir.
     */
    fun interestingSnippets(html: String, window: Int = 140): String {
        val markers = listOf(
            "jwplayer", "setup(", "sources", "file:", "playlist",
            "atob", "eval(", "m3u8", "master", ".txt"
        )
        val parts = mutableListOf<String>()
        for (marker in markers) {
            val i = html.indexOf(marker, ignoreCase = true)
            if (i >= 0) {
                val end = minOf(i + window, html.length)
                parts.add(
                    "[" + marker + "@" + i + "] " +
                        html.substring(i, end).replace(Regex("\\s+"), " ")
                )
            }
        }
        return parts.joinToString(" | ")
    }

    /**
     * Sayfadaki <script src=...> adreslerini cikarir; ayni host'dakiler
     * one alinir. JWPlayer kurulumu ayri bir JS dosyasindaysa akis
     * URL'si orada gomulu olabilir (logcat'teki rapid embed kabugunda
     * HTML icinde akis yok; kurulum JS'i dis kaynakta olabilir).
     */
    fun extractScriptSrcs(html: String, baseUrl: String): List<String> {
        val baseHost = hostOf(baseUrl)
        val out = LinkedHashSet<String>()
        for (match in Regex("""<script[^>]+src=["']([^"']+)["']""").findAll(html)) {
            val absolute = safeAbsoluteUrl(normalizeEscaped(match.groupValues[1]), baseUrl)
            if (hostOf(absolute).isNotEmpty()) out.add(absolute)
        }
        // Ayni host once gelir; kararli sirala (LinkedHashSet duzenini koru)
        return out.sortedBy { hostOf(it) != baseHost }
    }

    /**
     * Interstitial/yonlendirme sayfalarindan gercek oyuncu sayfasina giden
     * hedefleri cikarir: meta refresh, JS location atamalari ve
     * location.replace/assign. Bos liste donmesi sayfanin yonlendirme
     * barindirmadigi anlamina gelir.
     */
    fun extractRedirectTargets(html: String): List<String> {
        val found = LinkedHashSet<String>()
        for (tag in metaRefreshTagPattern.findAll(html)) {
            metaRefreshUrlPattern.find(tag.value)?.let { match ->
                found.add(match.groupValues[1].trim().trim('"', '\''))
            }
        }
        for (match in jsLocationPattern.findAll(html)) found.add(match.groupValues[1])
        for (match in jsReplacePattern.findAll(html)) found.add(match.groupValues[1])
        for (match in jsAssignPattern.findAll(html)) found.add(match.groupValues[1])
        return found.filter { it.startsWith("http") }
    }

    /** Dean Edwards packer formatindaki eval(...) bloklarini cozer. */
    fun unpackPackedJs(html: String): String? {
        val match = packerPattern.find(html) ?: return null
        return runCatching {
            val payload = unescapeJsString(match.groupValues[1])
            val radix = match.groupValues[2].toInt()
            val count = match.groupValues[3].toInt()
            val dictionary = unescapeJsString(match.groupValues[4]).split('|')

            var out = payload
            var i = count - 1
            while (i >= 0) {
                val word = dictionary.getOrNull(i).orEmpty()
                if (word.isNotEmpty()) {
                    val key = toBase(i, radix)
                    out = out.replace(Regex("""\b""" + Regex.escape(key) + """\b"""), word)
                }
                i--
            }
            out
        }.getOrNull()
    }

    /**
     * Metindeki gozle gorulur base64 token'lari cozer ve iclerinden
     * akis URL'si icerenleri dondurur (atob("...") deseni de bu kapsamda,
     * cunku atob icindeki string ayni zamanda duz token olarak da gorunur).
     */
    fun decodeBase64Tokens(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (match in base64TokenPattern.findAll(text)) {
            val decoded = runCatching { base64Decode(match.groupValues[1]) }.getOrNull()
                ?: continue
            val asString = runCatching { String(decoded, Charsets.UTF_8) }.getOrNull()
                ?: continue
            if (asString.startsWith("http") || asString.contains(".m3u8") || asString.contains(".mp4")) {
                out.add(asString)
            }
        }
        return out.toList()
    }

    /** URL-safe olmayan, padding destekli minimal base64 cozucu (JVM/JUnit uyumlu). */
    private fun base64Decode(input: String): ByteArray {
        val table = IntArray(128) { -1 }
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { i, c ->
            table[c.code] = i
        }
        val clean = input.filter { it != ' ' && it != '\n' && it != '\r' && it != '\t' }
        require(clean.length % 4 == 0) { "gecersiz base64 uzunluk" }
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < clean.length) {
            val c0 = clean[i]; val c1 = clean[i + 1]
            val c2 = clean[i + 2]; val c3 = clean[i + 3]
            require(c0.code < 128 && table[c0.code] >= 0) { "gecersiz karakter" }
            require(c1.code < 128 && table[c1.code] >= 0) { "gecersiz karakter" }
            val b0 = table[c0.code]
            val b1 = table[c1.code]
            val b2 = if (c2 == '=') 0 else { require(c2.code < 128 && table[c2.code] >= 0); table[c2.code] }
            val b3 = if (c3 == '=') 0 else { require(c3.code < 128 && table[c3.code] >= 0); table[c3.code] }
            out.write((b0 shl 2) or (b1 shr 4))
            if (c2 != '=') out.write(((b1 and 0x0F) shl 4) or (b2 shr 2))
            if (c3 != '=') out.write(((b2 and 3) shl 6) or b3)
            i += 4
        }
        return out.toByteArray()
    }

    /** JS string literal icerigini kacis dizilerinden arindirir. */
    private fun unescapeJsString(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'x' -> {
                        runCatching { sb.append(s.substring(i + 2, i + 4).toInt(16).toChar()) }
                        i += 4
                    }
                    'u' -> {
                        runCatching { sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()) }
                        i += 6
                    }
                    else -> { sb.append(n); i += 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** Pozitif tam sayiyi verilen tabana (2..62) cevirir. */
    private fun toBase(value: Int, base: Int): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        require(base in 2..62) { "taban 2..62 olmali" }
        if (value == 0) return "0"
        var n = value
        var out = ""
        while (n > 0) {
            out = digits[n % base] + out
            n /= base
        }
        return out
    }

    fun extractYear(text: String, urlHint: String? = null): Int? {
        urlHint?.let { hint ->
            slugYearPattern.find(hint)?.let { match ->
                match.value.removePrefix("-").take(4).toIntOrNull()?.let { return it }
            }
        }
        return yearPattern.find(text)?.value?.toIntOrNull()
    }

    fun extractImdb(text: String): Float? {
        val value = imdbPattern.find(text)?.groupValues?.get(1)?.toFloatOrNull()
            ?: return null
        return if (value in 0f..10f) value else null
    }

    /** Ana sayfa bolum adi oynatilamayan icerige mi ait? */
    fun isSkippedHomeSection(name: String): Boolean =
        skippedSectionPattern.containsMatchIn(name)

    fun safeAbsoluteUrl(url: String, base: String): String {
        val trimmed = url.trim()
        if (trimmed.startsWith("//")) return "https:" + trimmed
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return base.trimEnd('/') + "/" + trimmed.trimStart('/')
    }

    fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")

    fun playerLabel(url: String): String = when {
        url.contains("rapid", ignoreCase = true) -> "Rapid"
        url.contains("closeload", ignoreCase = true) -> "CloseLoad"
        else -> "Kaynak"
    }
}
