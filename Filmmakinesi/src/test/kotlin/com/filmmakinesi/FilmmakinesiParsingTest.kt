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
        val raw = "file:\"https:\\/\\/rapid.filmmakinesi.to\\/cdn\\/x.m3u8?a=1&amp;b=2\""
        val normalized = FilmmakinesiParsing.normalizeEscaped(raw)
        assertTrue(normalized.contains("https://rapid.filmmakinesi.to/cdn/x.m3u8?a=1&b=2"))
    }

    @Test
    fun extractsM3u8FromRawMarkup() {
        assertEquals(
            "https://rapid.filmmakinesi.to/cdn/master.m3u8?tok=1",
            FilmmakinesiParsing.extractM3u8("sources:[{src:\"https:\\/\\/rapid.filmmakinesi.to\\/cdn\\/master.m3u8?tok=1\"}]")
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
    fun urlSlugYearTakesPriorityOverFreeText() {
        // URL slug'inda yil varsa metindeki ilk yil degil o kullanilir:
        // metindeki "1998" konu baglamindan geliyor olabilir.
        assertEquals(
            2026,
            FilmmakinesiParsing.extractYear("1998 yilinda gecen bir hikaye", "https://filmmakinesi.to/film/disco-2026/")
        )
        // Slug'da yil yoksa metne dusulur (geri uyumlu davranis).
        assertEquals(
            2019,
            FilmmakinesiParsing.extractYear("Vizyon 2019", "https://filmmakinesi.to/film/the-legend-of-aang-fm3/")
        )
        // Hint'siz cagri eski davranisi korur.
        assertEquals(2024, FilmmakinesiParsing.extractYear("IMDB 6.1 2024"))
    }

    @Test
    fun imdbRatingMustSitNextToImdbKeyword() {
        assertEquals(7.5f, FilmmakinesiParsing.extractImdb("IMDB: 7.5/10")!!, 0.01f)
        assertEquals(6.9f, FilmmakinesiParsing.extractImdb("imdb 6.9")!!, 0.01f)
        // "imdb" gecen bir metinde uzaktaki x.y sayisi puan sayilmamaliydi
        // (onceki desen ilk x.y'yi aliyordu).
        assertNull(FilmmakinesiParsing.extractImdb("IMDB puanina sahip, 2.5 saat suren film"))
        assertNull(FilmmakinesiParsing.extractImdb("Sadece bir film"))
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
        assertEquals(
            "https://rapid.filmmakinesi.to/player/setup.js",
            FilmmakinesiParsing.safeAbsoluteUrl(
                "/player/setup.js",
                "https://rapid.filmmakinesi.to/embed-abc/"
            )
        )
        assertEquals(
            "https://rapid.filmmakinesi.to/embed-abc/setup.js",
            FilmmakinesiParsing.safeAbsoluteUrl(
                "setup.js",
                "https://rapid.filmmakinesi.to/embed-abc/"
            )
        )
    }

    @Test
    fun labelsKnownPlayerHosts() {
        assertEquals("Rapid", FilmmakinesiParsing.playerLabel("https://rapid.filmmakinesi.to/embed-x"))
        assertEquals("CloseLoad", FilmmakinesiParsing.playerLabel("https://closeload.filmmakinesi.to/video/embed/x"))
        assertEquals("Kaynak", FilmmakinesiParsing.playerLabel("https://other.example/e"))
    }

    @Test
    fun unpacksDeanEdwardsPackerAndFindsStream() {
        val packed = "eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(" +
            "new RegExp('\\\\b'+c+'\\\\b','g'),k[c]);return p}" +
            "('0 1 2',62,3,'var|source|\"https:\"+\"//cdn.x.y/\"+\"film\"+\".m3u8?token=xyz\";|'." +
            "split('|'),0,{}))"
        val urls = FilmmakinesiParsing.extractStreamUrls(packed)
        assertTrue(urls.any { it.contains("cdn.x.y/film.m3u8") })
    }

    @Test
    fun decodesBase64EmbeddedStream() {
        val b64 = java.util.Base64.getEncoder()
            .encodeToString("https://cdn.x.y/playlist.m3u8?sig=zz".toByteArray())
        val urls = FilmmakinesiParsing.extractStreamUrls(
            "<script>var s=atob(\"$b64\");</script>")
        assertTrue(urls.any { it.contains("playlist.m3u8") })
    }

    // ---------------------------------------------------------------
    // Logcat (2026-10-22) senaryolarina karsi gerileme testleri
    // ---------------------------------------------------------------

    @Test
    fun hlsMasterTxtIsNotMisclassifiedAsMp4() {
        // Logcat'te site HLS master manifest'ini
        // "...mp4/master.txt" path'iyle sundu; eski mp4 deseni bunu
        // butun olarak mp4 sanip CloudStream'de progressive VIDEO +
        // HTTP 404'e yol aciyordu.
        val urls = FilmmakinesiParsing.extractStreamUrls(
            "src:\"https://hls8.playmix.uno/hls/filmakinesimp4-f9gx1M12BwC.mp4/master.txt\""
        )
        assertEquals(1, urls.size)
        assertTrue(urls.single().endsWith("master.txt"))
        assertTrue(FilmmakinesiParsing.isLikelyHls(urls.single()))
    }

    @Test
    fun plainMp4StaysProgressive() {
        val urls = FilmmakinesiParsing.extractStreamUrls(
            "file:\"https://cdn.ornek.site/video/film.mp4\""
        )
        assertEquals(listOf("https://cdn.ornek.site/video/film.mp4"), urls)
        assertFalse(FilmmakinesiParsing.isLikelyHls(urls.single()))
    }

    @Test
    fun mp4WithQueryStringIsStillCollected() {
        // ".mp4" path sonu kisitlamasi query string'i yutmamaliydi.
        val urls = FilmmakinesiParsing.extractStreamUrls(
            "https://cdn.ornek.site/video/film.mp4?token=abc123&exp=1"
        )
        assertEquals(1, urls.size)
        assertTrue(urls.single().contains("?token=abc123"))
        assertFalse(FilmmakinesiParsing.isLikelyHls(urls.single()))
    }

    @Test
    fun detectsHlsByMasterIndexOrHlsPath() {
        assertTrue(FilmmakinesiParsing.isLikelyHls("https://cdn.x/master.m3u8"))
        assertTrue(FilmmakinesiParsing.isLikelyHls("https://hls8.playmix.uno/hls/x.mp4/master.txt"))
        assertTrue(FilmmakinesiParsing.isLikelyHls("https://cdn.x/hls/f/index.m3u8?tok=1"))
        assertFalse(FilmmakinesiParsing.isLikelyHls("https://cdn.x/video/film.mp4"))
    }

    @Test
    fun extractsInterstitialRedirectTargets() {
        val meta = "<meta http-equiv=\"refresh\" content=\"3;url=https://rapid.filmmakinesi.to/embed-abc\">"
        val js = "<script>window.location.replace(\"https://rapid.filmmakinesi.to/embed-def\");</script>"
        val jsHref = "<script>location.href = \"https://rapid.filmmakinesi.to/embed-ghi\";</script>"
        val none = "<html>akisku yok</html>"
        assertEquals(
            listOf("https://rapid.filmmakinesi.to/embed-abc"),
            FilmmakinesiParsing.extractRedirectTargets(meta)
        )
        assertEquals(
            listOf("https://rapid.filmmakinesi.to/embed-def"),
            FilmmakinesiParsing.extractRedirectTargets(js)
        )
        assertEquals(
            listOf("https://rapid.filmmakinesi.to/embed-ghi"),
            FilmmakinesiParsing.extractRedirectTargets(jsHref)
        )
        assertTrue(FilmmakinesiParsing.extractRedirectTargets(none).isEmpty())
    }

    @Test
    fun jsRedirectDoesNotFalsePositiveOnFieldNames() {
        // "someLocation=" gibi alan adlari yonlendirme sayilmamaliydi.
        val html = "<script>var someLocation = \"https://not.a.redirect/x\";</script>"
        assertTrue(FilmmakinesiParsing.extractRedirectTargets(html).isEmpty())
    }

    @Test
    fun jwplayerConcatSplitStreamIsFoundAfterRejoin() {
        // Logcat (2026-10-22 09:26): rapid embed JWPlayer kabugu ve akis
        // URL'sini parcali string birlestirme ile gizliyor; dizgun
        // metinde hicbir desen eslesmiyor.
        val page = "jwplayer(\"player\").setup({sources:[{file:\"https:\"+\"//hls8.playmix.uno\"+\"/hls/filmakinesimp4-GERCEKTOKEN.mp4\"+\"/master.m3u8?tok=ABC123\"}]});"
        val urls = FilmmakinesiParsing.extractStreamUrls(page)
        assertEquals(1, urls.size)
        assertTrue(urls.single().contains("hls8.playmix.uno/hls/filmakinesimp4-GERCEKTOKEN.mp4/master.m3u8?tok=ABC123"))
        assertTrue(FilmmakinesiParsing.isLikelyHls(urls.single()))
    }

    @Test
    fun extractsScriptSrcsWithSameHostFirst() {
        val html = "<script src=\"/player/setup.js\"></script>" +
            "<script src=\"https://rapid.filmmakinesi.to/player/core.js\"></script>" +
            "<script src=\"https://cdn.ornek.site/lib.js\"></script>"
        val srcs = FilmmakinesiParsing.extractScriptSrcs(html, "https://rapid.filmmakinesi.to/embed-x/")
        assertEquals(3, srcs.size)
        assertTrue(srcs[0].contains("rapid.filmmakinesi.to"))
        assertTrue(srcs[1].contains("rapid.filmmakinesi.to"))
        assertTrue(srcs[2].contains("cdn.ornek.site"))
    }

    @Test
    fun snippetsReportPlayerMarkers() {
        val html = "<html><body>jwplayer(\"player\").setup({file:\"x.m3u8\"})</body></html>"
        val s = FilmmakinesiParsing.interestingSnippets(html)
        assertTrue(s.contains("jwplayer@"))
        assertTrue(s.contains("setup(@"))
        assertTrue(s.contains("m3u8@"))
    }

    @Test
    fun skipsUnplayableHomepageSections() {
        // Ana sayfa bolumleri: yaklasan vizyon/dizi/bolum/fragman
        // oynatilamaz; "Vizyondakiler" gibi oynatilabilir bolumler
        // kalip ifadeye takilmamali.
        assertTrue(FilmmakinesiParsing.isSkippedHomeSection("Vizyon Tarihi Yaklaşan Filmler"))
        assertTrue(FilmmakinesiParsing.isSkippedHomeSection("Son Diziler"))
        assertTrue(FilmmakinesiParsing.isSkippedHomeSection("Son Eklenen Bölümler"))
        assertTrue(FilmmakinesiParsing.isSkippedHomeSection("Fragmanlar"))
        assertFalse(FilmmakinesiParsing.isSkippedHomeSection("Öne Çıkanlar"))
        assertFalse(FilmmakinesiParsing.isSkippedHomeSection("Son Filmler"))
        assertFalse(FilmmakinesiParsing.isSkippedHomeSection("Vizyondakiler"))
    }

    // ---------------------------------------------------------------
    // Logcat (2026-10-22 10:46) senaryosu: runtime atob + parca birlestirme
    // Site akis URL'sini parcalara bolup degiskenlere atiyor, sonra
    // "[p...].reverse().join('')" ile birlestirip atob() ile aciyor
    // (log: "file:atob(file_link)").
    // ---------------------------------------------------------------

    private fun base64Of(value: String): String =
        java.util.Base64.getEncoder().encodeToString(value.toByteArray())

    @Test
    fun reassemblesReversedFragmentArrayBase64Stream() {
        // Cozulmus JS gorunumu:
        //   var f0='sonParca';...;var file_link=[f0,f1,...].reverse().join('');
        //   jwplayer('player').setup({sources:[{file:atob(file_link)}]});
        val url = "https://hls8.playmix.uno/hls/filmakinesimp4-GERCEKTOKEN.mp4/master.m3u8?tok=AB"
        val pieces = base64Of(url).chunked(6)
        val reversed = pieces.reversed()
        val vars = reversed.mapIndexed { i, p -> "var f$i='$p';" }.joinToString("")
        val arrayExpr = "var file_link=[" +
            reversed.indices.joinToString(",") { "f$it" } + "].reverse().join('');"
        val page = "<script>$vars$arrayExpr " +
            "jwplayer('player').setup({sources:[{file:atob(file_link)}]});</script>"
        val urls = FilmmakinesiParsing.extractStreamUrls(page)
        assertTrue(
            "Parcali + ters + base64 akis URL'si cozulemeli",
            urls.any { it.contains("filmakinesimp4-GERCEKTOKEN.mp4/master.m3u8") }
        )
    }

    @Test
    fun reassemblesFragmentsInsidePackedJs() {
        // Packer icindeki parca birlestirme: once packer acilmali, sonra
        // [rp5,end44].reverse().join('') birlestirilip atob ile acilmali.
        // Sozluk indeksleri (radix 36, tek karakterli anahtarlar):
        //   0:var 1:rp5 2:'c1' 3:end44 4:'c0' 5:file_link 6:atob 7:return 8:''
        val url = "https://cdn.x.y/a.m3u8"
        val b64 = base64Of(url)
        val half = b64.length / 2
        val c0 = b64.substring(0, half)
        val c1 = b64.substring(half)
        // Dizide [rp5,end44] var; reverse() sonrasi join = end44+rp5 = c0+c1 = b64.
        // Sozlukte parca DEGERLERI gercek base64 yarimlari olmali.
        val payload = "0 1=2;0 3=4;0 5=[1,3].reverse().join(8);7 6(5)"
        val dict = "var|rp5|\\'" + c1 + "\\'|end44|\\'" + c0 +
            "\\'|file_link|atob|return|\\'\\'"
        val packed = "eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(" +
            "new RegExp('\\\\b'+c+'\\\\b','g'),k[c]);return p}" +
            "('" + payload + "',36,9,'" + dict + "'.split('|'),0,{}))"
        val urls = FilmmakinesiParsing.extractStreamUrls(packed)
        assertTrue("Packer icindeki parcali akis URL'si cozulemeli", urls.any { it == url })
    }

    @Test
    fun decodesAtobWithConcatenatedFragments() {
        // var file_link='parca1'+'parca2'; file:atob(file_link)
        val url = "https://cdn.ornek.site/v/film.mp4"
        val b64 = base64Of(url)
        val mid = b64.length / 2
        val page = "var file_link='" + b64.substring(0, mid) + "'+'" + b64.substring(mid) +
            "';file:atob(file_link),sources:[{file:s7e}]"
        val urls = FilmmakinesiParsing.extractStreamUrls(page)
        assertTrue(
            "Birlestirilmis parcalardan atob ile akis cozulemeli",
            urls.any { it.contains("cdn.ornek.site/v/film.mp4") }
        )
    }

    @Test
    fun realTokenIsFoundEvenWhenDecoyManifestIsPresent() {
        // Logdaki kritik durum: sayfa ayni anda olu yem (decoy) master.txt
        // icerir ve gercek token parcalidir; yem, parca-birlestirmeyi
        // engellememeli (katman kosulsuz calisir).
        val decoy = "src:\"https://hls8.playmix.uno/hls/filmakinesimp4-f9gx1M12BwC.mp4/master.txt\""
        val url = "https://hls9.playmix.uno/hls/filmakinesimp4-REELTOKEN.mp4/index.m3u8"
        val pieces = base64Of(url).chunked(5).reversed()
        val vars = pieces.mapIndexed { i, s -> "var q$i='$s';" }.joinToString("")
        val arr = "var file_link=[" +
            pieces.indices.joinToString(",") { "q$it" } + "].reverse().join('');"
        val page = "$decoy <script>$vars$arr file:atob(file_link)</script>"
        val urls = FilmmakinesiParsing.extractStreamUrls(page)
        assertTrue(urls.any { it.contains("filmakinesimp4-REELTOKEN.mp4/index.m3u8") })
        assertTrue("Yem manifest listelenmeye devam etmeli (HEAD ile elenir)", urls.any { it.endsWith("master.txt") })
    }

    @Test
    fun reassemblyDoesNotFabricateStreamsFromPlainMarkup() {
        // Yanlis pozitif korumasi: parca/atob icermediği halde ekstra
        // URL uretilmemeli (mevcut deseni bozmamali).
        val urls = FilmmakinesiParsing.extractStreamUrls(
            "<html><body><div class='x' data-info='abc'>normal sayfa</div></body></html>"
        )
        assertTrue(urls.isEmpty())
    }
}
