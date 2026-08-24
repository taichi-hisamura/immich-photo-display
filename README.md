# Immich Frame Low Bandwidth — metered Android fork

> This is a low-bandwidth fork of ImmichFrame v0.5.0 for a dedicated,
> SIM-connected photo frame. It stores preview images only, excludes videos,
> uses complete paginated album sync, and disables upstream self-update. See
> [the fork profile](docs/low-bandwidth-profile.md) for normative behavior.

A native Android slideshow app that connects directly to your Immich server.
No intermediary Docker container, no WebView, no second API key.

## Why?

[ImmichFrame_Android](https://github.com/immichFrame/ImmichFrame_Android) uses
a server+client architecture where a Docker container holds your Immich API
key and serves a web UI that the Android app loads in a WebView. This only
makes sense if every screen in your house shows the same content. For a
single photo frame, it's unnecessary complexity.

This app talks to the Immich API directly using `x-api-key` authentication,
lets you pick which album(s) to display, and remembers your choice.

![Architecture comparison: this app vs ImmichFrame_Android](docs/architecture-comparison.svg)

> The source for this diagram is in [`docs/architecture-comparison.excalidraw`](docs/architecture-comparison.excalidraw) — open it at [excalidraw.com](https://excalidraw.com) to edit.

## Features

- Direct Immich API access (no middleman server)
- Native Jetpack Compose UI (no WebView)
- Album picker with multi-select
- Fullscreen slideshow with crossfade transitions
- Configurable interval, transition speed, fill mode (Contain/Cover)
- Image-only playback; videos are excluded and GIFs use a static preview
- Draggable clock overlay with configurable size, 12h/24h format, optional seconds, snap-to-grid, and orbital burn-in motion (when photo animations are enabled)
- Photo animations (Ken Burns: zoom in/out, pan left/right/up/down, or random) — also serves as burn-in protection
- Adaptive background (fills letterbox bars with each photo's edge colors as a gradient)
- Shuffle mode for randomized image order
- Progress bar showing time remaining per image
- Start on boot (with SYSTEM_ALERT_WINDOW permission for Android 10+ BAL exemption, plus OEM autostart permission detection)
- Launcher mode (Home replacement) — the most reliable boot method for dedicated photo frames; bypasses BOOT_COMPLETED entirely
- Fork self-update disabled until a fork-owned signed release channel exists
- Preview-only offline cache with a six-hour default background sync
- Display Sleep Schedule — recommended: turns the display off after the device timeout and wakes it silently at configured daily times; on Android 12+ it guides the user to grant Alarms & reminders for on-time transitions
- Night Mode — fallback: keeps photos visible while dimming the screen during set hours when display sleep is not reliable
- Auto-resumes last album on launch
- Interactive onboarding tour with coachmark overlays — guides users through setup, album selection, slideshow controls (including back-to-albums and update indicator), and settings; replayable per-screen ("Show Tour Again") or globally ("Reset All Tours")
- Adaptive launcher icon with day/night variants and Android 13+ monochrome (themed icon) support; dedicated debug-build variant (amber background); separate background-free logo drawable for the Setup screen
- Localized into 13 languages (en, ar, zh, nl, fr, de, it, ja, ko, pl, pt, ru, es)
- **In-app API key generation** — log in with email/password or OAuth; the app auto-creates a scoped key (no external scripts needed)
- **Permission verification** for the four image-only endpoints
- Scoped API key (album.read, asset.read, asset.view, user.read)
- API key stored encrypted on-device (AES-256, Android Keystore)
- Optional six-digit in-app administration PIN protects album selection,
  server URL, and API key changes (works without device screen lock)

## Screenshots

| Slideshow | Clock overlay | Night mode |
|:---:|:---:|:---:|
| ![Slideshow](docs/screenshots/slideshow/photo_contain.png) | ![Clock](docs/screenshots/slideshow/with_clock.png) | ![Night mode](docs/screenshots/slideshow/nightmode.png) |

| Album selection | Media selection | Setup |
|:---:|:---:|:---:|
| ![Albums](docs/screenshots/albums/albums_loaded.png) | ![Media](docs/screenshots/media_selection/some_hidden.png) | ![Setup](docs/screenshots/setup/domain_filled.png) |

| Settings — Playback | Settings — Clock | Settings — Night mode |
|:---:|:---:|:---:|
| ![Playback](docs/screenshots/settings/playback.png) | ![Clock settings](docs/screenshots/settings/clock.png) | ![Night mode settings](docs/screenshots/settings/night_mode.png) |

> All screenshots are Full HD (1080×1920), generated on JVM via Roborazzi from Compose `@Preview` functions — no device or emulator needed. See [`docs/technical-spec.md`](docs/technical-spec.md) → Screenshot Testing for the full inventory.

## Setup

1. Enter your Immich server URL — the app validates it and detects the version
2. Paste an existing API key, or generate one in-app (email/password or OAuth)
3. Select album(s)
4. Slideshow starts

### API key

You can **paste an existing API key** from Immich (User Settings → API Keys),
or use the **Generate Key** button during setup to auto-create one — just
enter your email and password. Your password is used once to create the key,
then immediately discarded (never stored). OAuth is also supported for
servers that have it enabled.

Alternatively, you can create a key manually in Immich under
**User Settings → API Keys**. The key needs 4 permissions:

| Permission | Used for |
|---|---|
| `album.read` | List albums, get album info |
| `asset.read` | Search/list assets in albums |
| `asset.view` | View thumbnails and previews |
| `user.read` | Validate the key during setup |

> Requires Immich **v1.135+** for scoped keys. On older versions, any API key
> will work but will have full access — update your server if possible.

**Option A — Automatic (recommended).** Helper scripts create and scope the
key for you:

```bash
# macOS / Linux
./scripts/generate-api-key.sh https://photos.example.com:2283 user@example.com

# Windows PowerShell
.\scripts\generate-api-key.ps1 https://photos.example.com:2283 user@example.com
```

You'll be prompted for your password. The script creates a key named
`ImmichPhotoFrame` with exactly the 4 permissions above.

To verify an existing key's permissions against the app's endpoints:

```bash
./scripts/check-api-key.sh https://photos.example.com:2283 <your-api-key>
```

**Option B — Manual.** Create the key in the Immich web UI:

1. Log in to your Immich server.
2. Go to **User Settings → API Keys → New API Key**.
3. Name it `ImmichPhotoFrame` (or any name you like).
4. Under **Permissions**, select exactly these four:
   - `album.read`
   - `asset.read`
   - `asset.view`
   - `user.read`
5. Copy the generated key — it won't be shown again.

## Tech Stack

Kotlin · Jetpack Compose · Retrofit · Coil 3 · Hilt · Room · WorkManager · DataStore · Palette

## Requirements

- Immich server (v1.120+; v3 supported, see API docs for a query-param caveat)
- Android 8.0+ (API 26)
- JDK 17 for building

## Documentation

- [Overview](docs/overview.md) — goals and architecture
- [Functional Spec](docs/functional-spec.md) — features and user flows
- [Technical Spec](docs/technical-spec.md) — tech stack, data flow, state persistence
- [API Reference](docs/api-reference.md) — Immich API endpoints used
- [UI Spec](docs/ui-spec.md) — screen layouts
- [CI/CD](docs/ci-cd.md) — branching, build workflows, signing

## License

MIT
