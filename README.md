# NightGuard

A personal Android device-security app. Install it on your own phone to see:

- **App usage timeline** — which app was in the foreground, and when.
- **Incognito/private-browsing detection** — flags when a known browser shows a private-tab indicator, and captures whatever page title/address-bar text is visible on screen at that moment plus a selfie. (Private mode stops the browser from writing history to disk after the fact; it does nothing about what's rendered on screen while it's open, which is what this reads live.)
- **Sensitive settings access** — logs every Settings screen opened, and specifically flags the ones someone could use to hide activity or weaken NightGuard: accessibility settings, app permissions, usage-access permission, notification access, app info (uninstall/force-stop/clear data), developer options, factory reset, screen-lock changes, date & time, Do Not Disturb, device admin, "install unknown apps."
- **A browser's own settings** — separately from the Android Settings app, also logs when a browser's in-app settings are opened (clear browsing data, sync, site settings, incognito-tab locking, passwords), with a selfie on the ones that erase or hide browsing evidence.
- **Selfies on every trigger above** — not just unlock. A front-camera photo is taken whenever the phone is unlocked, whenever incognito is detected, whenever one of the sensitive settings screens above is opened, *and* if NightGuard's own Usage Access or Accessibility Service permission gets turned off (its self-defense watchdog, checked independently every ~15s so it isn't blind to its own components being disabled).
- **Location logging** — periodic GPS/network location, so there's a record of where the device was even if other evidence gets cleared.
- **Own-device connections** — Bluetooth devices this phone pairs with or connects to, Wi-Fi networks it joins (name included), and best-effort logging of when this phone's *own* hotspot/tethering turns on or off. (Deliberately does *not* scan for or log other nearby devices/networks the phone never connects to — that would be about detecting who else is nearby, a different and more invasive kind of monitoring than the rest of this app does. Also can't get a per-client device list for this phone's own hotspot: that requires a signature/system permission not available to a normal installed app, not something NightGuard can work around.)
- **Continuous browsing log, not just incognito** — a dedicated Browsing tab logs every page visited in a known browser, all the time, with entries visually highlighted when incognito was active at the time. This is scoped specifically to browser page titles/URLs, not a general on-screen text logger: reading everything rendered in every app would start capturing messages, banking info, and other people's texts to you, which is a fundamentally different (and much larger) kind of monitoring than "which websites did I visit." Password/secure-entry fields are never read by any text-scanning path in the app, on principle, regardless of what's being scanned for.
- **All timestamps in Central Time** — every screen and the report/backup exports display in America/Chicago time (correctly following CST/CDT rather than a fixed offset that would drift an hour off for half the year).
- **Manual voice memos** — a mic button in the timeline lets you record a note to yourself on purpose (e.g. "what I remember" right after a lapse). This is the only audio capture in the app, and it's never automatic: continuously recording ambient audio around triggers (the way selfies work) risks capturing other people's conversations without their consent, which is a materially bigger privacy/legal problem than a photo and wasn't added for that reason.
- **User/profile switches** — logs Android multi-user profile switches, if your device has more than one profile configured.

Everything is stored **locally on the device only** (Room database + AES-256-GCM encrypted photos/audio via Jetpack Security). Nothing is uploaded anywhere.

## Protecting the record itself

- **Encrypted backups outside app storage** — every ~6 hours (and on demand from the report screen), the full timeline plus encrypted photos/audio gets copied to `Downloads/NightGuard_Backups/` via MediaStore, which survives the app's own data being cleared from Settings (one of the ways someone could otherwise wipe the timeline). See the caveat in `BackupExporter.kt` about the encryption key's own survival, which varies by device and hasn't been verified.
- **PIN-gated pause** — during setup you set a recovery PIN while lucid. Pausing monitoring later (for a legitimate reason, e.g. a private appointment) requires that PIN; resuming early never does, since friction should apply to weakening protection, not restoring it. A pause always auto-expires (max 4 hours) — there's no way to leave it "paused" indefinitely. Tamper detection (see below) is never suppressed by a pause.
- **Escalated tamper alerts** — turning off NightGuard's Accessibility Service or Usage Access permission now posts a heads-up (not just a silent log entry) notification immediately, on top of the existing selfie + timeline entry.
- What this *can't* do: a normal Android app cannot prevent the OS Settings app from revoking its own permissions, force-stopping it, or uninstalling it — that would require enrolling the device under Android's Device Owner/MDM mode, which is a much bigger, more invasive step this app deliberately doesn't take. The mitigations above (backups outside app storage, immediate alerts, an audit trail of the attempt itself) are the realistic version of "hard to disable" within a normal user app.

## Episode markers

A button at the top of the timeline lets you (or whoever's with you) mark when an episode starts and ends, rather than leaving everything as one undifferentiated stream. This is the more clinically useful complement to passive logging: a doctor working from a bounded window ("this happened between 2:14 and 3:40 PM") correlated against what NightGuard already recorded in that window is more actionable than raw continuous logs. Episode markers are highlighted in both the timeline and the doctor report.

## Doctor-facing report

The report screen (top bar, timeline icon) generates a single self-contained HTML file — event log, photos embedded inline — for a date range you pick, and hands it to the share sheet so you can send it to a clinician or print it. Voice memos are noted in the report but not embedded; play them back in the app.

## Why this exists / how to use it responsibly

This is built for **monitoring your own device** — e.g. figuring out what's happening during memory lapses. A few things worth knowing before you rely on it:

- Android requires a persistent notification for any app doing background camera, location, or usage-stats work (as of Android 10+), so NightGuard cannot run fully invisibly — it shows low-priority "NightGuard is active" notifications while monitoring.
- If anyone besides you has legitimate/known access to this phone (partner, kid, roommate), consider whether they should know monitoring is in place — a lock-screen note or a conversation avoids ambiguity later, and in some places continuous photo capture of other people has legal wrinkles beyond just "it's my phone."
- Incognito detection works by reading on-screen text/UI nodes in browser apps (there's no public Android API for "this tab is private"), so it's a best-effort signal, not a guarantee — it'll need updating if a browser changes its private-mode UI.
- If you're experiencing memory lapses with evidence of deliberate concealment during them, that pattern is worth describing directly to a doctor — this app can hand a clinician evidence, it can't diagnose or explain what's happening on its own.

## Architecture

| Component | File | Purpose |
|---|---|---|
| `AppUsageMonitorService` | `service/` | Foreground service polling `UsageStatsManager` every 15s for foreground-app changes; also NightGuard's tamper watchdog |
| `LocationMonitorService` | `service/` | Foreground service logging periodic location via the framework `LocationManager` (no Play Services dependency) |
| `NightGuardAccessibilityService` | `service/` | Accessibility Service reading window content for incognito indicators, sensitive Settings screens, and in-browser settings screens |
| `UnlockCaptureService` | `capture/` | CameraX-based headless front-camera capture, triggered on unlock and every other flagged event |
| `VoiceMemoRecorder` | `capture/` | Manual, user-initiated audio recording (MediaRecorder) for voice memos |
| `UnlockReceiver` / `UserSwitchReceiver` / `BootReceiver` / `DeviceConnectionReceiver` | `receiver/` | React to `ACTION_USER_PRESENT`, profile switches, reboot, and Bluetooth/Wi-Fi connections |
| `TimelineRepository` + Room DB | `data/` | Single local event log (`timeline_events` table) all sources write to; honors the PIN-gated pause |
| `SecureImageStore` / `SecureAudioStore` | `util/` | Encrypt/decrypt photos and voice memos with Jetpack Security |
| `SecurePrefs` / `MonitoringState` | `util/` | Encrypted recovery-PIN storage and pause-state checks |
| `BackupExporter` / `BackupWorker` | `util/`, `work/` | Periodic + on-demand encrypted backup to MediaStore Downloads |
| `ReportExporter` | `util/` | Builds the doctor-facing HTML report |
| `SetupScreen` / `TimelineScreen` / `BrowsingScreen` / `PauseScreen` / `ReportScreen` | `ui/` | Compose UI: permission + PIN onboarding, the event timeline (view photos, play memos, record new ones), the always-on incognito-highlighted browsing log, the pause gate, and report/backup generation |

## Required permissions (all granted manually via the in-app Setup screen)

- **Usage access** (`PACKAGE_USAGE_STATS`) — special permission, granted via Settings, not a runtime dialog.
- **Accessibility service** — also granted via Settings; required for incognito/settings detection.
- **Camera** — for selfies.
- **Notifications** — required on Android 13+ to keep the foreground services running.
- **Ignore battery optimization** — recommended so Android doesn't kill the background monitors.
- **Location** (fine + background) — for location logging.
- **Nearby devices** (`BLUETOOTH_CONNECT`, Android 12+) — for Bluetooth connection logging.
- **Microphone** — requested only when you first tap the voice-memo record button, not during setup, since it's an optional manual feature.

The app walks you through granting the setup-gated permissions (and setting your recovery PIN) on first launch.

## Building

This repo was scaffolded without access to the Android SDK/emulator, so it hasn't been built or run — treat it as a solid starting point to open in Android Studio, not a verified build.

1. Open the project root in **Android Studio** (Koala/2024.1 or newer recommended).
2. Let Gradle sync — it will offer to generate the Gradle wrapper jar if missing (the wrapper jar binary isn't committed to this repo; `gradle/wrapper/gradle-wrapper.properties` pins Gradle 8.7).
3. Replace the placeholder vector icon (`app/src/main/res/drawable/ic_shield.xml`) with a real adaptive launcher icon via Android Studio's Image Asset tool if you want a proper app icon.
4. Build & run on a physical device (camera/location/usage-stats/accessibility/Bluetooth behavior can't be meaningfully tested on most emulators).
5. On first launch, walk through the Setup screen to grant all permissions and set a recovery PIN, then the Timeline screen starts filling in.

### Known things to double check once you can build

- Dependency versions (Compose BOM, AGP, Kotlin, Room, CameraX) were picked for mutual compatibility as of this writing but weren't verified against an actual Gradle sync — Android Studio's upgrade assistant will flag anything stale.
- `NightGuardAccessibilityService`'s incognito/browser/settings keyword lists will need tuning against the actual browsers and Settings UI you use.
- The Room schema version was bumped with `fallbackToDestructiveMigration()` rather than a real migration, since there's no shipped v1 install to preserve yet — replace this before it matters.
- `BackupExporter`'s assumption that the Jetpack Security master key (and therefore backup decryptability) survives "Clear data" hasn't been verified against a real device/Android version.
- Below API 29, backups/reports write to the legacy public Downloads directory via `WRITE_EXTERNAL_STORAGE` (maxSdkVersion 28); this path is less exercised than the MediaStore path used on 29+.

### A note on OEM-skinned Settings apps (Samsung One UI, etc.)

Sensitive-settings detection has two layers: matching the screen's internal fragment class name (works on stock-ish Android), and matching the on-screen title/heading text (works regardless of OEM skin, since it reads what's literally displayed). The title list in `SENSITIVE_TITLE_KEYWORDS` was written from general knowledge of Android/One UI screen names, not verified against a physical device — if a screen you'd expect to trigger a selfie doesn't, it's almost certainly a wording mismatch (e.g. Samsung titles a screen slightly differently than guessed) rather than a missing feature. Tell me the exact screen and I'll add the matching string. Samsung's Secure Folder gets special-cased since it's the most common way to hide apps/photos on a Galaxy phone.
