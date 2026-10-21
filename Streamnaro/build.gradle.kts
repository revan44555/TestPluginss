version = 10

cloudstream {
    description = "Streamnaro movies and TV catalog with multi-provider HLS playback (beta)"
    authors = listOf("revan44555")
    // Beta until HLS playback is verified end-to-end on a real Android device.
    // v10: speculative playback resolution - the movie detail page dwell
    // absorbs the WebView capture cost, so pressing play serves cached links
    // and starts in seconds instead of ~15-25s.
    status = 3
    tvTypes = listOf("TvSeries", "Movie")
    iconUrl = "https://raw.githubusercontent.com/revan44555/TestPluginss/master/Streamnaro/branding/streamnaro-icon.png"
    language = "en"
}

dependencies {
    // Compile-only: the CloudStream app already ships kotlinx-coroutines at
    // runtime (visible in device log stack traces), but the plugin compile
    // classpath does not expose it. compileOnly lets the code build without
    // bundling a second copy into the plugin package.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
