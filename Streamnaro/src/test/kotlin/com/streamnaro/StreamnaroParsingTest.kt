package com.streamnaro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamnaroParsingTest {
    private val routePrefix = "https://streamnaro.example/__catalog__/"

    @Test
    fun validatesImdbIdsStrictly() {
        assertTrue(StreamnaroParsing.isImdbId("tt12345"))
        assertTrue(StreamnaroParsing.isImdbId("tt1234567890"))
        assertFalse(StreamnaroParsing.isImdbId("tt1234"))
        assertFalse(StreamnaroParsing.isImdbId("TT12345"))
        assertFalse(StreamnaroParsing.isImdbId("tt12345678901"))
        assertFalse(StreamnaroParsing.isImdbId("tt12345/extra"))
    }

    @Test
    fun acceptsOnlyWellFormedHttpsPosterUrls() {
        assertEquals(
            "https://cdn.example.org/poster.jpg?size=large",
            StreamnaroParsing.safeHttpsUrl("  https://cdn.example.org/poster.jpg?size=large  ")
        )
        assertNull(StreamnaroParsing.safeHttpsUrl(null))
        assertNull(StreamnaroParsing.safeHttpsUrl(" "))
        assertNull(StreamnaroParsing.safeHttpsUrl("http://cdn.example.org/poster.jpg"))
        assertNull(StreamnaroParsing.safeHttpsUrl("https://"))
        assertNull(StreamnaroParsing.safeHttpsUrl("https://user:password@cdn.example.org/poster.jpg"))
    }

    @Test
    fun buildsCinemetaCatalogPaginationPaths() {
        assertEquals(
            "https://v3-cinemeta.strem.io/catalog/movie/top.json",
            StreamnaroParsing.catalogPageUrl(
                "https://v3-cinemeta.strem.io/catalog/movie/top.json",
                0
            )
        )
        assertEquals(
            "https://v3-cinemeta.strem.io/catalog/movie/top/skip=100.json",
            StreamnaroParsing.catalogPageUrl(
                "https://v3-cinemeta.strem.io/catalog/movie/top.json",
                100
            )
        )
        assertEquals(
            "https://v3-cinemeta.strem.io/catalog/series/top/skip=200.json",
            StreamnaroParsing.catalogPageUrl(
                "https://v3-cinemeta.strem.io/catalog/series/top",
                200
            )
        )
        assertEquals(
            "https://v3-cinemeta.strem.io/catalog/movie/top.json",
            StreamnaroParsing.catalogPageUrl(
                "https://v3-cinemeta.strem.io/catalog/movie/top",
                -100
            )
        )
    }

    @Test
    fun parsesEpisodeCoordinatesFromFieldsOrVideoId() {
        assertEquals(
            2 to 9,
            StreamnaroParsing.parseEpisodeCoordinates("tt1234567:2:9", null, null)
        )
        assertEquals(
            3 to 5,
            StreamnaroParsing.parseEpisodeCoordinates("tt1234567:2:9", 3, 5)
        )
        assertNull(StreamnaroParsing.parseEpisodeCoordinates("tt1234567:2", null, null))
        assertNull(StreamnaroParsing.parseEpisodeCoordinates("tt1234567:-1:2", null, null))
        assertNull(StreamnaroParsing.parseEpisodeCoordinates("tt1234567:2:0", null, null))
    }

    @Test
    fun parsesOnlySupportedItemRoutes() {
        assertEquals(
            "movie" to "tt12345",
            StreamnaroParsing.parseItemRoute("${routePrefix}movie/tt12345", routePrefix)
        )
        assertEquals(
            "series" to "tt123456",
            StreamnaroParsing.parseItemRoute("${routePrefix}series/tt123456", routePrefix)
        )
        assertNull(
            StreamnaroParsing.parseItemRoute("${routePrefix}episode/tt12345/1/1", routePrefix)
        )
        assertNull(
            StreamnaroParsing.parseItemRoute("${routePrefix}movie/tt12345/extra", routePrefix)
        )
        assertNull(
            StreamnaroParsing.parseItemRoute("${routePrefix}movie/not-an-imdb-id", routePrefix)
        )
        assertNull(
            StreamnaroParsing.parseItemRoute(
                "https://streamnaro.example.evil/__catalog__/movie/tt12345",
                routePrefix
            )
        )
    }

    @Test
    fun parsesValidatedInternalEpisodeRoutes() {
        assertEquals(
            StreamnaroParsing.EpisodeRoute("tt1234567", 1, 1),
            StreamnaroParsing.parseEpisodeRoute(
                "${routePrefix}episode/tt1234567/1/1",
                "streamnaro://"
            )
        )
        assertEquals(
            StreamnaroParsing.EpisodeRoute("tt1234567", 0, 9),
            StreamnaroParsing.parseEpisodeRoute(
                "${routePrefix}episode/tt1234567/0/9",
                "streamnaro://"
            )
        )
        assertNull(
            StreamnaroParsing.parseEpisodeRoute(
                "${routePrefix}episode/tt1234567/-1/1",
                "streamnaro://"
            )
        )
        assertNull(
            StreamnaroParsing.parseEpisodeRoute(
                "${routePrefix}episode/tt1234567/1/0",
                "streamnaro://"
            )
        )
        assertNull(
            StreamnaroParsing.parseEpisodeRoute(
                "${routePrefix}episode/not-imdb/1/1",
                "streamnaro://"
            )
        )
        assertNull(
            StreamnaroParsing.parseEpisodeRoute(
                "${routePrefix}episode/tt1234567/1/1/extra",
                "streamnaro://"
            )
        )
        assertNull(
            StreamnaroParsing.parseEpisodeRoute(
                "https://example.org/episode/tt1234567/1/1",
                "streamnaro://"
            )
        )
    }
}
