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
    private val yearPattern = Regex("""\b(19|20)\d{2}\b""")
    // DUZELTME: Onceki desen metinde "imdb" kelimesi gecse bile HERHANGI
    // bir x.y sayisini puan sanabiliyordu (or. "IMDB 7.5 2.5 saat" gibi
    // bir metinde ilk eslesen sayi seciliyordu). Artik puan deseni yalnizca
    // "imdb" kelimesine yapisik bolgeden (en fazla 15 ayirici karakter)
    // araniyor; "/10" oneki varsa da tuketiliyor.
    private val imdbPattern =
        Regex("""(?i)imdb[\s:.\-]{0,15}?\b([0-9]\.[0-9])\b(?:\s*/\s*10)?""")
    // DUZELTME: Film URL'lerindeki "...-2026/" bicimli yil onegi.
    // Metin icindeki ilk yil yanlis olabilir (or. konu aciklamasinda
    // gegen baska bir yil); URL slug'indaki "-YYYY" kalibi cok daha guvenilir.
    private val slugYearPattern = Regex("""-(19|20)\d{2}(?:[/?#]|$)""")

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

    fun extractM3u8(raw: String): String? {
        // Embed sayfalari akis URL'lerini kacisli (\/) veriyor; deseni
        // normalize edilmis metne uygula ki hem bu fonksiyon tek basina
        // dogru calissin hem de birim testi beklenen degeri gorsun.
        val match = m3u8Pattern.find(normalizeEscaped(raw)) ?: return null
        return match.value.trimEnd('"', '\'', ';', ',')
    }

    /**
     * Yil cikarir. [urlHint] verilirse once oradaki "-YYYY/" kalibi
     * denenir (film URL slug'lari genelde "...-2026/" bicimindedir);
     * bulunamazsa metindeki ilk 19xx/20xx sayisina duser. Bu, konu
     * metninin icinde gegen baglamsiz yillarin yanlis secilmesini azaltir.
     */
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
