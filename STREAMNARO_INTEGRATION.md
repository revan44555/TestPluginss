# Streamnaro integration findings

Review date: 2026-10-21

## What the public site currently exposes

The public landing page at https://streamnaro.online/ describes a movies, TV shows,
and anime interface. Its settings text mentions “Live Cinemeta catalog feeds”,
OpenSubtitles v3, and several player/download server labels. The rendered page
also contains “Failed to load additional search results” and “Playback failed”
states. These are observations from the publicly rendered page, not proof that
all browser sessions fail.

The page labels include “Stream HD NHD API”, “2Embed Backup Server”, and three
download-server options. The visible labels do not disclose a verified base URL,
request schema, authentication method, content authorization model, or stable
media response format. A label is not sufficient evidence that a service is a
working API or that its streams are authorized.

The page also says that it does not host content and tells visitors to use
streams and downloads they are authorized to access. This repository does not
assume that linked content has distribution rights.

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

## What was implemented in the initial PR

- A separate `Streamnaro` CloudStream module discovered automatically by the
  root `settings.gradle.kts`.
- A provider registration class and plugin metadata.
- Cinemeta-backed metadata search, movie/series metadata, and series episode
  listing, based on the public interface described by the Stremio project:
  https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/advanced.md
- IMDb ID validation and HTTPS-only poster URL filtering.
- A pull-request build workflow that compiles without publishing artifacts.

This is a **metadata/catalog prototype**, not yet a working video-source
integration. It uses Cinemeta for metadata because Streamnaro itself mentions
Cinemeta catalog feeds, but this does not demonstrate that Streamnaro's own
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
been built or run in a local Android/CloudStream runtime in this session.
The new PR workflow is intended to supply the build check when GitHub runs it.
