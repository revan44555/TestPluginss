version = 2

cloudstream {
    description = "Streamnaro catalog with HLS playback resolver (beta)"
    authors = listOf("revan44555")
    // Beta until HLS playback is verified in the Android app and source-use terms are reviewed.
    status = 3
    tvTypes = listOf("TvSeries", "Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=streamnaro.online&sz=%size%"
    language = "en"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
