version = 7

cloudstream {
    description = "Streamnaro movies and TV catalog with multi-provider HLS playback (beta)"
    authors = listOf("revan44555")
    // Beta until HLS playback is verified end-to-end on a real Android device.
    // v7: manifest re-fetch carries the host-scoped session cookie and is
    // retried once (device findings: cookie-less re-fetch can 403; signed
    // tokens can lose the minting race right after capture).
    status = 3
    tvTypes = listOf("TvSeries", "Movie")
    iconUrl = "https://raw.githubusercontent.com/revan44555/TestPluginss/master/Streamnaro/branding/streamnaro-icon.png"
    language = "en"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
