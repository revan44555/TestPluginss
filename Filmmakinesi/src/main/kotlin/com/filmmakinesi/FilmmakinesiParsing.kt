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
    private val base64TokenPattern = Regex("""(?<![A-Za-z0-9+/_-])([A-Za-z0-9+/_-]{20,}={0,2})(?![A-Za-z0-9+/_=-])""")
    // The latest player pages also synthesize stream strings from numeric character codes.
    // Only literal numeric arguments are decoded; arbitrary JavaScript is never executed.
    private val fromCharCodePattern = Regex(
        """(?is)(?:String\s*\.\s*fromCharCode|String\s*\[\s*["']fromCharCode["']\s*\])\s*\(([^)]{1,4096})\)"""
    )
    private val fromCharCodeApplyPattern = Regex(
        """(?is)(?:String\s*\.\s*fromCharCode|String\s*\[\s*["']fromCharCode["']\s*\])\s*\.\s*apply\s*\(\s*(?:null|undefined|String|this)\s*,\s*\[([^\]]{1,4096})\]\s*\)"""
    )

    // ---------------------------------------------------------------
    // Parca-birlestirme (runtime atob) cozucusu
    // ---------------------------------------------------------------
    // Logcat (2026-10-22 10:46): embed sayfasi akis URL'sini hicbir yerde
    // literal tutmuyor; parcalari kisri degiskenlere bolup
    // "[p1,p2,...].reverse().join('')" ile birlestirip atob() ile aciyor
    // (log: "file:atob(file_link)", sozlukte "rp5|end44|l30|reverse|...").
    // Asagidaki desenler bu birlesimi statik olarak yeniden kurar:
    // degisken atamalari, 'a'+'b' biciminde birlestirilmis atamalar,
    // dizi-join kalibi ve atob(...) cagrilarinin argumanlari.
    private val literalAssignPattern =
        Regex("""(?<![.\w$])([A-Za-z_$][\w$]*)\s*=\s*["']([^"'\n]{1,160}?)["']""")
    private val concatAssignPattern =
        Regex("""(?<![.\w$])([A-Za-z_$][\w$]*)\s*=\s*((?:"[^"\n]{0,160}"|'[^'\n]{0,160}')(?:\s*\+\s*(?:"[^"\n]{0,160}"|'[^'\n]{0,160}')){1,15})""")
    private val arrayJoinPattern =
        Regex("""\[\s*([A-Za-z_$][\w$]*(?:\s*,\s*[A-Za-z_$][\w$]*){1,31})\s*\]\s*(?:\.\s*reverse\s*\(\s*\)\s*)?\.\s*join\s*\(\s*["']?([^)"']{0,4}?)["']?\s*\)""")
    private val atobCallPattern =
        Regex("""(?<![\w$.])atob\s*\(\s*([^)]{1,200}?)\s*\)""")
    private val quotedPiecePattern =
        Regex("""["'][^"'\n]{0,160}["']""")
    private val identifierPattern =
        Regex("""^[A-Za-z_$][\w$]*$""")
    private val packedTokenPattern = Regex("""[A-Za-z0-9]+""")

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

    /** Build likely search routes used by current and legacy site themes. */
    fun searchUrls(query: String, baseUrl: String): List<String> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        return listOf(
            "$baseUrl/kesfet/?s=$encoded",
            "$baseUrl/kesfet/?q=$encoded",
            "$baseUrl/kesfet/?search=$encoded",
            "$baseUrl/kesfet/?arama=$encoded",
            "$baseUrl/?s=$encoded"
        ).distinct()
    }

    /**
     * Normalize accents and punctuation before comparing a requested title
     * with catalog card titles (e.g. "Predator: Badlands" vs "Predator Badlands").
     */
    fun searchTitleMatches(query: String, title: String): Boolean {
        fun normalize(value: String): String =
            java.text.Normalizer.normalize(value.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
        val wanted = normalize(query)
        val candidate = normalize(title)
        if (wanted.isBlank() || candidate.isBlank()) return false
        if (candidate.contains(wanted)) return true
        val words = wanted.split(Regex("\\s+")).filter { it.length > 1 }
        return words.isNotEmpty() && words.all { candidate.contains(it) }
    }

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
        // A direct .mp4 under an /hls/ directory is still a video file;
        // don't download the entire file just to test whether it is a manifest.
        if (path.endsWith(".mp4")) return false
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
        val found = LinkedHashSet<String>()
        val candidates = ArrayList<String>()
        val seenTexts = HashSet<String>()

        fun enqueue(candidate: String?) {
            if (candidate.isNullOrBlank() || candidate.length > 200_000) return
            if (candidates.size < 64 && seenTexts.add(candidate)) candidates.add(candidate)
        }

        fun collectFrom(text: String) {
            // .txt manifests must be collected before the .mp4 path prefix.
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

        enqueue(normalizeEscaped(raw))
        var index = 0
        while (index < candidates.size && index < 64) {
            val text = candidates[index++]
            collectFrom(text)

            // Keep decoding even when a dead/stale manifest has already been found.
            // Several player variants include a decoy URL next to the actual encoded URL.
            val charDecoded = decodeFromCharCodeExpressions(text)
            if (charDecoded != text) enqueue(charDecoded)

            // Static concatenation, packed JavaScript, and fragment-array reconstruction.
            val rejoined = text
                .replace("\"+\"", "")
                .replace("'+'", "")
                .replace("'\"+\"'", "")
            if (rejoined != text) enqueue(rejoined)

            val unpacked = unpackPackedJs(text)
            if (unpacked != null) {
                enqueue(unpacked)
                // The variable definitions and their join expression can be in different layers.
                enqueue(unpacked + "\n" + text)
            }
            reassembleFragmentedStreams(text).take(48).forEach(::enqueue)

            // Decode only base64 payloads that look like URLs or player/JS source.
            decodeBase64Tokens(text).take(32).forEach(::enqueue)
            tryDecodeBase64Text(text)
                ?.takeIf { isInterestingDecodedText(it) }
                ?.let(::enqueue)
        }

        val urls = found.toList()
        // Remove truncated URL prefixes when a longer complete manifest URL exists.
        return urls.filterNot { short ->
            urls.any { longer -> longer.length > short.length && longer.startsWith(short) }
        }
    }

    /**
     * Akis bulunamayinca sayfadaki oyuncu kurulum ipuclarini isaret
     * konumlariyla kisa parcalar halinde verir (teshis logu icin).
     * JWPlayer setup(), sources/file alanlari ve saklama bicimleri
     * hangi bolgedeyse bir sonraki duzeltme ona gore yapilir.
     */
    fun interestingSnippets(html: String, window: Int = 220): String {
        val markers = listOf(
            "jwplayer", "setup(", "sources", "file:", "playlist",
            "atob", "eval(", "m3u8", "master", ".txt",
            // Yeni gizleme ipuclari (logcat 2026-10-22 10:46):
            "file_link", "reverse", "fromCharCode", "split('|')", "fetch(", "XMLHttpRequest"
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
     * Teshis: gizlemenin kalbindeki (eval-packer, atob, file_link) bolgeyi
     * konum bilgisiyle dondurur. HTML basindaki CSS yerine dogrudan
     * birlesim mantigini gormek icin; parca atamalarini bir sonraki
     * logcat'te yakalamak icin kullanilir.
     */
    fun obfuscationRegionSnippet(html: String, window: Int = 700): String? {
        val anchors = listOf(
            "file_link", "atob(", "fromCharCode", "eval(function(p,a,c,k,e,d)",
            ".reverse", "split('|')", "join("
        )
        val hits = anchors.mapNotNull { anchor ->
            val index = html.indexOf(anchor, ignoreCase = true)
            if (index >= 0) index to anchor else null
        }.sortedBy { it.first }
        if (hits.isEmpty()) return null

        // Include a few distinct code regions, but avoid duplicate overlapping windows.
        val selected = mutableListOf<Pair<Int, String>>()
        for (hit in hits) {
            if (selected.none { kotlin.math.abs(it.first - hit.first) < window / 2 }) {
                selected.add(hit)
            }
            if (selected.size >= 4) break
        }

        val urlPattern = Regex("""(?i)https?://[^\s"'<>\\]+""")
        val quotedLiteralPattern = Regex("""(["'])([^"'\n]{5,})\1""")
        // JavaScript template literals can contain signed tokens too; treat their full body as sensitive.
        val templateLiteralPattern = Regex("""(`)((?:\\.|[^`\\]){5,})`""")
        val numericArrayPattern = Regex("""(?<![\w])(?:\d{1,5}\s*,\s*){10,}\d{1,5}(?![\w])""")
        val regions = selected.map { (index, anchor) ->
            val start = maxOf(0, index - window / 3)
            val end = minOf(html.length, index + window)
            val redacted = html.substring(start, end)
                .replace(urlPattern, "[URL]")
                .replace(base64TokenPattern, "[BASE64]")
                .replace(numericArrayPattern, "[NUMERIC_ARRAY]")
                .replace(quotedLiteralPattern) { match ->
                    val quote = match.groupValues[1]
                    quote + "[LITERAL:" + match.groupValues[2].length + "]" + quote
                }
                .replace(templateLiteralPattern) { match ->
                    "`[TEMPLATE_LITERAL:" + match.groupValues[2].length + "]`"
                }
                .replace(Regex("""\s+"""), " ")
            "[@$index $anchor] $redacted"
        }
        return regions.joinToString(" || ").take(3000)
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

            // Older implementation compiled one regex and copied the entire
            // payload for every dictionary word. On modern, large player bundles
            // this caused sustained allocation/GC churn before WebView playback.
            // Bound hostile/oversized inputs and replace tokens in a single pass.
            require(radix in 2..62 && count in 0..4096 && payload.length <= 200_000)
            val replacements = HashMap<String, String>(minOf(count, dictionary.size))
            for (i in (minOf(count, dictionary.size) - 1) downTo 0) {
                val word = dictionary[i]
                if (word.isNotEmpty()) replacements[toBase(i, radix)] = word
            }
            val out = StringBuilder(payload.length)
            var cursor = 0
            for (token in packedTokenPattern.findAll(payload)) {
                val start = token.range.first
                val end = token.range.last + 1
                val leftBoundary = start == 0 || !isRegexWord(payload[start - 1])
                val rightBoundary = end == payload.length || !isRegexWord(payload[end])
                val decoded = if (leftBoundary && rightBoundary) replacements[token.value] else null
                if (decoded != null) {
                    out.append(payload, cursor, start)
                    out.append(decoded)
                    cursor = end
                }
            }
            out.append(payload, cursor, payload.length)
            out.toString()
        }.getOrNull()
    }

    private fun isRegexWord(char: Char): Boolean =
        char == '_' || char in '0'..'9' || char in 'a'..'z' || char in 'A'..'Z'

    /**
     * Decode safe, literal-only String.fromCharCode calls commonly used by obfuscated players.
     * Non-numeric expressions are left untouched; this intentionally does not execute JavaScript.
     */
    private fun decodeFromCharCodeExpressions(raw: String): String {
        var current = raw
        repeat(4) {
            val withApply = fromCharCodeApplyPattern.replace(current) { match ->
                decodeCharCodeArguments(match.groupValues[1])?.let(::quoteJsLiteral) ?: match.value
            }
            val next = fromCharCodePattern.replace(withApply) { match ->
                decodeCharCodeArguments(match.groupValues[1])?.let(::quoteJsLiteral) ?: match.value
            }
            if (next == current) return current
            current = next
        }
        return current
    }

    private fun decodeCharCodeArguments(raw: String): String? {
        var source = raw.trim()
        if (source.startsWith("...")) source = source.substring(3).trim()
        if (source.startsWith("[") && source.endsWith("]")) {
            source = source.substring(1, source.length - 1).trim()
        }
        if (source.isEmpty()) return null
        val tokens = source.split(',')
        if (tokens.isEmpty() || tokens.size > 2048) return null
        val codes = tokens.map { token -> parseCharCode(token) ?: return null }
        return buildString(codes.size) {
            for (code in codes) append((code and 0xFFFF).toChar())
        }
    }

    private fun parseCharCode(raw: String): Int? {
        val token = raw.trim().removeSurrounding("(", ")").trim().removePrefix("+")
        if (token.matches(Regex("""-?0[xX][0-9a-fA-F]{1,8}"""))) {
            val negative = token.startsWith("-")
            val hex = token.removePrefix("-").drop(2).toLongOrNull(16) ?: return null
            val signed = if (negative) -hex else hex
            return signed.toInt()
        }
        if (token.matches(Regex("""-?\d{1,10}"""))) {
            val value = token.toLongOrNull() ?: return null
            if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
            return value.toInt()
        }
        return null
    }

    private fun quoteJsLiteral(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r") + "\""

    private fun isInterestingDecodedText(text: String): Boolean =
        text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true) ||
            listOf(".m3u8", ".mp4", "jwplayer", "file_link", "fromCharCode", "atob(", "eval(")
                .any { text.contains(it, ignoreCase = true) }

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
            if (isInterestingDecodedText(asString)) {
                out.add(asString)
            }
        }
        return out.toList()
    }

    /**
     * Parca-birlestirme adaylarini uretir: akis URL'si parcalara bolunup
     * degiskenlere atanir, sonra (ters cevrilip) birlestirilerek atob()
     * ile acilir. URL dogrulamasi cagiran tarafta (extractStreamUrls)
     * URL desenleriyle yapilir; burada yalnizca aday dizgiler uretilir.
     * Her aday hem duz hem ters yonde verilir, boylece .reverse()
     * kullanimi ve split('').reverse().join('') varyantlari kaplanir.
     */
    fun reassembleFragmentedStreams(text: String): List<String> {
        val literals = HashMap<String, String>()
        // 0) 'a'+'b' biciminde birlestirilmis atamalar once tam deger olarak
        //    sozluge yazilir (literalAssignPattern yalnizca ilk parci yakalar).
        for (match in concatAssignPattern.findAll(text)) {
            val joined = quotedPiecePattern.findAll(match.groupValues[2])
                .joinToString("") { stripQuotes(it.value) }
            if (joined.isNotBlank()) literals.putIfAbsent(match.groupValues[1], joined)
        }
        for (match in literalAssignPattern.findAll(text)) {
            val value = match.groupValues[2]
            if (value.isNotBlank()) literals.putIfAbsent(match.groupValues[1], value)
        }
        if (literals.isEmpty()) return emptyList()

        val out = LinkedHashSet<String>()
        // 1) [a,b,c].join('') — .reverse() olsun olmasin iki yonde aday uret
        for (match in arrayJoinPattern.findAll(text)) {
            val ids = match.groupValues[1].split(',').map { it.trim() }
            if (ids.isEmpty() || ids.any { !literals.containsKey(it) }) continue
            val forward = ids.joinToString("") { literals[it].orEmpty() }
            val reverseOrder = ids.reversed().joinToString("") { literals[it].orEmpty() }
            for (candidate in listOf(forward, reverseOrder)) {
                if (candidate.length >= 8) {
                    out.add(candidate)
                    out.add(candidate.reversed())
                }
            }
        }
        // 2) atob(X) — X tanimlayici, birlestirme veya ters-cevirme ifadesi
        for (match in atobCallPattern.findAll(text)) {
            resolveAtobArgument(match.groupValues[1], literals)?.let { arg ->
                if (arg.length >= 8) {
                    out.add(arg)
                    out.add(arg.reversed())
                }
            }
        }
        return out.toList()
    }

    private fun resolveAtobArgument(exprRaw: String, literals: Map<String, String>): String? {
        val expr = exprRaw.trim()
        if (expr.isEmpty()) return null
        // Dizgi tersine cevirme: x.split('').reverse().join('')
        if (expr.contains(".split(")) {
            val base = resolveSimpleToken(expr.substringBefore(".").trim(), literals) ?: return null
            return base.reversed()
        }
        // Birlestirme: a + b + 'c'
        if (expr.contains("+")) {
            val sb = StringBuilder()
            for (rawToken in expr.split("+")) {
                val piece = resolveSimpleToken(rawToken, literals) ?: return null
                sb.append(piece)
            }
            return sb.toString().takeIf { it.isNotEmpty() }
        }
        return resolveSimpleToken(expr, literals)
    }

    private fun resolveSimpleToken(token: String, literals: Map<String, String>): String? {
        val t = token.trim()
        if (t.isEmpty()) return null
        if ((t.startsWith("'") && t.endsWith("'") && t.length >= 2) ||
            (t.startsWith("\"") && t.endsWith("\"") && t.length >= 2)
        ) {
            return t.substring(1, t.length - 1)
        }
        if (identifierPattern.matches(t)) return literals[t]
        return null
    }

    private fun stripQuotes(s: String): String {
        if (s.length >= 2 &&
            ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\"")))
        ) {
            return s.substring(1, s.length - 1)
        }
        return s
    }

    /**
     * Uzunlugu ve karakter kumesi base64 uyumlu adaylari cozer; cozum
     * basarisizsa null doner (aday URL degilse de sonuc URL deseni
     * eslesmedigi icin zararsizca elenir).
     */
    internal fun tryDecodeBase64Text(candidate: String): String? {
        val clean = candidate.filter { !it.isWhitespace() }
        if (clean.length < 8 || clean.length % 4 == 1) return null
        for (c in clean) {
            val ok = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                c == '+' || c == '/' || c == '-' || c == '_' || c == '='
            if (!ok) return null
        }
        return runCatching { String(base64Decode(clean), Charsets.UTF_8) }.getOrNull()
    }

    /** URL-safe olmayan, padding destekli minimal base64 cozucu (JVM/JUnit uyumlu). */
    private fun base64Decode(input: String): ByteArray {
        val table = IntArray(128) { -1 }
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { i, c ->
            table[c.code] = i
        }
        val compact = input.filter { it != ' ' && it != '\n' && it != '\r' && it != '\t' }
            .replace('-', '+')
            .replace('_', '/')
        require(compact.length % 4 != 1) { "gecersiz base64 uzunluk" }
        val clean = compact + "=".repeat((4 - compact.length % 4) % 4)
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
        if (trimmed.isBlank()) return base
        if (trimmed.startsWith("//")) return "https:" + trimmed
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return runCatching {
            java.net.URI(base).resolve(java.net.URI(trimmed)).toString()
        }.getOrElse {
            base.trimEnd('/') + "/" + trimmed.trimStart('/')
        }
    }

    fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")

    fun playerLabel(url: String): String = when {
        url.contains("rapid", ignoreCase = true) -> "Rapid"
        url.contains("closeload", ignoreCase = true) -> "CloseLoad"
        else -> "Kaynak"
    }
}
