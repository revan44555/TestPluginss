package com.filmmakinesi

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmmakinesiPlugin : Plugin() {
    override fun onLoad() {
        registerMainAPI(FilmmakinesiProvider())
    }
}
