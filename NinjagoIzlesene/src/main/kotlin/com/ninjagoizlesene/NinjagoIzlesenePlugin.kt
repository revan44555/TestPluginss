package com.ninjagoizlesene

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NinjagoIzlesenePlugin : Plugin() {
    override fun onLoad() {
        registerMainAPI(NinjagoIzleseneProvider())
    }
}
