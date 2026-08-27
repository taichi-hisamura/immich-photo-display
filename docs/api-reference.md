# Immich API Reference

> **Normative fork override:** [Low-bandwidth fork profile](low-bandwidth-profile.md).
> The fork uses image previews and `x-api-key` header authentication; it does
> not call the original-asset endpoint during synchronization or playback.

Base URL: user-provided (e.g. `https://photos.example.com:2283` or `http://192.168.1.100:2283`)

All endpoints are under `/api` prefix.

## Authentication

Send the API key as an HTTP header:

```
x-api-key: ***
```

The app uses this header for Retrofit calls, permission probes, persistent
preview downloads, and Coil image requests. The API key is never appended to
a media URL. Coil adds the header only for the configured Immich origin and
`/api/assets/` path.

## Media URL Routing

| Asset type | Endpoint | Notes |
|---|---|---|
| Standard image | `GET /api/assets/{id}/thumbnail?size=preview` | Display and persistent offline cache. Auth: `x-api-key` header. |
| Animated GIF | `GET /api/assets/{id}/thumbnail?size=preview` | Static preview only; the original animation is not downloaded. |
| Video | Not requested | Metadata is filtered before cache/display and `videoUrl()` fails closed. |
| Thumbnail (media-selection grid) | `GET /api/assets/{id}/thumbnail?size=thumbnail` | Auth: scoped `x-api-key` header. |

API keys can be created in Immich under User Settings > API Keys, and can be
scoped to specific permissions.

**In-app key generation**: during setup, the default is to paste an existing
key manually. A **Generate Key** helper button lets the user auto-create a
scoped key via email/password (or OAuth) — the app logs in, creates the key,
and discards the password. See F1 in the functional spec for details.

### Required Permissions

The API key needs 4 scoped permissions:

| Permission | Used for |
|---|---|
| `album.read` | List albums, get album info |
| `asset.read` | Search/list assets in albums (POST /search/metadata) |
| `asset.view` | View thumbnails and previews |
| `user.read` | Validate API key (GET /users/me) |

These permissions are the minimum required for ImmichFrame to function.
The in-app key generator creates a key with exactly these permissions
(requires Immich v1.135+ for scoped keys). The external `keymgr` scripts
are still available as a fallback.

During media synchronization, `GET /api/albums` is also used to confirm an
otherwise empty `POST /search/metadata` response. Cache removal for an empty
album is allowed only when the matching album reports `assetCount = 0`.

### Permission Verification

After the API key is stored (whether generated in-app or pasted manually),
the app probes each required endpoint to verify the key actually has the
necessary scopes. This mirrors the external `scripts/check-api-key.sh`
script. Probes run in dependency order:

| Step | Endpoint | Permission Tested | Auth method | Notes |
|---|---|---|---|---|
| 1 | `GET /api/users/me` | `user.read` | `x-api-key` header | Also validates the key itself |
| 2 | `GET /api/albums` | `album.read` | `x-api-key` header | Returns album list |
| 3 | `POST /api/search/metadata` | `asset.read` | `x-api-key` header | Returns first asset ID for downstream probes |
| 4 | `GET /api/assets/{id}/thumbnail?size=preview` | `asset.view` | `x-api-key` header | Same authentication mechanism as downloader and Coil |

If an upstream probe fails, downstream probes are skipped and marked
"unknown" (not "denied"). Results are stored as `permission_status` (JSON)
in DataStore and refreshed every time the Settings screen opens or the
user taps "Re-check".

## In-App Auth Endpoints

These endpoints are used during setup by a separate Retrofit instance
(`ImmichAuthApi`) that does NOT use the `x-api-key` header. Login and
key-creation calls use `Bearer` tokens passed per-call via the
`Authorization` header.

### Server Probing (no auth)

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/server/version` | Detect Immich version (scoped-key support check) |
| GET | `/server/features` | Detect available auth methods (passwordLogin, oauth) |

### Password Login (no auth)

| Method | Endpoint | Request | Response |
|---|---|---|---|
| POST | `/auth/login` | `{ email, password }` | `{ accessToken, userId, userEmail, ... }` |

### API Key Management (Bearer token)

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api-keys` | List existing keys (metadata only, no secrets) |
| POST | `/api-keys` | Create a new key: `{ name: "ImmichMediaFrame", permissions: [...] }` → returns `{ secret }` |
| PUT | `/api-keys/{id}` | Update key name/permissions (metadata only) |
| POST | `/auth/logout` | Invalidate the bearer session after key creation so the device doesn't linger in "Authorized Devices". The API key survives logout. |

### OAuth PKCE (no auth)

| Method | Endpoint | Purpose |
|---|---|---|
| POST | `/oauth/authorize` | `{ redirectUri, codeChallenge, state }` → `{ url }` (open in browser) |
| POST | `/oauth/callback` | `{ url, codeVerifier, state }` → `{ accessToken, ... }` (same as login) |

The OAuth flow uses PKCE: the app generates a `code_verifier` + `code_challenge`
+ `state` locally (`PkceHelper.kt`), opens the authorization URL in a Custom
Tab, and receives the callback via the `com.dav3.immichframe://oauth-callback`
deep link.

## API Key Management Tools

ImmichFrame provides several cross-platform tools for generating and validating API keys:

| Tool | Platform | Description |
|---|---|---|
| `keymgr` | macOS, Linux, Windows (compiled binary) | Unified CLI: `keymgr generate <url> <email>` and `keymgr check <url> <key>` |
| `keymgr.exe` | Windows | Pre-compiled Windows binary |
| `generate-api-key.sh` / `check-api-key.sh` | macOS, Linux | Bash scripts using `curl` |
| `generate-api-key.ps1` / `check-api-key.ps1` | Windows (PowerShell 5.1+) | Native PowerShell scripts using `Invoke-RestMethod` |

### Quick Start (macOS/Linux)

```bash
# Download the release assets from GitHub Releases
chmod +x keymgr
./keymgr generate https://photos.example.com user@example.com
# Enter password when prompted
# Copy the output key into ImmichFrame Settings > API Key
./keymgr check https://photos.example.com <your-api-key>
```

### Quick Start (Windows PowerShell)

```powershell
# Download the release assets from GitHub Releases
.\keymgr.exe generate https://photos.example.com user@example.com
# Enter password when prompted
.\keymgr.exe check https://photos.example.com <your-api-key>
```

Or use the native PowerShell scripts directly:
```powershell
.\generate-api-key.ps1 https://photos.example.com user@example.com
.\check-api-key.ps1 https://photos.example.com <your-api-key>
```

> **Note**: All tools require Immich v1.135+ for scoped API key support. The server version is checked automatically.
### 1. Health Check

```
GET /api/server/ping
```

No auth required. Returns:

```json
{ "resp": "pong" }
```

Used during setup to verify the server is reachable.

---

### 2. Validate API Key / Get Current User

```
GET /api/users/me
```

Auth: `x-api-key`

Returns user info including email, name, ID. Used during setup to verify
the API key is valid.

Response (abbreviated):

```json
{
  "id": "uuid",
  "email": "user@example.com",
  "name": "User Name",
  ...
}
```

---

### 3. List Albums

```
GET /api/albums
```

Auth: `x-api-key`

Returns array of albums:

```json
[
  {
    "id": "album-uuid",
    "albumName": "Vacation 2024",
    "assetCount": 142,
    "albumThumbnailAssetId": "asset-uuid"
  }
]
```

The app extracts `id`, `albumName`, `assetCount`, and `albumThumbnailAssetId`.

---

### 4. Search Assets by Album (Metadata Search)

```
POST /api/search/metadata
```

Auth: `x-api-key`

> **Note**: The app previously used `GET /api/albums/{id}` to fetch album
> assets, but switched to the search endpoint for reliability with Immich v3.
> The album details endpoint returns assets inconsistently across versions.

Request body:

```json
{
  "albumIds": ["album-uuid"],
  "size": 1000
}
```

Response:

```json
{
  "albums": { "total": 1, "count": 1, "items": [...] },
  "assets": {
    "total": 142,
    "count": 142,
    "items": [
      { "id": "asset-uuid", "type": "IMAGE" }
    ]
  }
}
```

The app extracts `id` and `type` from each asset in `assets.items`. Assets
with `type == "VIDEO"` are discarded by the low-bandwidth sync/display policy.

DTOs are in `Dtos.kt` (`SearchMetadataRequest`, `SearchMetadataResponse`,
`SearchAssetsDto`, `AssetDto`).

---

### 5. Download / View Asset

```
GET /api/assets/{id}/thumbnail?size=preview
```

Auth: `x-api-key` header.

Returns a web-friendly preview image. The `size` parameter accepts:
- `thumbnail` — small (~100px), used for album picker thumbnails
- `preview` — medium/large (~1440p), used for slideshow display

The app requests `size=preview` for all slideshow images to optimize
bandwidth and disk cache usage.

URL construction is in `ImmichRepositoryImpl.kt`:
- `imageUrl(assetId)` → `{base}/api/assets/{id}/thumbnail?size=preview`
- `thumbnailUrl(assetId)` → `{base}/api/assets/{id}/thumbnail?size=thumbnail`
- `videoUrl(assetId)` → throws `UnsupportedOperationException`

## Rate Limiting

Immich does not impose explicit rate limits on API requests. However, the
slideshow prefetcher should be reasonable — prefetching 3 images ahead
at 30s intervals is well within normal usage.

## API Versioning

Immich follows semantic versioning. The API endpoints listed above are
marked as `Stable` in the Immich API docs, meaning they should not break
across minor version updates.

If Immich changes their API, the app will surface HTTP errors (404, 400)
rather than crash. Version compatibility can be checked via
`GET /api/server-info/version` if needed in the future.

## GitHub API (Self-Update)

The self-update feature uses the GitHub API (not Immich). The update path
depends on build type:

**Release builds** (primary auto-update target):
```
GET https://api.github.com/repos/dave-palt/immich-photo-frame/releases/latest
```
Returns the latest non-prerelease (tag format `vX.Y.Z`). The app compares
the tag against `BuildConfig.VERSION_NAME` using semantic version comparison.
If newer, the APK asset is downloaded and the system installer invoked.

**Debug builds** (dev channel):
```
GET https://api.github.com/repos/dave-palt/immich-photo-frame/releases?per_page=30
```
Lists recent releases (including prereleases). The newest `dev-{sha}` tag is
selected and its SHA compared against `BuildConfig.GIT_SHA`. If different,
the APK is downloaded and installed.

No authentication required (public repo) for either path. Self-update is
disabled entirely for Play Store installs — those receive updates via the
Play Store.

Implemented in `GitHubApi.kt` / `GitHubDtos.kt` / `UpdateManager.kt`.
