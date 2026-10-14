# What was tested, and what wasn't

Short version: the engine, the expression language, the editor's data model and the whole UI were
tested here. Everything compiles against the real Android 14 API. **No APK was built and nothing
ran on an Android device or emulator**, because this machine can't reach Google's Maven server
(`dl.google.com`), which hosts the Android Gradle plugin, the Android SDK and the androidx libraries.

## Tested (all passing)

### Engine and model: 29 unit tests, `core/` (`./gradlew :core:test`)
- **Engine (12)**: the Dice template runs to the end; if/else branches; waits follow the clock;
  5 flows running at once; two "Flow start" blocks side by side in one flow; pause and resume;
  stop; errors either fail the run or follow the "Failed" arrow; a missing start block is reported;
  an endless loop is throttled and can still be stopped; a flow waiting for a time is saved
  ("hibernated") and resumes mid-wait with its variables; flows waiting on events never hibernate.
- **Expressions (7)**: arithmetic and precedence, conditions, text vs number `+`, all functions,
  unknown variables, readable error messages, `{…}` templates.
- **Model (10)**: edits, copy/paste with internal arrows, undo/redo with coalescing, export/import
  round trip, comments, all 10 templates valid, validator, permissions per block, catalog
  consistency, next-run time for schedules.

### UI: 6 tests on Compose Desktop (`tools/verify`, `:ui-run`)
The real screen code (everything under `ui/` except `ui/android`) was rendered at the Galaxy Tab
A11's size (about 1007×601 dp landscape) and in portrait, in light and dark. Screenshots are in
`docs/screenshots/`. Simulated touch tests:
- searching the palette for "toast" and adding the block, which gets wired under the selected block;
- dragging a block on the canvas, then undo and redo;
- drawing an arrow by dragging from an output port onto another block.

### Compiles against the real Android API
- `runtime/` (service, receivers, alarms, event sources, all 34 block executors, permissions) and
  `data/Prefs.kt` compile against Android 14's API (Robolectric's `android-all` jar) with no stubs.
- **The whole app** (`app/src/main/java`, every file) also compiles in one go against Android 14 +
  Compose 1.7.3, with small hand-written signature stubs for activity, lifecycle, Room and WorkManager
  (`tools/verify/app-check/stubs`). Those stubs were written from memory of the real APIs, so this
  catches mistakes in the app's own code, not every mismatch with those four libraries.
- The log-pruning SQL was checked in SQLite.
- The Gradle wrapper (8.11.1) works: it downloads Gradle and fails exactly at the Android plugin
  (`com.android.application:8.7.3` not found, since Google's Maven is unreachable).

## Not tested

- **Building the APK.** Not done: AGP, `aapt2`, R8, Room's code generator (KSP) and resource
  merging never ran. The manifest and XML resources were written by hand and not validated by the
  Android build. The first real build (GitHub Actions or a PC) is the first time these run; small
  fixes may be needed there.
- **Anything on a device**: the foreground service, notifications, alarms and hibernation in real
  Doze, boot restart, all trigger listeners (battery, charging, Wi-Fi, Bluetooth, app opened via
  usage stats, notification listener, screen, headphones), all actions (flashlight, volume,
  brightness, TTS, clipboard, files via the folder picker, HTTP, share, open app/URL), the dialog
  block over other apps, permission prompts and settings screens, dynamic colors, import/export
  through the system file picker, Room storage on a device.
- **Real touch on a tablet**: pinch zoom, multi-finger gestures and long-press drag from the
  palette were only simulated on desktop.
- **Battery and memory use** on the Galaxy Tab A11.
- **Samsung-specific behavior**: One UI may still put the app to sleep; if flows stop in the
  background, set AutoFlow's battery use to "Unrestricted" (Settings in the app links there).

## How the off-device checks run

```
cd tools/verify
gradle :core:test :ui-run:test          # unit tests + UI render/touch tests
gradle :android-check:compileKotlin     # runtime vs Android 14 API
gradle :app-check:compileKotlin         # whole app vs Android 14 + Compose + stubs
```
These only need Maven Central, which is why they could run here.
