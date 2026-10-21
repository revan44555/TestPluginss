version = 5

cloudstream {
    description = "Streamnaro movies and TV catalog with multi-provider HLS playback (beta)"
    authors = listOf("revan44555")
    // Beta until HLS playback is verified end-to-end on a real Android device.
    // v5 fixes template validation: placeholders are substituted before the
    // HTTPS check, because java.net.URI rejects braces in URLs (bug shipped in v4).
    status = 3
    tvTypes = listOf("TvSeries", "Movie")
    iconUrl = "https://raw.githubusercontent.com/revan44555/TestPluginss/master/Streamnaro/branding/streamnaro-icon.png"
    language = "en"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
