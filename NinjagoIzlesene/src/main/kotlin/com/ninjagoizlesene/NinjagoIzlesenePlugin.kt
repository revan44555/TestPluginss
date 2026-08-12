package com.ninjagoizlesene

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class NinjagoIzlesenePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(NinjagoIzleseneProvider())
    }
}
