# NightGuard

A personal Android device-security app. Install it on your own phone to see:

- **App usage timeline** — which app was in the foreground, and when.
- **Incognito/private-browsing detection** — flags when a known browser shows a private-tab indicator.
- **Sensitive settings access** — logs every Settings screen opened, and specifically flags the ones someone could use to hide activity or weaken NightGuard: accessibility settings, app permissions, usage-access permission, notification access, app info (uninstall/force-stop/clear data), developer options, factory reset, screen-lock changes, date & time, Do Not Disturb, device admin, "install unknown apps."
- **Selfies on every trigger above** — not just unlock. A front-camera photo is taken whenever the phone is unlocked, *and* whenever one of the sensitive settings screens above is opened, *and* if NightGuard's own Usage Access or Accessibility Service permission gets turned off (its self-defense watchdog, checked independently every ~15s so it isn't blind to its own components being disabled).
- **User/profile switches** — logs Android multi-user profile switches, if your device has more than one profile configured.

Everything is stored **locally on the device only** (Room database + AES-256-GCM encrypted photos via Jetpack Security). Nothing is uploaded anywhere.

## Why this exists / how to use it responsibly

This is built for **monitoring your own device** — e.g. figuring out who's been getting into your phone while you're asleep. A few things worth knowing before you rely on it:

- Android requires a persistent notification for any app doing background camera or usage-stats work (as of Android 10+), so NightGuard cannot run fully invisibly — it shows a low-priority "NightGuard is active" notification while monitoring.
- If anyone besides you has legitimate/known access to this phone (partner, kid, roommate), consider whether they should know monitoring is in place — a lock-screen note or a conversation avoids ambiguity later, and in some places continuous photo capture of other people has legal wrinkles beyond just "it's my phone."
- Incognito detection works by reading on-screen text/UI nodes in browser apps (there's no public Android API for "this tab is private"), so it's a best-effort signal, not a guarantee — it'll need updating if a browser changes its private-mode UI.

## Architecture

| Component | File | Purpose |
|---|---|---|
| `AppUsageMonitorService` | `service/` | Foreground service polling `UsageStatsManager` every 15s for foreground-app changes |
| `NightGuardAccessibilityService` | `service/` | Accessibility Service reading window content for incognito indicators + sensitive Settings screens |
| `UnlockCaptureService` | `capture/` | CameraX-based headless front-camera capture, triggered on unlock |
| `UnlockReceiver` / `UserSwitchReceiver` / `BootReceiver` | `receiver/` | React to `ACTION_USER_PRESENT`, profile switches, and reboot |
| `TimelineRepository` + Room DB | `data/` | Single local event log (`timeline_events` table) all sources write to |
| `SecureImageStore` | `util/` | Encrypts/decrypts unlock selfies with Jetpack Security |
| `SetupScreen` / `TimelineScreen` | `ui/` | Compose UI: permission onboarding checklist, and the event timeline (tap "View" on an unlock event to see the photo) |

## Required permissions (all granted manually via the in-app Setup screen)

- **Usage access** (`PACKAGE_USAGE_STATS`) — special permission, granted via Settings, not a runtime dialog.
- **Accessibility service** — also granted via Settings; required for incognito/settings detection.
- **Camera** — for unlock selfies.
- **Notifications** — required on Android 13+ to keep the foreground services running.
- **Ignore battery optimization** — recommended so Android doesn't kill the background monitor.

The app walks you through granting each of these on first launch.

## Building

This repo was scaffolded without access to the Android SDK/emulator, so it hasn't been built or run — treat it as a solid starting point to open in Android Studio, not a verified build.

1. Open the project root in **Android Studio** (Koala/2024.1 or newer recommended).
2. Let Gradle sync — it will offer to generate the Gradle wrapper jar if missing (the wrapper jar binary isn't committed to this repo; `gradle/wrapper/gradle-wrapper.properties` pins Gradle 8.7).
3. Replace the placeholder vector icon (`app/src/main/res/drawable/ic_shield.xml`) with a real adaptive launcher icon via Android Studio's Image Asset tool if you want a proper app icon.
4. Build & run on a physical device (camera/usage-stats/accessibility behavior can't be meaningfully tested on most emulators).
5. On first launch, walk through the Setup screen to grant all five permissions, then the Timeline screen starts filling in.

### Known things to double check once you can build

- Dependency versions (Compose BOM, AGP, Kotlin, Room, CameraX) were picked for mutual compatibility as of this writing but weren't verified against an actual Gradle sync — Android Studio's upgrade assistant will flag anything stale.
- `NightGuardAccessibilityService`'s incognito/browser keyword list will need tuning against the actual browsers you use.

### A note on OEM-skinned Settings apps (Samsung One UI, etc.)

Sensitive-settings detection has two layers: matching the screen's internal fragment class name (works on stock-ish Android), and matching the on-screen title/heading text (works regardless of OEM skin, since it reads what's literally displayed). The title list in `SENSITIVE_TITLE_KEYWORDS` was written from general knowledge of Android/One UI screen names, not verified against a physical device — if a screen you'd expect to trigger a selfie doesn't, it's almost certainly a wording mismatch (e.g. Samsung titles a screen slightly differently than guessed) rather than a missing feature. Tell me the exact screen and I'll add the matching string. Samsung's Secure Folder gets special-cased since it's the most common way to hide apps/photos on a Galaxy phone.
