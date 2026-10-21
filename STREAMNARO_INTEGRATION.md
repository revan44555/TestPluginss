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
These labels and route shapes do not identify a verified API base URL or establish
request parameters, authentication, response schema, content authorization, or
a stable media response. A label or route template alone is insufficient
evidence that a service is a working API or that its streams are authorized.

The page also includes generic-looking UI text and a placeholder-like support
number. Those are reliability warning signs, not proof of malicious intent.

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
credentials, install unknown APKs, hard-code session URLs, or trust this host as
a plugin dependency solely because a browser request returned HTTP 200.

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
  routes, episode coordinates, and episode playback-route validation.
- A pull-request build workflow that compiles without publishing artifacts.

During the code review, pagination was found to append `skip` as a query
parameter after the `.json` suffix. Stremio's documented protocol represents
catalog extra arguments in the path before `.json`, e.g.
`/catalog/movie/top/skip=100.json`. The provider now uses that route shape and
has a unit test for first and subsequent pages.

This is a **metadata/catalog prototype**, not yet a working video-source
integration. It uses Cinemeta for metadata because Streamnaro's page mentions
Cinemeta catalog feeds, but that does not demonstrate that Streamnaro's own
player uses the same endpoints.

## Playback status and next evidence required

Playback intentionally returns no links. Before implementing it, obtain and
verify an official API contract or an authorized integration example that
identifies:
- a stable endpoint and request parameters for a selected movie/episode;
- authentication, origin/referer requirements, and expiry behavior, if any;
- the response schema and supported playback formats;
- confirmation that the source may be used by this plugin.

Do not infer API endpoints solely from UI labels or try guessed endpoints as a
substitute for documentation. Once an authorized interface is established, add
a dedicated resolver, expiry/error handling, and fixture-based tests. Test with
content that the integrator is authorized to access.

## Limitations

The site is client-rendered and the public page output did not expose a stable
catalog or playback endpoint during this review. The browser capture narrows
the technical observation to HLS playlist requests, but does not resolve the
authorization or integration-contract questions. This implementation has not
been installed or tested in a real CloudStream Android app during this review.
GitHub Actions validates the automated unit tests and plugin build, not runtime
playback against Streamnaro.
