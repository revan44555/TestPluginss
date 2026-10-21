# Streamnaro Catalog (beta)

This module is an initial metadata/catalog integration for the Streamnaro entry in
this repository.

## Current behavior

- Browse Cinemeta's public movie and series catalogs.
- Search movie and series titles through Cinemeta's catalog interface.
- Load movie metadata and series episode metadata.
- Reject catalog IDs that are not in IMDb ID format.
- Accept only HTTPS URLs for poster artwork.
- Do not resolve or scrape video-host URLs.

## Playback status

Playback is intentionally **not implemented**. Streamnaro's public UI mentions
Cinemeta catalog feeds and several server labels, but this review did not verify
an official, documented playback endpoint, stream format, or authorization
model. The visible labels are not enough to establish where video bytes originate.
The provider therefore returns no playback link rather than guessing endpoints
or treating arbitrary page URLs as media files.

To implement playback, first obtain and verify the site's official API
documentation or an authorized integration contract. Then add a dedicated
resolver for that documented API and test it with content the integrator is
authorized to access.

## Validation still required

- Build this module against the repository's current CloudStream dependencies.
- Confirm Cinemeta search/catalog response behavior in the target CloudStream
  runtime.
- Add fixture-based tests for catalog parsing and episode IDs.
- Do not mark the plugin as production-ready until the playback and rights model
  are verified.
