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
    private val mp4Pattern =
        Regex("""(?i)(https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*)""")
    private val yearPattern = Regex("""\b(19|20)\d{2}\b""")
    // IMDB puani yalnizca "imdb" kelimesine yapisik bolgeden (en fazla 15
    // ayirici karakter) araniyor; "/10" oneki varsa tuketiliyor.
    private val imdbPattern =
        Regex("""(?i)imdb[\s:.\-]{0,15}?\b([0-9]\.[0-9])\b(?:\s*/\s*10)?""")
    // Film URL'lerindeki "...-2026/" bicimli yil onegi.
    private val slugYearPattern = Regex("""-(19|20)\d{2}(?:[/?#]|$)""")

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
     * Tum oynatilabilir akis URL'lerini (m3u8 ve mp4) cikarir.
     * Once kacis normalize edilir; bulunamazsa paketlenmis JS cozulup ve
     * base64 token'lar acilip desenler tekrar denenir.
     */
    fun extractStreamUrls(raw: String): List<String> {
        val normalized = normalizeEscaped(raw)
        val found = LinkedHashSet<String>()

        fun collectFrom(text: String) {
            for (match in m3u8Pattern.findAll(text)) {
                found.add(match.value.trimEnd('"', '\'', ';', ',', '\\'))
            }
            for (match in mp4Pattern.findAll(text)) {
                found.add(match.value.trimEnd('"', '\'', ';', ',', '\\'))
            }
        }

        collectFrom(normalized)

        if (found.isEmpty()) {
            // JS string birlestirme temizligi: "https:"+"//cdn..." -> https://cdn...
            // (cozulmus kaynak kodda parcali string'ler tek URL'ye birlesir)
            unpackPackedJs(normalized)?.let { collectFrom(it.replace("\"+\"", "")) }
        }
        if (found.isEmpty()) {
            for (decoded in decodeBase64Tokens(normalized)) {
                collectFrom(decoded)
            }
        }
        if (found.isEmpty()) {
            // Son care: ham base64 icinde gomulu m3u8 olabilir
            for (decoded in decodeBase64Tokens(normalized)) {
                val inner = unpackPackedJs(decoded)
                if (inner != null) collectFrom(inner)
            }
        }
        return found.toList()
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
     * cunku atof icindeki string ayni zamanda duz token olarak da gorunur).
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
