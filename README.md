# Datapack

Always-on Android recorder. It listens all day, saves a WAV "batch" only while there is
sound (speech-level energy), shows every batch as a horizontal bar labelled with the real
start and end time, and lets you pick a point on a bar and play from there.

## Build & install (Android Studio)

1. Open Android Studio (Ladybug or newer) → **Open** → choose this `Datapack` folder.
2. Let Gradle sync (it downloads the Android Gradle Plugin 8.7.3, Kotlin 2.0.21 and Compose).
3. Plug in the phone with USB debugging on, pick it in the device dropdown, press **Run ▶**.
   Or: **Build → Build Bundle(s) / APK(s) → Build APK(s)** and copy
   `app/build/outputs/apk/debug/app-debug.apk` to the phone.

Command line: `gradlew.bat assembleDebug` (Windows) or `./gradlew assembleDebug`.

## First launch on the phone

The app asks for the things it needs to never stop listening:

| Prompt | Why |
| --- | --- |
| Microphone | Obvious. Recording cannot start without it. |
| Notifications (Android 13+) | A foreground service must show a persistent "Listening" notification. |
| **Display over other apps** | Android 11+ silently *mutes* the microphone for a service that was started in the background — which is exactly what happens right after a reboot. Apps holding this permission are exempt, so granting it is what makes "start listening automatically after power-on" actually work. |
| Unrestricted battery | Stops the OS from killing the service to save power. |

The header turns green ("Listening") when everything is set. If Android ever mutes the mic
(red "Microphone muted by Android"), opening the app and tapping **Fix** restarts capture
with full access.

## How it works

- `RecordingService` – foreground service (`foregroundServiceType="microphone"`), `START_STICKY`,
  holds a partial wake lock. Reads 16 kHz mono PCM in 20 ms frames, computes RMS, keeps an
  adaptive noise floor. ≥80 ms above `max(3×floor, 350)` opens a batch (with 0.5 s of pre-roll
  so the first word is kept); 2 s of quiet closes it and trims the tail to 0.5 s after the last
  sound. Batches longer than 15 min are split. Files older than **3.5 days** are deleted hourly.
- `BootReceiver` – restarts the service on `BOOT_COMPLETED`, after app updates, and from a
  5-second keep-alive alarm the service sets whenever it is destroyed.
- `BatchStore` – batches are just files named `<startMillis>_<endMillis>.wav` in app-private
  storage; the name is the timestamp metadata. In-progress files (`<start>_rec.wav`) left over
  from a crash are repaired on the next start.
- `MainViewModel` / `Player` – one `MediaPlayer`; selection, handle position and playback state.
- `ui/MainScreen.kt` – Compose UI: date headers (newest first), one bar per batch, single
  **Play** / **Stop** buttons.

### UI rules (as specified)

- Left edge of a bar = batch start time, right edge = end time; bars are newest-on-top.
- Tap a bar → it becomes the selected bar and the handle resets to the start.
- Only the selected bar accepts a horizontal drag; dragging immediately stops playback and
  moves the handle. The label under the bar shows the real clock time the handle points at.
- **Play** plays the selected bar from the handle. **Stop** stops playback only — recording
  is never affected. Long-press a bar to delete it.

## Tuning

Constants at the bottom of `RecordingService.kt`: `MIN_THRESHOLD` (sensitivity),
`END_QUIET_FRAMES` (how long a pause ends a batch), `MAX_BATCH_MS`, and `BatchStore.RETENTION_MS`.

## Note on `targetSdk = 33`

This is deliberate. From API 34 Android forbids starting a *microphone* foreground service
from the boot broadcast. Targeting 33 keeps boot-start working on every Android version
while the app still installs fine on Android 14/15 (sideload). If you later publish on the
Play Store you'll need to raise it and move boot-start behind a user tap.
