package com.ninjagoizlesene

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NinjagoIzlesenePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NinjagoIzleseneProvider())
    }
}
