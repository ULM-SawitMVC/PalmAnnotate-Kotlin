# PalmAnnotate Native — Agent Guide

Native Kotlin + Jetpack Compose rewrite of PalmAnnotate (originally Capacitor WebView hybrid).

> **This file is the canonical agent guide for the native app.** Keep changes in this file.

## Build & Run

> Building locally is optional — every push to `master` produces a downloadable APK.
> See [CI — GitHub Actions](#ci--github-actions).

### Prerequisites

- **JDK 17** — `C:\tools\jdk17\jdk-17.0.19+10`
- **Android SDK** — `C:\tools\android-sdk`
- **Device:** Xiaomi Pad 8 (Android 16, wireless ADB `192.168.1.7:5555`)

### Build APK

```powershell
$env:JAVA_HOME = 'C:\tools\jdk17\jdk-17.0.19+10'
$env:ANDROID_HOME = 'C:\tools\android-sdk'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :app:assembleField --no-daemon --max-workers=4
```

Output: `app/build/outputs/apk/field/PalmAnnotate-field-v<version>.apk`
(for example `PalmAnnotate-field-v0.3.42.apk`). The version is part of the filename
(see [Versioning](#versioning)), so don't hardcode it; resolve the newest APK instead.

The field variant is not debuggable, keeps R8/resource shrinking disabled, and uses
`dev.sawitulm.palmannotate.field` so installing it cannot overwrite the existing debug
app's private dataset.

### Install & Launch

```powershell
$apk = Get-ChildItem 'app/build/outputs/apk/field/PalmAnnotate-field-v*.apk' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
& 'C:\tools\android-sdk\platform-tools\adb.exe' -s 192.168.1.7:5555 install -r $apk.FullName
& 'C:\tools\android-sdk\platform-tools\adb.exe' -s 192.168.1.7:5555 shell am force-stop dev.sawitulm.palmannotate.field
& 'C:\tools\android-sdk\platform-tools\adb.exe' -s 192.168.1.7:5555 shell monkey -p dev.sawitulm.palmannotate.field -c android.intent.category.LAUNCHER 1
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
│   ├── carousel/                ← CarouselScreen (PRIMARY annotation editor: swipe sides, draw/select/link, auto-save)
│   ├── viewer/                  ← DepthViewerScreen (jet colormap + tap-to-read)
│   ├── dedup/                   ← DeduplicationScreen (two-canvas pair review)
│   ├── results/                 ← ResultsScreen (summary + export + ZIP backup)
│   └── common/                  ← AnnotationCanvas (shared by carousel + dedup), AppHeader,
│                                   Dialogs, KeyboardShortcuts, ToastHost
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

**See:** `docs/PERF_GAIN.md` for full analysis.

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
See `docs/PERF_GAIN.md`. **Do not move the SAF mirror back onto the blocking save path.**

## Device Testing

### Xiaomi Pad 8 (Primary Test Device)

- **ADB:** Wireless at `192.168.1.7:5555`
- **Android:** 16
- **Package:** `dev.sawitulm.palmannotate.debug`
- **Screen:** 2880×1800 (landscape)
- **Notes:** R8 causes Orbbec preview freeze; keep minification OFF

### Xiaomi Pad 6 (Secondary Test Device)

- **ADB:** Wireless at `192.168.1.3:43451` (port is random — changes each time wireless debugging restarts; re-check the port in Developer options if connect fails)
- **Model:** `23043RP34G` (`device:pipa`)
- **Package:** `dev.sawitulm.palmannotate.debug`

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

## Versioning

PalmAnnotate uses **Semantic Versioning (SemVer)** with auto-increment:

```
versionName = "MAJOR.MINOR.COMMIT_COUNT"   e.g. "0.2.35"
versionCode = COMMIT_COUNT                  e.g. 35
```

- **PATCH** (`.35`) — auto, derived from `git rev-list --count HEAD`. Every commit increments it.
- **MAJOR.MINOR** (`0.2`) — manual, set in `app/build.gradle.kts` → `val majorMinor = "0.2"`.

### When to bump MAJOR vs MINOR

| Bump | When | Example |
|------|------|---------|
| **MINOR** (`0.2` → `0.3`) | New feature set is complete and usable: new screen, new workflow, significant UX improvement. Accumulate several small changes, then bump once when the feature set is "done". | Carousel editor done, depth viewer added, export pipeline complete |
| **MAJOR** (`0.x` → `1.0`) | App is production-ready: field-tested, stable, no known data-loss bugs, suitable for real annotation work. Also: breaking changes to data format or DB schema that require migration. | First field release, or v2.0 with new DB schema |

### Rules of thumb

1. **Don't bump MINOR for every commit.** Accumulate related changes, bump when a coherent feature set is done.
2. **PATCH is free** — it auto-increments, so you never think about it.
3. **Stay at `0.x` while in active development.** Bump to `1.0` only when the app is field-ready.
4. **Commit message convention** (optional but helpful):
   - `feat:` / `fix:` / `perf:` / `docs:` prefixes help when reviewing git log.

### How to bump

Edit `app/build.gradle.kts`:
```kotlin
val majorMinor = "0.3"   // ← change this
```
Then commit. The build will produce e.g. `PalmAnnotate-debug-v0.3.36.apk`.

## CI — GitHub Actions

Local builds are no longer the only way to get an APK. Two workflows live in `.github/workflows/`.

| Trigger | Android Build | Release |
|---------|---------------|---------|
| Push to `master` | ✅ APK as workflow artifact (30d) | ❌ |
| PR to `master` | ✅ | ❌ |
| Push tag `v*` | ❌ (branch-filtered) | ✅ tag + GitHub Release |
| Actions → Run workflow | ✅ | ✅ (derives the tag from the build) |

**Getting a CI APK:** Actions tab → pick the run → Artifacts. Or `gh run download`.
Release assets are the raw `.apk`; workflow artifacts are ZIP-wrapped by GitHub, so
the byte counts differ (~83 MB vs ~44 MB) for the *same* build. Install from a Release
to skip the unzip.

**Releases are deliberately manual.** `versionCode` increments on every commit, so
auto-releasing each push would bury the one build that was actually field-verified
under dozens of near-identical ones. Push daily → artifact; cut a Release only for a
build you intend to carry into the field.

### Invariants — do not break these

- **`fetch-depth: 0` in both checkouts is REQUIRED, not cosmetic.** `app/build.gradle.kts`
  derives `versionCode`/`versionName` from `git rev-list --count HEAD`. GitHub's default
  shallow clone (depth 1) makes that return `1` and ships a silently downgraded
  `v0.3.1` / `versionCode 1` APK.
- **CI builds both `field` and `debug`, not `release`.** Field is signed with the debug
  signing config but is not debuggable; it has a separate application id so it cannot
  overwrite the existing debug app's private dataset. R8 and resource shrinking remain off.
- **A runner-generated debug keystore is not a stable update identity.** Before distributing a
  field Release, either configure a persistent signing keystore in repository secrets or replace
  the CI asset with a locally built APK whose signer matches the APK validated on the device.
  Compare certificate SHA-256 digests with `apksigner verify --print-certs`; a mismatch forces
  uninstall and would remove that package's app-private data.
- **`release.yml` fails the run if a pushed tag disagrees with the built version.** That
  guard exists so a Release can never carry an APK whose internal version differs from
  its label. Don't relax it.
- **`git push --follow-tags` triggers both workflows** (branch push + tag push) — correct
  output, one wasted build. Push commit and tag separately.

### CI does NOT replace on-device verification

A green CI run means it compiles and the unit tests pass. It says **nothing** about the
Orbbec live depth preview, which no runner can exercise. Every APK still needs the
on-device checklist before field use. See the 0% error tolerance block below.

## Working Rules

> ### ⛔ 0% ERROR TOLERANCE — 0% BUG (read before every code change)
>
> The operator has **no chance to re-test with the Orbbec camera before the day of dataset
> collection** — the first time the app is opened again in the field *is* the collection day. There
> is no room for error.
>
> - **Never introduce a bug.** Every edit must be additive, minimal, and isolated.
> - **Check ALL related code** — every caller, every file/DB reader, every test — before saying
>   "done". Not just the changed lines.
> - **Finish the WHOLE fix** before reporting completion. No partial work.
> - **Orbbec runtime is untestable here.** Any new SDK call must be wrapped in `try/catch`,
>   **fail-safe** (a failure degrades to today's behavior — never crashes capture/preview, never
>   writes corrupt data) and **isolated** (easy to roll back). Do **not** touch the live
>   preview / frame-callback path.
> - **Be honest about verification limits.** State plainly what is code-verified (compile + unit
>   tests + caller trace) vs. what still needs the on-device checklist in `docs/AUDIT_RGBD_DEPTH.md` §8.

1. **Don't overestimate.** State what is verified vs. assumed. If a change can only be confirmed on the device, say so.
2. **Test on device.** UI changes must be verified on the physical device, not just in code.
3. **Log first, optimize second.** Add performance logging before making optimization changes.
4. **Read existing code first.** Understand the current implementation before changing it.
5. **Small changes.** Make one change at a time, test it, then proceed.
6. **Preserve data integrity.** DB transactions must be atomic; never leave partial state.

## Related Documentation

| File | Content |
|------|---------|
| `docs/MIGRATION_STATUS.md` | Migration progress (Done / Partial / Missing) |
| `docs/PERF_GAIN.md` | Dedup performance optimization analysis |
| `.github/workflows/` | CI: `android-build.yml` (APK per push) + `release.yml` (tagged Release) |
| `HANDOFF.md` | Session handoff notes |
| `README.md` | Project overview |
