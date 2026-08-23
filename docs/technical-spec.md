# Technical Specification

> **Normative fork override:** [Low-bandwidth fork profile](low-bandwidth-profile.md).
> Sections retained from upstream may describe functionality intentionally
> disabled by this fork.

## Tech Stack

| Layer | Technology | Version (approx) |
|---|---|---|
| Language | Kotlin | 2.1.0 |
| UI Framework | Jetpack Compose | BOM 2024.12+ |
| Min SDK | API 26 (Android 8.0) | ~95% device coverage |
| Target SDK | API 35 (Android 15) | Latest stable |
| HTTP Client | Retrofit 3 + OkHttp | 3.0 / 5.4 |
| JSON Parsing | Kotlinx Serialization | 1.7+ |
| Image Loading | Coil 3 (Compose), static previews only | 3.5 |
| Video Playback | Disabled by the low-bandwidth profile | N/A |
| Color Extraction | AndroidX Palette | 1.0+ |
| Animation | Compose Animation Core | (BOM) |
| Local Storage | DataStore (Preferences) | 1.1+ |
| Media Cache DB | Room | 2.8.4 |
| Background Sync | WorkManager | 2.9.1 |
| Credential Storage | EncryptedSharedPreferences (Tink) | 1.1+ |
| Biometric Auth | AndroidX Biometric | 1.1.0 |
| OAuth Browser | AndroidX Browser (Custom Tabs) | 1.8.0 |
| Screenshot Testing | Roborazzi + ComposablePreviewScanner | 1.70.0 / 0.9.1 |
| Dependency Injection | Hilt | 2.52+ |
| Worker Injection | Hilt-Work | 1.4.0 |
| Code Formatting | Spotless + ktlint | 7.0.2 / 1.4.1 |
| Build System | Gradle Kotlin DSL | 8.10.2 (AGP 8.7.3) |
| JDK | OpenJDK 17 | Required for builds |

### Suppressed ktlint rules

- `ktlint_standard_no-wildcard-imports` — standard Compose convention
- `ktlint_standard_function-naming` — Compose `@Composable` functions use
  PascalCase, which violates the default rule

## Architecture Layers

```
immich-android/
├── app/
│   ├── src/main/java/com/dav3/immichframe/
│   │   ├── data/
│   │   │   ├── remote/          # Retrofit API interfaces, DTOs
│   │   │   │   ├── ImmichApi.kt       # Immich endpoints
│   │   │   │   ├── GitHubApi.kt       # GitHub releases API (self-update)
│   │   │   │   ├── ImmichAuthApi.kt   # Auth/login/key/OAuth endpoints (no x-api-key)
│   │   │   │   ├── PkceHelper.kt       # PKCE code verifier/challenge
│   │   │   │   ├── Dtos.kt            # Immich DTOs
│   │   │   │   ├── GitHubDtos.kt      # GitHub DTOs
│   │   │   │   └── ImmichRepositoryImpl.kt
│   │   │   ├── local/           # DataStore, EncryptedPrefs, Room cache
│   │   │   │   ├── DataStoreProvider.kt  # Shared DataStore singleton
│   │   │   │   ├── SettingsRepositoryImpl.kt
│   │   │   │   ├── MediaCacheDatabase.kt # Room DB (cached_assets, album_sync_states)
│   │   │   │   ├── MediaCacheDao.kt      # Room DAOs
│   │   │   │   ├── MediaCacheEntities.kt # Room entities
│   │   │   │   ├── MediaCacheRepositoryImpl.kt
│   │   │   │   └── Converters.kt         # Room type converters
│   │   │   ├── sync/            # WorkManager background sync
│   │   │   │   ├── MediaCacheWorker.kt   # Downloads + reconciles album assets
│   │   │   │   └── SyncScheduler.kt      # Periodic/one-time sync scheduling
│   │   │   ├── update/          # Self-update logic
│   │   │   │   └── UpdateManager.kt
│   │   │   └── (repository/ is in di/)
│   │   ├── domain/
│   │   │   ├── model/           # Domain models (Album, Asset, Settings)
│   │   │   │   ├── Models.kt            # Album, Asset, SlideshowSettings, SyncProgress
│   │   │   │   └── RequiredPermission.kt # Permission registry + PermissionCheckResult
│   │   │   ├── repository/      # Repository interfaces
│   │   │   ├── system/          # AutostartPermissions.kt, LauncherHelper.kt, BiometricHelper.kt
│   │   │   └── sync/            # MediaCacheWorker, SyncScheduler
│   │   ├── di/                  # Hilt modules
│   │   ├── ui/
│   │   │   ├── setup/           # Setup screen (domain validation → key generation/manual/OAuth)
│   │   │   ├── albums/          # Album picker
│   │   │   ├── slideshow/       # Slideshow player (images, video, clock)
│   │   │   ├── media/           # Media selection grid (biometric-gated)
│   │   │   ├── settings/        # Settings screen
│   │   │   │   └── update/          # Update ViewModel (dialog is in slideshow)
│   │   │   ├── components/      # Reusable composables (BiometricLauncher)
│   │   │   ├── onboarding/      # Coachmark tour system (TourStep, TourState, CoachmarkOverlay)
│   │   │   ├── nav/             # Navigation graph
│   │   │   └── theme/           # Material 3 theme
│   │   ├── BootReceiver.kt      # BOOT_COMPLETED → launch slideshow (guards startActivity with SYSTEM_ALERT_WINDOW check)
│   │   ├── ImmichFrameApp.kt    # Application class (@HiltAndroidApp)
│   │   └── MainActivity.kt      # Single activity (also target of LauncherAlias)
│   ├── src/main/res/
│   │   ├── drawable/app_logo.xml             # In-app logo (no bg fill): frame + sun + mountain
│   │   ├── drawable/ic_launcher_foreground.xml  # Launcher foreground (day: white bg + icon at 75%)
│   │   ├── drawable/ic_launcher_monochrome.xml  # Android 13+ themed icon silhouette
│   │   ├── drawable-night/ic_launcher_foreground.xml  # Dark variant (gradient bg #1A1A2E→#16213E)
│   │   ├── values/colors.xml          # ic_launcher_background = #FFFFFF (day)
│   │   ├── values-night/colors.xml    # ic_launcher_background = #1A1A2E (night)
│   │   ├── mipmap-anydpi-v26/ic_launcher.xml   # Adaptive icon (background + foreground + monochrome)
│   │   ├── mipmap-anydpi-v26/ic_launcher_round.xml
│   │   └── xml/file_paths.xml   # FileProvider config for APK install
│   ├── src/debug/res/
│   │   ├── drawable/ic_launcher_foreground.xml  # Debug variant (amber bg #FFB400, navy replaces orange)
│   │   └── values/colors.xml                    # ic_launcher_background = #FFB400 (debug)
│   ├── src/main/assets/demo/     # Sample photos (Picsum) for @Preview screenshot tests
│   └── build.gradle.kts          # Includes roborazzi {} block for Compose Preview screenshot gen
├── docs/                        # This documentation
│   └── screenshots/             # Generated screenshots (from recordRoborazziDebug)
├── .github/workflows/           # dev-build.yml, prod-build.yml
├── build.gradle.kts             # Root build file
├── settings.gradle.kts
└── gradle/libs.versions.toml    # Version catalog
```

## Data Flow

```
UI (Compose) → ViewModel → Repository → Retrofit → Immich API
                              ↕
                           DataStore (settings, credentials, album selection)
```

- **ViewModel** holds UI state as `StateFlow`, survives config changes.
- **Repository** abstracts data sources (remote API + local storage).
- **Coil** displays local preview files and fetches preview/thumbnail URLs on
  cache misses. GIF originals are not decoded; Immich's static preview is used.
- Video playback code inherited from upstream is unreachable: settings force
  Skip Videos on, metadata sync filters `VIDEO`, and `videoUrl()` fails closed.
- **Palette API** extracts dominant colors from each image's top/bottom and
  left/right halves for adaptive background (per-edge letterbox gradient fill).
- **Retrofit OkHttp interceptor** injects the `x-api-key` header on every
  Immich API call automatically.
- **Night Mode** dims the screen during configured hours via per-window
  brightness (`WindowManager.LayoutParams.screenBrightness`). A
  `LaunchedEffect` in `SlideshowScreen` re-evaluates the current time every 5
  seconds and sets `screenBrightness` to the configured percentage when inside
  the night window, or `BRIGHTNESS_OVERRIDE_NONE` (defer to system) outside it.
  While active, the slideshow is fully hidden behind a black overlay, the
  adaptive background is forced to pure black, and the auto-advance timer is
  paused (no photo/video fetching or rendering). The screen is never turned off
  at the hardware level — this is a brightness-based fallback for devices
  without built-in scheduled power on/off.

## Package Naming

Application ID: `com.familyphotoframe.immichframe.lowbandwidth`

## Image Caching Strategy

Coil manages an LRU memory cache and a disk cache automatically.

Configuration:
- **Memory cache**: 25% of available app memory (Coil default)
- **Disk cache**: 500 MB (configurable), stores preview-quality images
- **Prefetch**: Slideshow prefetches the next 3 images ahead of the current one

**Offline display**: `SlideshowViewModel.imageUrl()` resolves a local preview
file first (`file://<path>`) and falls back to
`/api/assets/{id}/thumbnail?size=preview` on a cache miss. The API key is not
embedded in the URL. `MediaSelectionViewModel.thumbnailUrl()` uses the same
local-first pattern. GIFs display Immich's static preview; videos are excluded.

### Auth for image URLs

Retrofit, the preview downloader, the permission probe, and Coil all use the
`x-api-key` header. Media URLs contain no credential. Coil's interceptor adds
the header only when scheme, host, port, configured base path, and
`/api/assets/` path match the configured Immich server. Redirects are disabled
for all API-key-bearing clients.

The API-key permission probe checks `asset.view` against the first available
asset across accessible albums. Empty albums are skipped so they do not produce
a false `Unknown` status for an otherwise valid preview permission.

On Android 10 and later, launcher mode uses `RoleManager.ROLE_HOME` to
determine whether this app holds the Home role. This avoids false launcher-loss
warnings where a generic intent resolution returns the system resolver despite
a persistent Home selection.

## Media Cache (Room + WorkManager)

The app maintains a local Room database (`media_cache_db`) that stores
downloaded copies of album assets for offline-capable, instant slideshow
loading.

### Database schema

- **`cached_assets`** — one row per physical preview: `id`, `type`,
  `file_path`, `thumbnail_path`, `file_size`, `checksum`, `last_modified`,
  `cached_at`, `original_mime_type`.
- **`album_asset_cross_refs`** — composite `(album_id, asset_id)` membership;
  several albums can share one physical preview safely.
- **`album_sync_states`** — per-album sync metadata: `album_id` (PK),
  `last_synced_at`, `last_cursor`, `asset_count`.

### Sync lifecycle

1. **On slideshow load**: the ViewModel first checks the cache. If cached
   assets exist, they're displayed immediately. The ViewModel resolves
   each asset's local `file_path` up front (batch query via
   `MediaCacheRepository.getAssetFilePaths`) and serves `file://` URIs
   to Coil, so the image slideshow is fully **offline-capable**. If `autoSync`
   is on, foreground sync is enqueued only when the previous successful sync
   is older than the configured interval.
2. **Periodic sync**: `SyncScheduler` enqueues a periodic
   `MediaCacheWorker` (fork minimum 60 min; default 360 min)
   that fetches album asset lists, downloads new/updated assets, and
   removes deleted ones.
3. **Worker logic** (`MediaCacheWorker.performFullSync`):
   - Fetches remote asset list for each album via `POST /search/metadata`
   - **Album deletion detection**: if the fetch returns 404, the album is
     treated as permanently deleted — its cache is purged and it's flagged
     as gone. Transient errors (network, 5xx) are skipped; cache preserved.
   - **Empty-response guard**: the reconcile step only prunes cached assets
     when the remote list is non-empty. An empty response (possible
     search-service transient issue) does not wipe the cache.
   - Deletes cached assets no longer in the remote album (only when remote
     list is non-empty)
   - Filters out videos and downloads one bounded preview for each new/updated image
   - Skips download when cached and remote `lastModified` values match
   - Retries transient metadata/download failures without deleting the old cache
   - Updates `AlbumSyncState` with sync timestamp + asset count
   - Reports progress via `SyncProgress` StateFlow
   - If **all** selected albums were deleted (404), clears
     `selected_album_ids` in DataStore so `NavViewModel` routes the user
     back to album selection on next foreground.

Cache files are stored in `getExternalFilesDir("media_cache")`.

### WorkManager initialization

`ImmichFrameApp` implements `Configuration.Provider` and provides a
`HiltWorkerFactory` so that `MediaCacheWorker` can receive its
dependencies via Hilt. The default `WorkManagerInitializer` is removed
in `AndroidManifest.xml` (via `tools:node="remove"`) to avoid the
duplicate-initialization crash.

## Security

- API key stored via `EncryptedSharedPreferences` (AES-256, backed by Android Keystore)
- API key never logged, never sent to any endpoint except the user's Immich server
- Server URL stored in DataStore (not sensitive)
- No telemetry, no analytics, no third-party tracking
- The API key is only sent to `api.github.com` when checking for updates
  (no key sent — just a public GET to the releases endpoint)

## Navigation

Single-activity architecture with Compose Navigation:

```
Setup → Albums → Slideshow
                ↕
             Settings
```

- `Setup` is the start destination when no credentials are stored.
- Once credentials + album selection exist, start destination is `Slideshow`.
- `Settings` is accessible from `Slideshow` and `Albums`.
- `MediaSelection` is accessible from `Slideshow` (biometric-gated).

## State Persistence

| Data | Storage | Key | Type |
|---|---|---|---|
| Server URL | DataStore | `server_url` | String |
| API Key | EncryptedSharedPreferences | `api_key` | String (encrypted) |
| Selected Album IDs | DataStore | `selected_album_ids` | String set |
| Slideshow interval | DataStore | `interval_sec` | Int (5–120) |
| Transition duration | DataStore | `transition_sec` | Float (0–3) |
| Image fill mode | DataStore | `fill_mode` | String enum (CONTAIN/COVER) |
| Show clock | DataStore | `show_clock` | String bool |
| Clock seconds | DataStore | `clock_seconds` | String bool (default false) |
| Clock format | DataStore | `clock_format` | String enum (H24/H12, default H24) |
| Clock size | DataStore | `clock_size` | Float (24–96 sp) |
| Clock X position | DataStore | `clock_x` | Float (0.0–1.0 normalized, -1 = default) |
| Clock Y position | DataStore | `clock_y` | Float (0.0–1.0 normalized, -1 = default) |
| Clock snap to grid | DataStore | `clock_snap_to_grid` | String bool |
| Keep screen on | DataStore | `keep_screen_on` | String bool |
| Fullscreen | DataStore | `fullscreen` | String bool |
| Shuffle | DataStore | `shuffle` | String bool |
| Skip videos | DataStore | `skip_videos` | Forced `true` in this fork |
| Muted | DataStore | `muted` | String bool |
| Start on boot | DataStore | `start_on_boot` | String bool |
| Launcher mode | DataStore | `launcher_mode` | String bool (enables the Home activity-alias) |
| Boot verified | DataStore | `boot_verified` | String bool (self-test: BootReceiver sets true on successful fire) |
| Auto-update | DataStore | `auto_update` | Forced `false` in this fork |
| Adaptive background | DataStore | `adaptive_background` | String bool |
| Photo animations | DataStore | `photo_animations` | String bool |
| Anim: Zoom In | DataStore | `anim_zoom_in` | String bool |
| Anim: Zoom Out | DataStore | `anim_zoom_out` | String bool |
| Anim: Pan Left | DataStore | `anim_pan_left` | String bool |
| Anim: Pan Right | DataStore | `anim_pan_right` | String bool |
| Anim: Pan Up | DataStore | `anim_pan_up` | String bool |
| Anim: Pan Down | DataStore | `anim_pan_down` | String bool |
| Auto Sync | DataStore | `auto_sync` | String bool (default true) |
| Sync Interval | DataStore | `sync_interval_minutes` | Int (60/180/360/720/1440, default 360) |
| Night Mode | DataStore | `night_mode` | String bool (default false) |
| Night Mode Start | DataStore | `night_mode_start` | Int (minutes since midnight, default 1320 = 22:00) |
| Night Mode End | DataStore | `night_mode_end` | Int (minutes since midnight, default 420 = 07:00) |
| Night Mode Brightness | DataStore | `night_mode_brightness` | Int (0–100 percent, default 0) |
| Media Selection: Toggled IDs | DataStore | `media_selection_toggled_ids` | StringSet |
| Media Selection: New Items Shown | DataStore | `media_selection_new_shown` | String bool (default true) |
| Server Version | DataStore | `server_version` | String (e.g. "v1.135.0") |
| API Key Scoped | DataStore | `api_key_scoped` | String bool (key created with scoped permissions) |
| Permission Status | DataStore | `permission_status` | String JSON (serialized `PermissionCheckResult` — per-endpoint probe results) |
| Onboarding Steps | DataStore | `onboarding_completed_steps` | StringSet (step IDs) |

> **Note**: The `original_mime_type` column is NOT a DataStore key — it is a
> Room column on the `cached_assets` table (version 3 of `media_cache_db`).
> It retains Immich metadata for diagnostics; all image types still use the
> static preview endpoint.

All settings flow through a single shared DataStore instance
(`DataStoreProvider.kt`) — there must be only one DataStore active per file
or Android throws `IllegalStateException`.

## Build Configuration

- **Debug builds**: `applicationIdSuffix = ".debug"`, `versionNameSuffix = "-dev"`,
  signed with a shared debug keystore (so all dev builds share the same signature
  for clean upgrades over each other).
- **Release builds**: R8 minification + resource shrinking, signed with the
  production keystore.
- **`BuildConfig.GIT_SHA`**: injected at build time via `git rev-parse HEAD`,
  used by the self-update feature to compare against GitHub `dev-{sha}` release
  tags (debug/dev channel only).
- **`BuildConfig.VERSION_NAME`**: the app's semver (e.g. `0.1.0`), used by
  the self-update feature to compare against GitHub `vX.Y.Z` release tags
  (release builds — the primary auto-update target).

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | API calls to Immich server |
| `ACCESS_NETWORK_STATE` | Network connectivity checks |
| `RECEIVE_BOOT_COMPLETED` | Start-on-boot feature |
| `REQUEST_INSTALL_PACKAGES` | Self-update via GitHub releases (APK install) |
| `SYSTEM_ALERT_WINDOW` | Background Activity Launch exemption — required on Android 10+ (API 29+) for `BootReceiver` to call `startActivity()` from a `BOOT_COMPLETED` broadcast. Without it the OS silently blocks the launch. |

## Localization

The app is localized into 13 languages. String resources live in
`app/src/main/res/values*/strings.xml`.

| Locale | Directory |
|---|---|
| English (default) | `values/` |
| Arabic | `values-ar/` |
| Chinese (Simplified) | `values-zh-rCN/` |
| Dutch | `values-nl/` |
| French | `values-fr/` |
| German | `values-de/` |
| Italian | `values-it/` |
| Japanese | `values-ja/` |
| Korean | `values-ko/` |
| Polish | `values-pl/` |
| Portuguese | `values-pt/` |
| Russian | `values-ru/` |
| Spanish | `values-es/` |

`MissingTranslation` lint is disabled to allow incremental localization —
new strings fall back to English until translated.

## Screenshot Testing

Automated screenshot generation via [Roborazzi](https://github.com/takahirom/roborazzi)
+ [ComposablePreviewScanner](https://github.com/sergio-sastre/ComposablePreviewScanner).
Renders Compose `@Preview` composables on the JVM (Robolectric) — no device or
emulator needed.

### How it works

1. Screens are decomposed into a state-driven inner composable (e.g.
   `AlbumSelectionContent(state, thumbnailUrl, …)`) that accepts plain data
   + lambdas, no ViewModel or Hilt dependency.
2. `@Preview` functions construct fake `UiState` objects with demo data
   (album names, sample thumbnails from `app/src/main/assets/demo/`).
3. Roborazzi's `generateComposePreviewRobolectricTests` plugin auto-generates
   a parameterized JUnit test that scans the `com.dav3.immichframe` package
   tree and renders each preview to PNG.

### Commands

```bash
# Generate screenshots → app/build/outputs/roborazzi/*.png
./gradlew recordRoborazziDebug

# Verify screenshots match checked-in baselines
./gradlew verifyRoborazziDebug
```

### Output

Screenshots are copied to `docs/screenshots/<screen>/` for use in GitHub
README and Play Store listings.

### Screenshot inventory

| Screen | Folder | Variants |
|--------|--------|----------|
| Album Selection | `docs/screenshots/albums/` | `albums_loaded`, `albums_loading`, `albums_no_albums`, `albums_error` |
| Setup (landing/connect) | `docs/screenshots/setup/` | `domain_empty`, `domain_filled`, `domain_connecting`, `domain_error`, `auth_manual_key`, `auth_generate_key`, `auth_oauth`, `auth_success` |
| Slideshow | `docs/screenshots/slideshow/` | `photo_contain`, `photo_cover`, `with_clock`, `controls_visible`, `night_mode`, `paused`, `loading` |
| Settings | `docs/screenshots/settings/` | `playback`, `photo_animations`, `display`, `night_mode`, `clock`, `system`, `media_cache`, `connection` |
| Media Selection | `docs/screenshots/media_selection/` | `all_shown`, `some_hidden`, `loading` |

### Decomposition pattern

Each screen follows the same pattern to enable JVM previews:

1. Extract a `*Content` composable (e.g. `AlbumSelectionContent`,
   `SlideshowContent`, `SettingsContent`) that takes plain `UiState` +
   lambdas + an optional `TourState?` — no ViewModel, no Hilt, no lifecycle.
2. Production `*Screen` wraps the content in `TourHost` and wires up
   ViewModels, biometric launchers, system intents, lifecycle effects.
3. `@Preview` functions construct fake `UiState` with bundled demo data
   (Picsum photos in `assets/demo/`) and call the `*Content` composable.
4. Image URLs use `file:///android_asset/demo/photo_N.jpg` format so
   Coil renders bundled assets in Robolectric (no network).

| Screen | Content composable | Preview file |
|--------|--------------------|--------------|
| Album Selection | `AlbumSelectionContent` | `AlbumSelectionScreen.kt` |
| Setup | `SetupContent` | `SetupScreen.kt` |
| Slideshow | `SlideshowContent` | `SlideshowContent.kt` |
| Settings | `SettingsContent` | `SettingsContent.kt` |
| Media Selection | `MediaSelectionContent` | `MediaSelectionScreen.kt` |
