# Low-bandwidth fork profile

This document defines the behavior that intentionally differs from upstream
ImmichFrame v0.5.0. If another document describes video playback,
original-file caching, query-string API keys, or upstream self-update, this
profile takes precedence for this fork.

## Purpose

The fork targets a dedicated Android photo frame on a metered mobile-data
connection, initially a Lenovo Tab M10 FHD Plus TB-X606X. Its priorities are
predictable data use, offline display, safe retry, and unattended operation.

## Invariants

- Only `IMAGE` assets are synchronized or displayed. Video caching and video
  playback are disabled. Animated GIFs use Immich's static preview.
- Persistent media uses
  `GET /api/assets/{id}/thumbnail?size=preview`; `/original` is never used by
  the cache worker.
- The API key is sent in the `x-api-key` header only to the configured Immich
  origin and `/api/assets/` path. It is not placed in a media URL. Clients
  carrying an API key do not follow HTTP redirects.
- A preview download is rejected if it redirects, is not an image, or exceeds
  5 MiB. It is written to a temporary file and atomically replaces the old
  preview only after validation.
- Unchanged assets are recognized by Immich `lastModified` and are not
  downloaded again.
- Search metadata is fetched through every page. Asset IDs are deduplicated
  across pages and selected albums.
- One physical cached file may belong to multiple albums. Removing one album
  membership cannot delete a file still used by another album.
- The default synchronization interval is 360 minutes with a 60-minute
  minimum. Reopening a slideshow only enqueues synchronization when its last
  successful sync is stale.
- Network and download failures preserve the existing cache and ask
  WorkManager to retry. A temporary empty search response does not purge
  cached images.
- Upstream self-update is disabled and this fork has a distinct application
  ID: `com.familyphotoframe.immichframe.lowbandwidth`.

## Immich API-key permissions

The generated or manually created key requires four permissions:

- `album.read`
- `asset.read`
- `asset.view`
- `user.read`

`asset.download` is deliberately not requested because originals are outside
this fork's data budget.

## Cache schema

Room schema version 3 separates physical assets from album membership:

- `cached_assets`: one row per Immich asset and local preview file
- `album_asset_cross_refs`: composite key `(album_id, asset_id)`
- `album_sync_states`: the last successful sync and asset count per album

An empty metadata search never by itself permits cache removal. The worker must
also see `assetCount = 0` for the same album in Immich album metadata. When all
selected albums are confirmed empty, the current on-screen preview is retained
as a local fallback until new media is synchronized.

Migration 2→3 preserves existing cached asset rows and their album
memberships. Legacy cached videos are detached and removed when no album uses
them.

## Update and release policy

The application cannot download upstream releases. Before distributing a
release build, configure a fork-owned repository and signing key, document the
upgrade path, and then explicitly enable self-update. Debug APKs use the
fork's separate debug application ID and the repository's shared debug key.

## Verification baseline

Automated tests cover complete pagination, duplicate-ID handling, scoped
media authentication, redirect and size rejection, atomic preview retention,
unchanged-image download suppression, image-only selection, schema migration,
sync throttling, and shared multi-album cache ownership. Device acceptance
testing must still verify image quality, offline reboot, server outage
recovery, data usage, and background operation on the TB-X606X.
