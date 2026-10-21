package com.filmmakinesi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmmakinesiPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilmmakinesiProvider())
    }
}
