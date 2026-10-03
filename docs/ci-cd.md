## CI/CD Setup

### Current fork release policy

This section is the authoritative release policy for the low-bandwidth fork.
The inherited workflow documentation later in this file is retained only as
upstream reference and is not the production release path for this fork.
In-app self-update remains disabled; ManageEngine Endpoint Central Cloud is
the production APK distribution channel.

#### Version identifiers

`app/build.gradle.kts` is the source of truth for both Android version fields.

| Identifier | Policy |
|---|---|
| `versionName` | SemVer `MAJOR.MINOR.PATCH`; the user-visible release version |
| `versionCode` | Explicit positive integer; strictly increases for every APK distributed to a device |
| Git tag and GitHub Release | `v<versionName>` |
| ManageEngine Version Label | Exactly `<versionName>` |
| Release APK filename | `immich-photo-display-<versionName>.apk` |

The previous timestamp-derived version codes are already in the 1.78-billion
range. They must not be replaced by a smaller SemVer-derived number because
Android would reject it as a downgrade. The first release under this policy is
`versionName = "0.6.0"` and `versionCode = 1800000000`; later
releases increment the code by at least one. Rebuilding the same release does
not create a new version: once distributed, changed contents require a new
`versionName` and a larger `versionCode`.

#### Branch and release flow

- `main` contains integrated, verified code.
- Daily work uses `feature/<description>` or `fix/<description>` branches.
- A release uses `release/v<versionName>` and changes both version fields.
- The release commit is tagged with an annotated `v<versionName>` tag.
- The exact verified APK is uploaded unchanged to both GitHub Release and
  ManageEngine. Do not rebuild separate binaries for those destinations.
- A rollback is a new forward release with reverted code and a larger
  `versionCode`; do not distribute an older APK over a newer installation.

The fork currently has no `develop` branch, so the simpler `main` plus topic
branches model is intentional. Introduce `develop` only if the maintenance
volume justifies the additional merge-back process.

#### Owner-managed release signing

The production signing keystore remains under the owner's direct control.
Neither the keystore nor its passwords are committed, uploaded to GitHub
Actions, written to documentation, or stored in persistent environment
variables. The same signing key must be used for every update to
`com.familyphotoframe.immichframe.lowbandwidth`.

Run the following in a new PowerShell session from the repository root. Use an
absolute keystore path. `Read-Host -AsSecureString` keeps passwords out of the
command history; Gradle receives them only through temporary process
environment variables, which the `finally` block removes.

```powershell
$releaseStoreFile = Read-Host "Release keystore absolute path"
$releaseStorePassword = Read-Host "Keystore password" -AsSecureString
$releaseKeyAlias = Read-Host "Key alias"
$releaseKeyPassword = Read-Host "Key password" -AsSecureString

try {
    $env:SIGNING_STORE_FILE = $releaseStoreFile
    $env:SIGNING_STORE_PASSWORD = [System.Net.NetworkCredential]::new("", $releaseStorePassword).Password
    $env:SIGNING_KEY_ALIAS = $releaseKeyAlias
    $env:SIGNING_KEY_PASSWORD = [System.Net.NetworkCredential]::new("", $releaseKeyPassword).Password
    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

    .\gradlew.bat clean spotlessApply spotlessCheck lintDebug testDebugUnitTest assembleRelease `
        --no-daemon --no-configuration-cache
} finally {
    Remove-Item Env:SIGNING_STORE_FILE -ErrorAction SilentlyContinue
    Remove-Item Env:SIGNING_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:SIGNING_KEY_ALIAS -ErrorAction SilentlyContinue
    Remove-Item Env:SIGNING_KEY_PASSWORD -ErrorAction SilentlyContinue
    $releaseStorePassword = $null
    $releaseKeyPassword = $null
}
```

The owner keeps at least two encrypted/offline backups of the keystore and
records their recovery test separately from the repository. A newly generated
key cannot update the existing installed app.

#### Release verification

Before tagging or uploading, verify all of the following against the generated
`app/build/outputs/apk/release/app-release.apk`:

- Application ID is `com.familyphotoframe.immichframe.lowbandwidth`.
- `versionName` matches the intended SemVer value.
- `versionCode` is greater than every version already distributed.
- APK signature verification succeeds and the signer certificate SHA-256 is
  `C8EFCA24E2E030E0BB7159F56D49247555C8B0ECB4C60371932D574AF57706FF`.
- SHA-256 of the APK is recorded with the Git tag, source commit, file size,
  and ManageEngine rollout result. Do not record keystore paths or passwords.

Pilot the exact APK on `pilot-tb-x606x` first. After verifying an in-place
upgrade, retained settings/cache, slideshow operation, launcher recovery, and
ManageEngine inventory, approve the same APK for wider distribution. The
cross-system procedure is documented in the family photo-frame repository's
`docs/operations/immich_photo_display_release.md` runbook.

### Legacy upstream automation reference

The sections below describe inherited workflows. In particular,
`.github/workflows/prod-build.yml` still assumes GitHub-hosted signing secrets
and must not be used for fork production releases under the current policy.
Before any production automation is enabled, redesign it to trigger only from
an explicit version tag, validate the tag against both Gradle version fields,
and preserve owner-managed signing.

### Branching Strategy

- `develop` — active development branch. Push/PR triggers a debug APK build.
- `main` — production branch. Merges trigger a signed release AAB + APK build.
- Feature branches off `develop`: `feat/<description>`, `fix/<description>`.
- Release branches: `release/v<x.y.z>` off `develop`, PR target `main`.

#### Release PR flow & merge-back (MANDATORY)

Every production release touches one file: `app/build.gradle.kts`
(`versionName`). The workflow file `prod-build.yml` is **never edited
for a release** — it parses the tag/name from `versionName` and lets
GitHub auto-generate the release notes from the PR/commit history. This
keeps `main` and `develop` from conflicting on the workflow file (which
is what happened after v0.2.0).

Flow:

1. Branch `release/v<x.y.z>` off the current `develop` HEAD.
2. Bump `versionName` in `app/build.gradle.kts`. (Release notes are
   auto-generated — no manual notes file to edit.)
3. Open PR **`release/v<x.y.z>` → `main`** (the release PR).
4. Immediately after merge (or in parallel), open a **backport PR
   `release/v<x.y.z>` → `develop`** that bumps `versionName` to the **next
   dev version** (`v<x.y.z+1>`, rendered as `<x.y.z+1>-dev` via the debug
   `versionNameSuffix`). This carries `main`'s release-specific commits
   back into `develop` so the branches stay aligned.
5. Never skip the merge-back — forgetting it (as happened after v0.2.0)
   leaves `develop` pinned to a stale `versionName` and guarantees
   conflicts on the next release PR.

### Dev Build (develop branch)

Triggers on push/PR to `develop` (workflow: `.github/workflows/dev-build.yml`).

Two parallel jobs:

#### Lint job
- Spotless code style check (`spotlessCheck`)
- Android Lint (`lintDebug`)
- Lint reports uploaded as artifacts (7-day retention)

#### Build job
- Builds debug APK with `.debug` application ID suffix and `-dev` version name suffix
- APK signed with the committed `app/debug.keystore` — the same key used by local `assembleDebug` builds, so local and CI dev builds are interchangeable (clean upgrade-over-install in either direction)
- Artifact: `immichframe-debug` (14-day retention)
- **On push to develop** (not PR): publishes a GitHub pre-release:
  - Tag: `dev-{full sha}` (explicitly pinned via `--target ${{ github.sha }}`)
  - Title: "Dev Build (unstable)"
  - Prerelease flag set
- **Auto-cleanup**: keeps only the 3 most recent dev releases, deletes older ones with `gh release delete --cleanup-tag`

No GitHub secrets are required for the dev build — the debug keystore is committed to the repo (see [Shared Debug Keystore](#shared-debug-keystore)).

The release is created via `gh release create` (not `softprops/action-gh-release`) for full control over tag dates and target commit. The `--target ${{ github.sha }}` flag is critical — without it, GitHub Actions' detached HEAD checkout causes the tag to be created on the wrong commit.

### Production Build (main branch)

Triggers on push to `main` or manual `workflow_dispatch` (workflow: `.github/workflows/prod-build.yml`).

- Sets up Bun to compile the `keymgr` cross-platform binary tools
- Cross-compiles 5 keymgr binaries via `scripts/build.sh`:
  `keymgr-darwin-arm64`, `keymgr-darwin-x64`, `keymgr-linux-arm64`,
  `keymgr-linux-x64`, `keymgr-windows-x64.exe`
- Decodes signing keystore from `SIGNING_KEYSTORE_BASE64` secret
- Builds signed release **AAB + APK** in a single Gradle invocation
  (`bundleRelease assembleRelease`) with R8 minification + resource shrinking.
  Both targets share one task graph (compile + R8 once), which is faster than
  two sequential invocations and avoids the duplicated setup of split jobs.
- Uploads both as artifacts (90-day retention)
- Creates a GitHub Release with `softprops/action-gh-release@v3`

#### Version (single source of truth)

The release tag and name are **derived**, never hardcoded in the workflow:

- **Tag + name**: parsed from `versionName` in `app/build.gradle.kts` by a
  `Read version` step (`grep` + `sed`). Output: `v0.3.0`. The workflow file
  itself never changes between releases.
- **Release notes body**: auto-generated by GitHub
  (`generate_release_notes: true` on the `softprops/action-gh-release` step).
  GitHub compiles the PR/commit list since the previous tag, plus new
  contributors and a full diff link. No manual `.github/release-notes.md`
  file is maintained — the historical static file was removed when this
  automation was introduced.

This avoids the conflict-prone pattern of hardcoding the version/tag/body
inline in `prod-build.yml`, which required editing the workflow on every
release and caused merge conflicts between `main` and `develop`.

Release assets include: APK, AAB, keymgr binaries for all platforms, and all key management scripts

#### Required GitHub Secrets (prod)

| Secret | Description |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | The `.jks` keystore file, base64-encoded |
| `SIGNING_STORE_PASSWORD` | Keystore file password |
| `SIGNING_KEY_ALIAS` | Key alias name within the keystore |
| `SIGNING_KEY_PASSWORD` | Password for the specific key |

#### Generating the Keystore Locally

```bash
keytool -genkeypair \
  -alias release \
  -keyalg RSA -keysize 4096 \
  -validity 36500 \
  -keystore release.jks \
  -storepass <STORE_PASSWORD> \
  -keypass <KEY_PASSWORD> \
  -dname "CN=ImmichFrame, OU=Mobile, O=dav3, L=City, ST=State, C=US"
```

#### Uploading to GitHub

```bash
# Base64-encode the keystore
base64 -i release.jks -o keystore.b64

# Add as repository secret:
# Settings → Secrets and variables → Actions → Secrets → New secret
# Name: SIGNING_KEYSTORE_BASE64
# Value: (paste contents of keystore.b64)

# Then add the remaining secrets:
# SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD
```

#### Local Release Build

For local signed builds, set the environment variables before running Gradle:

```bash
export SIGNING_STORE_FILE=/path/to/release.jks
export SIGNING_STORE_PASSWORD=<password>
export SIGNING_KEY_ALIAS=release
export SIGNING_KEY_PASSWORD=<password>

./gradlew bundleRelease
```

> Release builds are signed but intentionally not minified or resource-shrunk.
> On the supported Android 10 tablet, R8-minified builds crash at startup with a
> `ClassCastException` in generated Hilt code, including when R8 optimization is
> disabled. Re-enable R8 only after a reproducing regression test and a verified fix.

> Immediate media-cache synchronization uses a normal one-time WorkManager request,
> not expedited work. Expedited work requires a foreground notification implementation
> on Android 10; using it without that implementation terminates the app during sync.

### API Key Manager Tooling (keymgr)

The production build compiles and releases the `keymgr` cross-platform CLI tool alongside the app artifacts. This tool helps users generate and validate Immich API keys with the exact permissions ImmichFrame requires.

#### Release Assets (prod build)

| Asset | Platform | Purpose |
|---|---|---|
| `immichframe-release.apk` | Android | Installable APK |
| `immichframe-release.aab` | Android/Play Store | App Bundle |
| `keymgr` | macOS, Linux | Compiled standalone Bun binary |
| `generate-api-key.sh` / `check-api-key.sh` | macOS, Linux | Bash scripts (curl) |
| `generate-api-key.ps1` / `check-api-key.ps1` | Windows (PowerShell 5.1+) | Native PowerShell scripts (Invoke-RestMethod) |

> **Windows users:** A pre-compiled `keymgr.exe` is not provided (Bun cannot cross-compile to Windows from macOS/Linux CI). Windows users should use the PowerShell scripts, or compile locally with `bun build scripts/keymgr.ts --compile --outfile keymgr.exe`.

All scripts are built from the same source of truth (`scripts/keymgr.ts`) to ensure consistent behavior across platforms.

### Local Debug Build

```bash
./gradlew clean spotlessApply spotlessCheck lintDebug assembleDebug
```

Local debug builds are signed with the same committed `app/debug.keystore` used by CI, so a locally-built APK and a CI-built dev APK are interchangeable — you can install one over the other without uninstalling first.

### Shared Debug Keystore

`app/debug.keystore` is a standard Android debug keystore (storepass: `android`, alias: `androiddebugkey`, keypass: `android`) committed to the repo. It is shared between local and CI dev builds so both produce APKs with the same signature, enabling clean upgrade-over-install in either direction.

This is safe because:

- Android debug credentials are publicly documented and carry no secrecy.
- It only ever signs the `com.dav3.immichframe.debug` application ID (the `.debug` suffix variant) — never a production release.
- Release builds use a separate keystore (`SIGNING_KEYSTORE_BASE64` secret) that is never committed.

Existing dev installs signed with the previous CI-only key will need a one-time uninstall before the first install of a build signed with this keystore.

### Self-Update (GitHub Releases)

The app's self-update feature consumes GitHub releases. The behavior depends on
build type:

- **Release builds** (primary target): fetch `/releases/latest` and compare the
  `vX.Y.Z` tag against the installed `versionName` via semantic version comparison.
  If newer, download the APK and invoke the system installer.
- **Debug builds** (dev channel): list recent releases, pick the newest `dev-{sha}`
  tag, and compare its SHA against `BuildConfig.GIT_SHA`. If different, download
  and install.

Dev builds published to the `develop` branch feed the dev channel. Production
releases published to the `main` branch feed the release channel. Self-update is
disabled entirely for Play Store installs (see [functional-spec.md](functional-spec.md#F5d)).

### Play Store Publishing (Future)

The AAB produced by the production workflow is ready for Play Store upload. Future enhancement: add `r0adkll/upload-google-play@v1` action to automate Play Store publishing from the `main` branch workflow.

Requirements for Play Store:
1. Google Play service account JSON key (stored as `PLAY_SERVICE_ACCOUNT_JSON` secret)
2. Existing Play Console app listing
3. First upload must be manual (Play Store requirement for new apps)
The AAB produced by the production workflow is ready for Play Store upload.
Future enhancement: add `r0adkll/upload-google-play@v1` action to automate
Play Store publishing from the `main` branch workflow.

Requirements for Play Store:
1. Google Play service account JSON key (stored as `PLAY_SERVICE_ACCOUNT_JSON` secret)
2. Existing Play Console app listing
3. First upload must be manual (Play Store requirement for new apps)
