---
id: RUNBOOK-MANAGEENGINE-KIOSK-001
title: ManageEngine Single App Kiosk Deployment
type: runbook
status: approved
implementation: implemented
updated: 2026-10-04
canonical_for:
  - Lenovo Tab M10 FHD Plus deployment with ManageEngine Single App Kiosk and Tailscale
depends_on:
  - docs/ci-cd.md
related_decisions: []
supersedes: null
---

# ManageEngine Single App Kiosk Deployment

## Scope

This runbook records the validated pilot boundary for:

- Lenovo Tab M10 FHD Plus
- Android 10
- ManageEngine Endpoint Central Cloud
- Immich Photo Display v0.6.5 or later
- Tailscale
- A self-hosted Immich server

The app connects to Immich through Tailscale. ManageEngine owns Android Home,
Single App Kiosk startup, Pause/Resume, and reboot recovery. Immich Photo
Display is a regular application and must not be selected as the Android Home
launcher.

## Recommended configuration

| Component | Configuration |
| --- | --- |
| Immich Photo Display | Single App Kiosk target |
| Tailscale | Kiosk Hidden App; latest stable version; Force enabled connection toggle ON; key expiry disabled / No expiry |
| VPN | Android Always-on VPN ON; VPN Lockdown OFF |
| ManageEngine Self Service | 26.09.01 or later |
| Android Home | Owned by ManageEngine / MDM Self Service; do not make Immich Photo Display the default Home app |

Keep Tailscale available as a Hidden App so the VPN service can reconnect while
the photo display remains the only foreground kiosk application. Do not add
MDM-specific behavior to the Android app.

## Known issue and root cause

The pilot reproduced a failure with ManageEngine MDM Self Service 26.08.02 and
Tailscale 1.98.8: after a managed app configuration push, Android Always-on VPN
was turned off and Tailscale remained disconnected without self-recovery.

ManageEngine Android MDM Agent 26.09.01 release notes identify the corresponding
fix: “Always-On VPN configuration was removed when app configurations were pushed
to the device”. Use Self Service 26.09.01 or later before treating a managed
configuration push as a stable baseline.

## Pilot validation

The following sequence passed on the physical pilot device using the v0.6.5
release APK:

1. Push the Tailscale Managed App Configuration.
2. Reapply the VPN profile and establish the baseline with Always-on VPN ON.
3. Confirm Tailscale is Connected for more than ten minutes.
4. Pause and Resume Single App Kiosk, then confirm Always-on VPN remains ON.
5. Restart the device from ManageEngine.
6. Confirm, without local user interaction:
   - ManageEngine returns.
   - Tailscale connects.
   - Android Always-on VPN remains ON.
   - Single App Kiosk returns.
   - Immich Photo Display v0.6.5 starts.
   - The slideshow is visible.
   - Immich synchronization succeeds.

## Validation matrix

| Test | Result |
| --- | --- |
| Kiosk Pause → Resume | PASS |
| Home chooser appears during resume | NO |
| Always-on VPN retained after managed configuration push | PASS |
| Tailscale reconnect after reboot | PASS |
| Reboot → ManageEngine / Kiosk recovery | PASS |
| Reboot → slideshow | PASS |
| Immich sync after reboot | PASS |

## Troubleshooting

### Tailscale is Disconnected

1. Check that Tailscale is assigned as a Kiosk Hidden App.
2. Confirm Force enabled connection toggle is ON and key expiry is disabled.
3. Check the device's Android Always-on VPN provider and connection state.
4. If the device uses Self Service 26.08.02 or earlier, update the agent first.
5. Reapply the VPN profile, confirm Always-on VPN is ON, then redistribute the
   managed app configuration and observe the connection for at least ten minutes.

### Always-on VPN is OFF

Do not enable VPN Lockdown as a workaround. Reapply the VPN profile, turn
Always-on VPN ON, confirm Lockdown is OFF, and then repeat the managed
configuration push on Self Service 26.09.01 or later. If the setting is removed
again, stop the rollout and capture the agent version and profile assignment
before changing the kiosk target.

### A Home chooser appears

Immich Photo Display must not be an Android Home candidate. Confirm that the
installed APK is v0.6.5 or later, the app is assigned as the Single App Kiosk
target, and Android Home is still managed by ManageEngine. Reapply the kiosk
profile if necessary; do not select Immich Photo Display as the default Home
app.

### Kiosk does not resume

Confirm that Immich Photo Display is the only foreground kiosk target and that
Tailscale is assigned as Hidden App rather than as the foreground target. From
ManageEngine, reapply the kiosk profile and launch the app through its normal
package launcher entry. Check VPN connectivity before investigating slideshow
or Immich synchronization.

### MDM agent is outdated

Update ManageEngine Self Service to 26.09.01 or later, reapply the VPN profile,
recreate the Always-on VPN baseline, and redistribute the Tailscale managed
configuration. Repeat the Pause/Resume and reboot tests before continuing the
rollout.

## Application boundary

The Android app intentionally contains no ManageEngine or Tailscale integration.
Its contract is the ordinary `MAIN` + `LAUNCHER` entry and normal Immich API
behavior. Android Home and kiosk lifecycle changes belong in MDM policy and
must not be implemented by reintroducing a Home intent, Home-role dependency,
or default-launcher prompt.
