# Streamnaro for CloudStream

A CloudStream provider for movie and TV metadata, with best-effort playback through the publicly documented NHD embed player.

## What it supports

- Popular movie and TV catalogs from Cinemeta.
- Search across movie and TV catalogs.
- Metadata pages and TV episode lists.
- IMDb route validation, safe HTTPS poster URLs, and tested catalog pagination.
- Playback through the documented NHD movie/TV pages, using CloudStream's WebView resolver to detect HLS requests.
- Defensive HLS URL validation before handing a manifest to CloudStream's HLS parser.

## How it works

1. Cinemeta supplies catalog and metadata results.
2. Streamnaro builds an internal route for each movie or series episode.
3. For playback, the route is converted to the documented NHD embed page:
   - Movie: `https://nhdapi.st/movie/{tmdbIdOrImdbId}`
   - TV episode: `https://nhdapi.st/tv/{tmdbIdOrImdbId}/{season}/{episode}`
4. CloudStream's WebView resolver observes the player-generated HLS request; `M3u8Helper` expands a valid manifest into playable quality links.

The provider does not treat the embed-page URL as if it were a direct video file, and it does not store temporary, session-specific media URLs.

## Known limitations

- This is **not** a full replica of Streamnaro's website UI or backend. The provider currently uses Cinemeta for catalog and metadata, not Streamnaro's own search/filter APIs.
- Anime, profiles, watchlists, site-specific filters, downloads, and a 2Embed fallback are not implemented in this module.
- HLS playback depends on the upstream player and its request behavior. The resolver may fail if the player, source, session, or network requirements change.
- Capturing an HLS URL is not proof that all child playlists and media segments are accessible.
- Subtitle tracks controlled inside the embed player do not automatically transfer to CloudStream when the HLS link is extracted.
- This provider declares no Chromecast support because upstream HLS playback may depend on request headers.
- Automated builds test compilation and pure parsing logic; they do not prove playback on an Android device.

## Validation

Run:

```bash
gradle :Streamnaro:testDebugUnitTest --stacktrace
gradle make makePluginsJson --stacktrace
```

Before release, manually test at least one authorized movie and one TV episode in the CloudStream Android app, check subtitles and segment requests, and review the terms/rights for the upstream services. The provider does not bypass DRM.
