package com.streamnaro

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class StreamnaroPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(StreamnaroProvider())
    }
}
