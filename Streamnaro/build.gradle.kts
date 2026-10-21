version = 3

cloudstream {
    description = "Streamnaro movies and TV catalog with multi-mirror HLS playback (beta)"
    authors = listOf("revan44555")
    // Beta until HLS playback is verified end-to-end on a real Android device.
    // v3 adds NHD domain fallbacks (nhdapi.com, player.nhdapi.com) and the
    // query-string episode route shape observed on the live site.
    status = 3
    tvTypes = listOf("TvSeries", "Movie")
    iconUrl = "https://raw.githubusercontent.com/revan44555/TestPluginss/master/Streamnaro/branding/streamnaro-icon.png"
    language = "en"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
