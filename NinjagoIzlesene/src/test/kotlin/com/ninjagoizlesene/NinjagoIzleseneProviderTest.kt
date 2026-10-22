package com.ninjagoizlesene

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NinjagoIzleseneProviderTest {
    @Test
    fun episodeOnlyLabelsUseCurrentSeasonAndFallbackNumbersContinueAfterExplicitEpisode() {
        val currentSeason = 2
        val explicit = NinjagoEpisodeNumbers.episodeOnlyLabel(currentSeason, 3)
        val counters = mutableMapOf<Int, Int>()

        assertEquals(2, explicit.season)
        assertEquals(3, explicit.episode)

        NinjagoEpisodeNumbers.record(counters, explicit.season, explicit.episode)
        assertEquals(4, NinjagoEpisodeNumbers.nextFallback(currentSeason, counters))
    }

    @Test
    fun adAndPlaceholderIframesAreNotTreatedAsPlayableContent() {
        val blank = Jsoup.parseBodyFragment("""<iframe src="about:blank"></iframe>""")
            .selectFirst("iframe")!!
        val ad = Jsoup.parseBodyFragment("""<iframe src="https://securepubads.g.doubleclick.net/tag"></iframe>""")
            .selectFirst("iframe")!!
        val player = Jsoup.parseBodyFragment("""<iframe src="https://dzen.ru/embed/abc123"></iframe>""")
            .selectFirst("iframe")!!

        assertFalse(NinjagoPlayerFrameFilter.isCandidate(blank, "https://ninjagoizlesene.com.tr"))
        assertFalse(NinjagoPlayerFrameFilter.isCandidate(ad, "https://ninjagoizlesene.com.tr"))
        assertTrue(NinjagoPlayerFrameFilter.isCandidate(player, "https://ninjagoizlesene.com.tr"))
    }
}
