# Streamnaro integration findings

Review date: 2026-10-21

## What the public site currently exposes

The public landing page at https://streamnaro.online/ describes a movies, TV shows,
and anime interface. Its settings text mentions “Live Cinemeta catalog feeds”,
OpenSubtitles v3, and several player/download server labels. The page extracted
in the current review also shows failed search/title loading and a “Playback
failed” state. These observations are from the publicly rendered page and are
not proof that every user session fails.

The page labels include “Stream HD NHD API”, “2Embed Backup Server”, and three
download-server options. Its settings text describes a “Direct High Speed
Server” with route shapes `/movie/{IMDB_ID}` and `/tv/{IMDB_ID}/{S}/{E}`.

There is a public NHD Embed documentation page at
https://nhdapi.st/docs. It documents player/embed URLs
`https://nhdapi.st/movie/{tmdbIdOrImdbId}` and
`https://nhdapi.st/tv/{tmdbIdOrImdbId}/{season}/{episode}`. The documentation
says these player pages do not require an API key; a key is required for its
separate JSON API. These routes match the route *shape* shown in Streamnaro's
“Stream HD NHD API” server label.

The user-provided Network capture shows `nextlevelbrandstudio.site` as the
request host and `https://nextgencloudfabric.com` as its Origin. This does not
prove the upstream host's operator, stability, or content-use rights. The
public NHD documentation does not provide a raw-HLS JSON response schema; the
plugin therefore opens the documented player page and uses CloudStream's
WebView request interception to capture the HLS request made by the player.
It does not assume that the player-page URL itself is a direct media URL.

A label or route template alone is insufficient evidence that a service is
authorized or that every title will be playable.

The public page also includes generic-looking UI text, loading errors, and a
placeholder-like support number. Those are reliability warning signs, not
proof of malicious intent.

## Public domain-reputation warnings

Public automated reputation reports reviewed on 2026-10-21 raised caution flags
for both the site and one host visible in the user's Network capture:

- Gridinsoft's report for `streamnaro.online`, checked 2026-10-17, classified
  the domain as suspicious with a 29/100 trust score and one external-provider
  warning: https://pt.gridinsoft.com/online-virus-scanner/url/streamnaro-online
- ScamAdviser's report for `streamnaro.online` showed a trust score of 0 and
  identified its young domain age and movie-download content as negative
  signals: https://www.scamadviser.com/tc/check-website/streamnaro.online
- Gridinsoft's report for `nextgencloudfabric.com`, checked 2026-07-25,
  classified that domain as suspicious with a 22/100 trust score and three
  external-provider warnings:
  https://gridinsoft.com/online-virus-scanner/url/nextgencloudfabric-com

These scores are automated reputation assessments, not definitive proof of
malware or fraud. They are nevertheless relevant risk signals. Do not send site
credentials, install unknown APKs, hard-code session URLs, or trust a host as a
plugin dependency solely because a browser request returned HTTP 200.

## Browser Network capture (user-provided, 2026-10-21)

A user-provided Android DevTools capture shows four successful HTTP 200 media
requests whose request URLs end in `.m3u8`. One captured request identifies
`nextlevelbrandstudio.site` as the request host, with
`https://nextgencloudfabric.com` shown as its Origin header. The displayed
response has MIME type `application/vnd.apple.mpegurl` and a response size of
76 bytes.

This is evidence that the browser session requested an HLS playlist-like
resource while a title page was open. It does **not** establish that this host
is operated by Streamnaro, that the URL is stable, that the returned playlist
contains playable segments on its own, or that the linked content is licensed
for third-party integration. The captured URL contains a long opaque path; the
full URL is deliberately not stored here because it may be session-specific or
expire and could grant temporary access.

A short HLS playlist may point to another playlist or media segments. Therefore,
a 200 response for a `.m3u8` request is not by itself proof of end-to-end
playability from CloudStream. Do not hard-code a captured browser URL or infer
an API contract from this observation.

## What was implemented

- A separate `Streamnaro` CloudStream module discovered automatically by the
  root `settings.gradle.kts`.
- A provider registration class and plugin metadata.
- Cinemeta-backed metadata search, movie/series metadata, and series episode
  listing, based on Stremio's public protocol documentation:
  https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md
- IMDb ID validation and HTTPS-only poster URL filtering.
- Unit tests for ID/URL validation, catalog pagination URL construction, item
  routes, episode coordinates, and NHD player playback-route construction.
- A CloudStream `WebViewResolver` path that captures the player-generated HLS
  request, then uses `M3u8Helper.generateM3u8()` to parse the manifest and emit
  playable quality links.
- A pull-request build workflow that compiles without publishing artifacts.

During code review, pagination was found to append `skip` as a query parameter
after the `.json` suffix. Stremio's documented protocol represents catalog
extra arguments in the path before `.json`, e.g.
`/catalog/movie/top/skip=100.json`. The provider now uses that route shape and
has a unit test for first and subsequent pages.

The code now implements catalog/metadata plus a **best-effort playback
resolver**. Cinemeta is used for metadata because Streamnaro's page mentions
Cinemeta catalog feeds; playback uses the documented NHD player-page route shape
shown by the “Stream HD NHD API” option. This link extraction has passed CI
compilation and unit tests, but has not yet been verified end-to-end in a real
CloudStream Android session.

## Playback status and next evidence required

Playback now follows this flow:
1. Convert the internal movie/episode route into the documented NHD player page
   (`/movie/{id}` or `/tv/{id}/{season}/{episode}`).
2. Let CloudStream's `WebViewResolver` load the player page and intercept its
   first request matching `.m3u8` or `master.txt`.
3. Pass the captured request to `M3u8Helper.generateM3u8()`, preserving the
   captured Origin, Referer, User-Agent, Accept, and Accept-Language headers
   when present, but not forwarding browser cookies to segment hosts.

This avoids committing a captured, potentially expiring browser URL. The
resolver relies on upstream player behavior and is therefore best-effort. CI
can prove compilation and route construction, not that the player emits the
same request from every device, region, or title. If no manifest is captured,
`loadLinks()` returns false cleanly. The plugin does not bypass DRM.

Before production release, verify playback in CloudStream on an Android device
with content the integrator is authorized to access, and review the upstream
service's terms and content-use rights.

## Limitations

The Streamnaro site is client-rendered and its own public page output did not
expose a stable direct-media endpoint. The implemented resolver instead uses
the public NHD player-page route shape identified by the site's server label.
The exact captured browser URL is not stored because it may be session-specific
or expire. Automated tests/build have passed on earlier commits; the latest
resolver change must pass CI and still requires end-to-end playback validation
in a real CloudStream Android app. CI does not prove runtime playback against
the upstream player or verify the rights model.
