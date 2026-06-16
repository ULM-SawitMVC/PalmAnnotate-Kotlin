# PalmAnnotate Native — Agent Guide

Native Kotlin + Jetpack Compose rewrite of PalmAnnotate (originally Capacitor WebView hybrid).

> **This file is the canonical agent guide for the native app.** Keep changes in this file.

## Build & Run

### Prerequisites

- **JDK 17** — `C:\tools\jdk17\jdk-17.0.19+10`
- **Android SDK** — `C:\tools\android-sdk`
- **Device:** Xiaomi Pad 8 (Android 16, wireless ADB `192.168.1.7:5555`)

### Build APK

```powershell
$env:JAVA_HOME = 'C:\tools\jdk17\jdk-17.0.19+10'
$env:ANDROID_HOME = 'C:\tools\android-sdk'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :app:assembleDebug --no-daemon --max-workers=4
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### Install & Launch

```powershell
& 'C:\tools\android-sdk\platform-tools\adb.exe' -s 192.168.1.7:5555 install -r 'app/build/outputs/apk/debug/app-debug.apk'
& 'C:\tools\android-sdk\platform-tools\adb.exe' -s 192.168.1.7:5555 shell am force-stop dev.sawitulm.palmannotate.debug
& 'C:\tools\android-sdk\platform-tools\adb.exe' -s 192.168.1.7:5555 shell monkey -p dev.sawitulm.palmannotate.debug -c android.intent.category.LAUNCHER 1
```

### Run Tests

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

## R8 Minification — DO NOT ENABLE

**R8 code minification + resource shrinking are DISABLED (deliberately).**

When R8 was enabled (`minifyEnabled true`), the Orbbec live preview showed "one frame then freeze / laggy". R8 strips/optimizes something the Orbbec SDK reaches via its JNI/reflection frame-callback path. The keep rules in `proguard-rules.pro` don't cover this.

**Do NOT re-enable `minifyEnabled`/`shrinkResources` without re-verifying the live Orbbec preview on a physical device.**

## Architecture

```
app/src/main/java/dev/sawitulm/palmannotate/
├── PalmAnnotateApp.kt          ← Hilt @HiltAndroidApp Application
├── MainActivity.kt             ← Compose entry point (@AndroidEntryPoint)
├── di/AppModule.kt             ← Hilt DI module (singleton bindings)
├── domain/
│   ├── model/                   ← Data classes (Bbox, ActiveSession, TreeSide, etc.)
│   ├── dedup/                   ← UnionFind + SuggestionEngine
│   ├── results/                 ← ResultsComputer
│   ├── quality/                 ← QualityCheck (capture QA validation)
│   ├── usecase/                 ← SessionUseCases (bbox CRUD, link mgmt, mismatch resolve)
│   └── util/                    ← DepthUtil, ColorUtil, OperationQueue
├── data/
│   ├── db/                      ← Room entities + DAOs + PalmAnnotateDatabase
│   ├── storage/                 ← SessionRepository, AndroidStorageManager, SafMirrorStore,
│   │                              ExportFolderRepository, FolderResumeImporter, InputCache
│   ├── yolo/                    ← YoloParser (parse/serialize YOLO labels)
│   ├── detection/               ← OnnxDetector (native ONNX Runtime inference)
│   ├── camera/                  ← OrbbecManager (Orbbec USB depth camera)
│   ├── location/                ← GpsProvider (background GPS)
│   └── export/                  ← ExportManager (Output JSON / YOLO / CSV / Identity)
├── ui/
│   ├── theme/                   ← Material 3 theming (PalmColors, OnMediaColors)
│   ├── navigation/              ← NavHost + routes
│   ├── home/                    ← HomeScreen + HomeViewModel
│   ├── session/                 ← SessionDetailScreen
│   ├── capture/                 ← CaptureFlowScreen (CameraX + Orbbec toggle)
│   ├── annotation/              ← AnnotationScreen (canvas + tools + detect + carousel entry)
│   ├── viewer/                  ← DepthViewerScreen (depth colormap + tap-to-read)
│   ├── carousel/                ← CarouselScreen (fullscreen swipe viewer)
│   ├── dedup/                   ← DeduplicationScreen (two-canvas pair review)
│   ├── results/                 ← ResultsScreen (summary + export)
│   └── common/                  ← AnnotationCanvas, AppHeader, Dialogs,
│                                   KeyboardShortcuts, ToastHost
└── app/src/test/                ← Unit tests (DomainTests + FolderResumeTests)
```

### Key Patterns

- **DI:** Hilt (`@HiltAndroidApp`, `@AndroidEntryPoint`, `@Module`, `@Provides`)
- **DB:** Room (entities, DAOs, migrations, `@Database`)
- **UI:** Jetpack Compose (Material 3, `NavHost`, `remember`, `LaunchedEffect`)
- **ViewModels:** `@HiltViewModel`, `viewModel()`, `viewModelScope.launch`
- **Concurrency:** `Dispatchers.IO` for file ops, `Dispatchers.Default` for compute
- **Navigation:** `NavHost` with route strings, `navController.navigate()`
- **Image loading:** `BitmapFactory` with downsampling, LRU `BitmapCache` (8 entries)

## Key Technical Decisions

### Depth Viewer (Jet Colormap)

The depth viewer uses the **jet colormap** (blue→cyan→green→yellow→red), matching the web app and Orbbec live preview.

**Formula:** `clampUnit(1.5 - |4t - n|)` where `n=1` (Blue), `n=2` (Green), `n=3` (Red)

**Range:** P2–P98 percentiles of the depth data (no padding, no clamping). This matches the web app's `_range(u16, scale)` function.

**Value scale:** Read from JSON sidecar (`valueScale` field). Applied as `pixelValue * valueScale` before colormap.

### Dedup Performance (saveDbOnly)

The Dedup button originally called `saveAndAwait()` which waited for `writeSideArtifacts()` (YOLO labels + SAF image mirror) — **12 seconds**. Fixed by creating `saveDbOnly()` that only runs the DB transaction (**13ms**).

**See:** `PERF_GAIN.md` for full analysis.

### Tap-to-Read Depth

`DepthViewerScreen` has a `pointerInput` modifier that converts screen taps to depth pixel coordinates using `ContentScale.Fit` math. Shows depth in mm via a floating popup.

## Performance Logging

Filter `adb logcat` with:

```bash
adb logcat | grep -E "DedupPerf|CanvasPerf|SessionRepo|DepthViewer"
```

| Tag | Component |
|-----|-----------|
| `SavePerf` | **User-felt** save latency (tap → busy-overlay clears). Log lives at the wait the user sees, not inside the repo — the DB was 10ms yet the user waited 12s. |
| `DedupPerf` | DeduplicationScreen composable + ViewModel |
| `CanvasPerf` | AnnotationCanvas image loading |
| `SessionRepo` | SessionRepository: DB txn, `writeLocalArtifacts` (sync, truth), `mirrorSafArtifacts` (background) |
| `DepthViewer` | Depth viewer loading + tap-to-read |

### Save path (important)

`saveSession` writes the **DB + local label/annot-log synchronously** (the source of
truth, ~15ms) and fires the **SAF mirror on a background `safScope`** (best-effort,
never awaited). SAF was the entire ~11.6s "save feels slow" cost. `SafMirrorStore`
caches directory handles + child listings and overwrites files in place (no
delete+create), and infers MIME from the extension (a `.txt` written as
`application/json` was being saved as `.txt.json` and spawning `(N)` duplicates).
See `PERF_GAIN.md`. **Do not move the SAF mirror back onto the blocking save path.**

## Device Testing

### Xiaomi Pad 8 (Primary Test Device)

- **ADB:** Wireless at `192.168.1.7:5555`
- **Android:** 16
- **Package:** `dev.sawitulm.palmannotate.debug`
- **Screen:** 2880×1800 (landscape)
- **Notes:** R8 causes Orbbec preview freeze; keep minification OFF

### ADB Commands

```powershell
# Connect
& 'C:\tools\android-sdk\platform-tools\adb.exe' connect 192.168.1.7:5555

# Check connection
& 'C:\tools\android-sdk\platform-tools\adb.exe' -s 192.168.1.7:5555 devices

# View logs
& 'C:\tools\android-sdk\platform-tools\adb.exe' -s 192.168.1.7:5555 logcat | Select-String -Pattern "DedupPerf|CanvasPerf"

# Clear logs
& 'C:\tools\android-sdk\platform-tools\adb.exe' -s 192.168.1.7:5555 shell logcat -c

# Force stop
& 'C:\tools\android-sdk\platform-tools\adb.exe' -s 192.168.1.7:5555 shell am force-stop dev.sawitulm.palmannotate.debug

# Take screenshot
& 'C:\tools\android-sdk\platform-tools\adb.exe' -s 192.168.1.7:5555 shell screencap -p /sdcard/screenshot.png
& 'C:\tools\android-sdk\platform-tools\adb.exe' -s 192.168.1.7:5555 pull /sdcard/screenshot.png .
```

## Working Rules

1. **Don't overestimate.** State what is verified vs. assumed. If a change can only be confirmed on the device, say so.
2. **Test on device.** UI changes must be verified on the physical device, not just in code.
3. **Log first, optimize second.** Add performance logging before making optimization changes.
4. **Read existing code first.** Understand the current implementation before changing it.
5. **Small changes.** Make one change at a time, test it, then proceed.
6. **Preserve data integrity.** DB transactions must be atomic; never leave partial state.

## Related Documentation

| File | Content |
|------|---------|
| `MIGRATION_STATUS.md` | Migration progress (Done / Partial / Missing) |
| `PERF_GAIN.md` | Dedup performance optimization analysis |
| `HANDOFF.md` | Session handoff notes |
| `README.md` | Project overview |
