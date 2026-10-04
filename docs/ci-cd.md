## CI/CD Setup

### Current fork policy

This document is the authoritative release and verification policy for the
low-bandwidth fork. ManageEngine Endpoint Central Cloud is the production APK
distribution channel. In-app self-update remains disabled for this fork.

GitHub Actions is verification-only. Production signing, release packaging,
tagging, GitHub Release creation, and distribution are owner-managed local
operations.

### Version identifiers

`app/build.gradle.kts` is the source of truth for both Android version fields.

| Identifier | Policy |
| --- | --- |
| `versionName` | SemVer `MAJOR.MINOR.PATCH`; user-visible release version |
| `versionCode` | Explicit positive integer; strictly increases for every APK distributed to a device |
| Git tag | Annotated `v<versionName>` tag created manually after pilot approval |
| GitHub Release | Created manually for the approved release tag |
| ManageEngine Version Label | Exactly `<versionName>` |
| Release APK filename | `immich-photo-display-<versionName>.apk` |

The previous timestamp-derived version codes are already in the 1.78-billion
range. They must not be replaced by a smaller SemVer-derived number because
Android would reject it as a downgrade. The first release under this policy is
`versionName = "0.6.0"` and `versionCode = 1800000000`; later releases use a
new version name and a larger version code. Rebuilding the same release does
not create a new version: changed contents require a new version.

### Production signing policy

- The release keystore is owner-managed and local-only.
- Never commit or upload the release keystore to GitHub.
- Never store the release keystore or release signing passwords in GitHub
  Actions Secrets.
- Never use a GitHub-hosted runner for production signing.
- The existing signer certificate must be maintained across updates.
- Generating a new key is not an acceptable migration path for installed
  devices because a newly generated key cannot update the existing app.
- Release signing credentials are entered interactively on the owner-managed
  machine and are removed from its temporary process environment afterward.

The former `.github/workflows/prod-build.yml` was intentionally removed. Do
not recreate a production signing workflow unless the owner explicitly changes
this policy.

### CI verification policy

The current GitHub Actions contract is verification only:

- Pull requests targeting `main` use
  `.github/workflows/pr-verify.yml`.
- The workflow runs `testDebugUnitTest`, `lintDebug`, `assembleDebug`,
  `processDebugMainManifest`, and `processReleaseMainManifest`.
- It uses `contents: read` and does not require release secrets.
- It performs no production release signing, release packaging, artifact
  publication, tag creation, or GitHub Release creation.
- `assembleDebug` uses the non-production shared debug keystore required for
  an Android debug build; it never signs a production APK.
- The existing `dev-build.yml` remains a development-only workflow for the
  repository's `develop` branch. Its debug signing and dev release behavior
  are not production release operations.

Repository-wide `spotlessCheck` is not part of the required main-PR workflow.
The repository has pre-existing CRLF files that cause Spotless violations in
untouched files; this policy does not introduce a large line-ending rewrite.

### Production release flow

Follow this order for every production release:

1. Confirm the PR verification workflow succeeds.
2. Confirm that the release PR already contains the intended `versionName` and
   `versionCode`, then merge that approved PR into `main`.
3. On the owner-managed local machine, check out the exact merged commit from
   `main`.
4. Confirm that the working tree is clean.
5. Confirm that the committed `versionName` and `versionCode` in that exact
   commit already match the intended release.
6. If either version field is wrong, stop the release. Do not edit it only in
   the local working tree; create and merge a corrective release/version PR,
   then restart this flow from the new exact commit.
7. Run the local release build procedure below from that exact clean commit.
8. Enter the release keystore path, password, and alias interactively.
9. Build the signed release APK locally.
10. Verify the application ID, `versionName`, `versionCode`, signer certificate
    SHA-256, and APK SHA-256.
11. Pilot the exact APK on the pilot device.
12. If the pilot passes, create the annotated Git tag manually on the exact
    committed source tree.
13. Create the GitHub Release manually from that tag.
14. Upload the exact same APK to the GitHub Release.
15. Upload the exact same APK to ManageEngine.
16. Roll out that same binary to the remaining devices.

Do not rebuild separate APKs for GitHub Release and ManageEngine. The APK
verified on the pilot device must be the APK distributed through both channels.
The Git tag, GitHub source commit, and locally built APK must correspond to the
same committed tree. Production APKs must be built only from a clean working
tree; never patch production version fields only in that working tree.

### Local production build

Run this procedure in a new PowerShell session on the owner-managed machine
from the repository root. Use an absolute keystore path. `Read-Host
-AsSecureString` keeps passwords out of command history; the `finally` block
removes the temporary process environment after the build.

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

    .\gradlew.bat assembleRelease --no-daemon --no-configuration-cache
} finally {
    Remove-Item Env:SIGNING_STORE_FILE -ErrorAction SilentlyContinue
    Remove-Item Env:SIGNING_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:SIGNING_KEY_ALIAS -ErrorAction SilentlyContinue
    Remove-Item Env:SIGNING_KEY_PASSWORD -ErrorAction SilentlyContinue
    $releaseStorePassword = $null
    $releaseKeyPassword = $null
}
```

Before distribution, verify the generated
`app/build/outputs/apk/release/app-release.apk`:

- Application ID is `com.familyphotoframe.immichframe.lowbandwidth`.
- `versionName` matches the intended release.
- `versionCode` is greater than every version already distributed.
- APK signature verification succeeds and the signer certificate SHA-256 is
  `C8EFCA24E2E030E0BB7159F56D49247555C8B0ECB4C60371932D574AF57706FF`.
- The APK SHA-256 is recorded with the tag, source commit, file size, and
  ManageEngine rollout result.

Do not record keystore paths, passwords, or other signing secrets in the
repository. Keep at least two encrypted/offline backups of the keystore and
test their recovery separately from the repository.

Pilot the exact APK on `pilot-tb-x606x` first. Verify the in-place upgrade,
retained settings/cache, slideshow operation, launcher recovery, and
ManageEngine inventory before wider rollout. The cross-system procedure is
documented in the family photo-frame repository's
`docs/operations/immich_photo_display_release.md` runbook.

### Local debug build

```bash
./gradlew clean spotlessApply spotlessCheck lintDebug assembleDebug
```

Local debug builds use the committed `app/debug.keystore`, which is also used
by the development workflow. This key is public debug signing material and
must never be used for a production release.

### Self-update and Play Store

The fork keeps in-app self-update disabled. GitHub Releases are a manually
managed record and distribution channel for owner-approved production APKs,
not an automated signing or release trigger.

Play Store publishing remains a future, separately designed process. It must
preserve the local-only production signing policy and must not introduce
GitHub-hosted release signing without an explicit owner decision.
