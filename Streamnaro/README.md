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
- Use Stremio's documented path format for Cinemeta pagination.
- Do not resolve or scrape video-host URLs.

## Playback status

Playback is intentionally **not implemented**. A user-provided Android DevTools
capture showed four HTTP 200 `.m3u8` media requests while a title page was open.
One request came from `nextlevelbrandstudio.site`; the request showed
`https://nextgencloudfabric.com` as its Origin and a response MIME type of
`application/vnd.apple.mpegurl`. The response shown was only 76 bytes.

The Streamnaro page also uses the label “Stream HD NHD API” and displays
movie/TV route shapes similar to the public NHD Embed docs
(https://nhdapi.com/docs). Those docs describe iframe/player endpoints, while
the public documentation reviewed here does not specify a raw-HLS JSON response
contract for CloudStream. The observed browser request was from a different
host, so its selected source is not yet identified.

Public automated reputation reports also raise caution flags for Streamnaro and
the host shown in that browser capture. Such assessments are not proof of
malicious activity, but they reinforce that session URLs should not be trusted
or hard-coded without independent verification.

To implement playback, first obtain and verify the official API contract or an
authorized integration example that identifies a stable resolver, response
schema, authentication/expiry behavior, and the right to use the source. Then
add a dedicated resolver and tests using content the integrator is authorized
to access. Do not hard-code temporary browser URLs or infer endpoint behavior
from a request label alone.

## Validation still required

- Confirm Cinemeta search/catalog response behavior in the target CloudStream
  runtime.
- Test a documented/authorized playback resolver in the target CloudStream
  runtime once its contract is available.
- Do not mark the plugin as production-ready until playback and content rights
  are verified.
