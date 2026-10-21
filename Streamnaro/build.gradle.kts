version = 6

cloudstream {
    description = "Streamnaro movies and TV catalog with multi-provider HLS playback (beta)"
    authors = listOf("revan44555")
    // Beta until HLS playback is verified end-to-end on a real Android device.
    // v6 accepts hls.js-style fragment-marked manifests ("...#file.m3u8")
    // and strips the fragment before playback (bug shipped in v5).
    status = 3
    tvTypes = listOf("TvSeries", "Movie")
    iconUrl = "https://raw.githubusercontent.com/revan44555/TestPluginss/master/Streamnaro/branding/streamnaro-icon.png"
    language = "en"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
