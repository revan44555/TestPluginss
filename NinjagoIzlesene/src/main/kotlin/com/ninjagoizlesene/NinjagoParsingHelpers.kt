package com.ninjagoizlesene

import org.jsoup.nodes.Element
import java.net.URI

internal data class NinjagoEpisodePosition(val season: Int, val episode: Int)

internal object NinjagoEpisodeNumbers {
    fun episodeOnlyLabel(currentSeason: Int, episode: Int): NinjagoEpisodePosition =
        NinjagoEpisodePosition(season = currentSeason, episode = episode)

    fun nextFallback(season: Int, counters: MutableMap<Int, Int>): Int {
        val next = (counters[season] ?: 0) + 1
        counters[season] = next
        return next
    }

    fun record(counters: MutableMap<Int, Int>, season: Int, episode: Int) {
        counters[season] = maxOf(counters[season] ?: 0, episode)
    }
}

internal object NinjagoPlayerFrameFilter {
    private val adHosts = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "googletagservices.com",
        "adnxs.com",
        "taboola.com",
        "outbrain.com",
        "exoclick.com",
        "popads.net",
        "propellerads.com",
        "trafficjunky.com",
        "adsterra.com",
        "adskeeper.co.uk"
    )

    fun isCandidate(iframe: Element, baseUrl: String): Boolean {
        val raw = iframe.attr("data-src").trim()
            .takeIf { it.isNotBlank() && !it.equals("about:blank", ignoreCase = true) }
            ?: iframe.attr("src").trim()
                .takeIf { it.isNotBlank() && !it.equals("about:blank", ignoreCase = true) }
            ?: return false
        if (raw.startsWith("javascript:", ignoreCase = true)) return false

        val absolute = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> raw
            raw.startsWith("/") -> baseUrl.trimEnd('/') + raw
            else -> baseUrl.trimEnd('/') + "/" + raw
        }
        val host = runCatching { URI(absolute).host?.lowercase() }.getOrNull() ?: return false
        return adHosts.none { host == it || host.endsWith(".$it") }
    }
}
