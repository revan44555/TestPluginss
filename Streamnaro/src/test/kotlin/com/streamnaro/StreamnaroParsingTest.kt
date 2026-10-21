package com.streamnaro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamnaroParsingTest {
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
            StreamnaroParsing.parseItemRoute("streamnaro://movie/tt12345", "streamnaro://")
        )
        assertEquals(
            "series" to "tt123456",
            StreamnaroParsing.parseItemRoute("streamnaro://series/tt123456", "streamnaro://")
        )
        assertNull(
            StreamnaroParsing.parseItemRoute("streamnaro://episode/tt12345/1/1", "streamnaro://")
        )
        assertNull(
            StreamnaroParsing.parseItemRoute("streamnaro://movie/tt12345/extra", "streamnaro://")
        )
        assertNull(
            StreamnaroParsing.parseItemRoute("streamnaro://movie/not-an-imdb-id", "streamnaro://")
        )
    }

    @Test
    fun parsesValidatedInternalEpisodeRoutes() {
        assertEquals(
            StreamnaroParsing.EpisodeRoute("tt1234567", 1, 1),
            StreamnaroParsing.parseEpisodeRoute(
                "streamnaro://episode/tt1234567/1/1",
                "streamnaro://"
            )
        )
        assertEquals(
            StreamnaroParsing.EpisodeRoute("tt1234567", 0, 9),
            StreamnaroParsing.parseEpisodeRoute(
                "streamnaro://episode/tt1234567/0/9",
                "streamnaro://"
            )
        )
        assertNull(
            StreamnaroParsing.parseEpisodeRoute(
                "streamnaro://episode/tt1234567/-1/1",
                "streamnaro://"
            )
        )
        assertNull(
            StreamnaroParsing.parseEpisodeRoute(
                "streamnaro://episode/tt1234567/1/0",
                "streamnaro://"
            )
        )
        assertNull(
            StreamnaroParsing.parseEpisodeRoute(
                "streamnaro://episode/not-imdb/1/1",
                "streamnaro://"
            )
        )
        assertNull(
            StreamnaroParsing.parseEpisodeRoute(
                "streamnaro://episode/tt1234567/1/1/extra",
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
