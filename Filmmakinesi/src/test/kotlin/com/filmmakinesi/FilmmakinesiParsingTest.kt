package com.filmmakinesi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmmakinesiParsingTest {

    @Test
    fun acceptsFilmUrlsAndRejectsOthers() {
        assertTrue(FilmmakinesiParsing.isFilmUrl("/film/sev-beni-sev-beni-2026/"))
        assertTrue(FilmmakinesiParsing.isFilmUrl("https://filmmakinesi.to/film/disco-2026/"))
        assertTrue(FilmmakinesiParsing.isFilmUrl("/film/the-legend-of-aang-fm3/"))
        assertFalse(FilmmakinesiParsing.isFilmUrl("/seri-filmler-izle/cadilar-bayrami/"))
        assertFalse(FilmmakinesiParsing.isFilmUrl("/filmler-1/"))
        assertFalse(FilmmakinesiParsing.isFilmUrl("javascript:void(0)"))
    }

    @Test
    fun extractsPlayerUrlsFromMarkupVariants() {
        val html = """
            <iframe src="https://rapid.filmmakinesi.to/embed-tcvrkicsgrjh"></iframe>
            <div data-link="https://closeload.filmmakinesi.to/video/embed/abc123"></div>
            <iframe src="https://www.youtube.com/embed/xyz"></iframe>
        """.trimIndent()
        val urls = FilmmakinesiParsing.extractPlayerUrls(html)
        assertEquals(
            listOf(
                "https://rapid.filmmakinesi.to/embed-tcvrkicsgrjh",
                "https://closeload.filmmakinesi.to/video/embed/abc123"
            ),
            urls
        )
    }

    @Test
    fun findsBarePlayerUrlsInRawHtml() {
        val html = "<script>var src=\"https://rapid.filmmakinesi.to/embed-xyz789\";</script>"
        val urls = FilmmakinesiParsing.extractPlayerUrls(html)
        assertEquals(listOf("https://rapid.filmmakinesi.to/embed-xyz789"), urls)
    }

    @Test
    fun normalizesEscapedStreamUrls() {
        val raw = "file:\"https:\/\/rapid.filmmakinesi.to\/cdn\/x.m3u8?a=1&amp;b=2\""
        val normalized = FilmmakinesiParsing.normalizeEscaped(raw)
        assertTrue(normalized.contains("https://rapid.filmmakinesi.to/cdn/x.m3u8?a=1&b=2"))
    }

    @Test
    fun extractsM3u8FromRawMarkup() {
        assertEquals(
            "https://rapid.filmmakinesi.to/cdn/master.m3u8?tok=1",
            FilmmakinesiParsing.extractM3u8("sources:[{src:\"https:\/\/rapid.filmmakinesi.to\/cdn\/master.m3u8?tok=1\"}]")
        )
        assertNull(FilmmakinesiParsing.extractM3u8("<html>no stream here</html>"))
    }

    @Test
    fun extractsYearAndImdbFromCardText() {
        assertEquals(2026, FilmmakinesiParsing.extractYear("IMDB 7.5 2026 Aksiyon 2 Saat 5 Dakika"))
        assertNull(FilmmakinesiParsing.extractYear("Aksiyon Filmi"))
        assertEquals(7.5f, FilmmakinesiParsing.extractImdb("IMDB 7.5")!!, 0.01f)
        assertNull(FilmmakinesiParsing.extractImdb("Puan 7.5"))
        assertNull(FilmmakinesiParsing.extractImdb("IMDB verisi yok"))
    }

    @Test
    fun resolvesRelativeAndProtocolRelativeUrls() {
        assertEquals(
            "https://filmmakinesi.to/film/disco-2026/",
            FilmmakinesiParsing.safeAbsoluteUrl("/film/disco-2026/", "https://filmmakinesi.to")
        )
        assertEquals(
            "https://filmmakinesi.to/img.jpg",
            FilmmakinesiParsing.safeAbsoluteUrl("//filmmakinesi.to/img.jpg", "https://filmmakinesi.to")
        )
        assertEquals(
            "https://filmmakinesi.to/x",
            FilmmakinesiParsing.safeAbsoluteUrl("x", "https://filmmakinesi.to/")
        )
    }

    @Test
    fun labelsKnownPlayerHosts() {
        assertEquals("Rapid", FilmmakinesiParsing.playerLabel("https://rapid.filmmakinesi.to/embed-x"))
        assertEquals("CloseLoad", FilmmakinesiParsing.playerLabel("https://closeload.filmmakinesi.to/video/embed/x"))
        assertEquals("Kaynak", FilmmakinesiParsing.playerLabel("https://other.example/e"))
    }
}
