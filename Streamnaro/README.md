# Streamnaro Catalog (beta)

This module is an initial metadata/catalog integration for the Streamnaro entry in
this repository.

## Current behavior

- Browse Cinemeta's public movie and series catalogs.
- Search movie and series titles through Cinemeta's catalog interface.
- Load movie metadata and series episode metadata.
- Reject catalog IDs that are not in IMDb ID format.
- Accept only HTTPS URLs for poster artwork.
- Validate internal movie/series routes and the season/episode route format.
- Do not resolve or scrape video-host URLs.

## Playback status

Playback is intentionally **not implemented**. A user-provided Android DevTools
capture showed four HTTP 200 `.m3u8` media requests while a title page was open.
One request came from `nextlevelbrandstudio.site`; the request showed
`https://nextgencloudfabric.com` as its Origin and a response MIME type of
`application/vnd.apple.mpegurl`. The response shown was only 76 bytes.

The public settings page also describes route shapes for its “Direct High Speed
Server” (`/movie/{IMDB_ID}` and `/tv/{IMDB_ID}/{S}/{E}`), but this review did not
verify a stable API base URL, request/response contract, authentication, or
authorization to reuse its media. The long captured URL is not stored because it
may include session-specific access material and is not a stable integration
contract.

To implement playback, first obtain and verify the site's official API
documentation or an authorized integration contract. Then add a dedicated
resolver for that documented API and test it with content the integrator is
authorized to access. Do not hard-code temporary browser URLs or infer endpoint
behavior from a request label alone.

## Validation still required

- Confirm Cinemeta search/catalog response behavior in the target CloudStream
  runtime.
- Test a documented/authorized playback resolver in the target CloudStream
  runtime once its contract is available.
- Do not mark the plugin as production-ready until the playback and rights model
  are verified.
