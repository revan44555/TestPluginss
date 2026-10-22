package com.ninjagoizlesene

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class NinjagoIzleseneProviderTest {
    @Test
    fun episodeOnlyTextKeepsCurrentSeasonAndFallbackNumbersContinueAfterExplicitEpisode() {
        val baseUrl = "https://ninjagoizlesene.com.tr/dizi/ninjago/"
        val doc = Jsoup.parse(
            """
                <h2>2. Sezon</h2>
                <a href="/ninjago/episode-three/">3. Bölüm</a>
                <a href="/ninjago/bonus-episode/">Özel Bölüm</a>
            """.trimIndent(),
            baseUrl
        )

        val episodes = NinjagoIzleseneProvider().extractEpisodes(
            doc = doc,
            pageUrl = baseUrl,
            slug = "ninjago"
        )

        assertEquals(2, episodes.size)
        assertEquals(2, episodes[0].season)
        assertEquals(3, episodes[0].episode)
        assertEquals(2, episodes[1].season)
        assertEquals(4, episodes[1].episode)
    }
}
