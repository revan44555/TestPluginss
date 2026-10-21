# Streamnaro Catalog (beta)

This module provides Cinemeta-backed catalog/metadata plus a best-effort HLS playback resolver for CloudStream.

## Current behavior

- Browse Cinemeta's public movie and series catalogs.
- Search movie and series titles through Cinemeta's catalog interface.
- Load movie metadata and series episode metadata.
- Validate IMDb IDs, HTTPS poster URLs, internal routes, and episode coordinates.
- Use Stremio's documented path format for Cinemeta catalog pagination.
- Route movie/episode playback through the documented NHD player pages.
- Use CloudStream's `WebViewResolver` to capture the HLS manifest requested by the player.
- Use CloudStream's `M3u8Helper.generateM3u8()` to parse the manifest and expose available quality variants.
- Build the player URL per item at playback time; do not hard-code a temporary browser-captured CDN URL.
- Do not forward browser session cookies to HLS segment hosts.

## Playback implementation

The public NHD Embed documentation at https://nhdapi.st/docs documents these player-page routes:

- Movie: `https://nhdapi.st/movie/{tmdbIdOrImdbId}`
- TV episode: `https://nhdapi.st/tv/{tmdbIdOrImdbId}/{season}/{episode}`

The plugin opens the relevant player page inside CloudStream's WebView resolver, clicks a visible play control where available, and captures the first request whose URL matches an HLS manifest pattern (`.m3u8` or `master.txt`). The captured request's Origin, Referer, User-Agent, Accept, and Accept-Language headers are passed to M3U8 parsing when present. `M3u8Helper` then parses the playlist and returns playable HLS links.

The NHD documentation describes these as embed/player pages; it does not publish a raw-HLS JSON schema. This implementation uses the player itself and dynamically intercepts its HLS request, rather than assuming an undocumented JSON endpoint. The embed pages do not require an API key according to the published documentation; the separate JSON API does require a key.

## Important limitations

- HLS interception depends on the upstream player UI and its network behavior. If that player changes, the extraction logic may need maintenance.
- The code compiles in CI and tests URL routing, but automated build results do not prove playback on a physical Android device.
- A captured HTTP 200 response for a `.m3u8` URL does not guarantee that all playlists, segments, and request headers will work end-to-end.
- Confirm the upstream service's terms and content-use rights before treating this integration as production-ready.
- This plugin does not bypass DRM or hard-code expiring session URLs.

## Validation

Run the automated checks through the repository's Pull Request Build workflow:

- `gradle :Streamnaro:testDebugUnitTest --stacktrace`
- `gradle make makePluginsJson --stacktrace`

Test at least one movie and one TV episode in the actual CloudStream Android app before releasing the plugin outside this beta branch.
