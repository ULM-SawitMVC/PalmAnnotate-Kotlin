# PalmAnnotate Native

Rewrite dari PalmAnnotate (Capacitor WebView hybrid) ke **native Kotlin + Jetpack Compose**.

> **Status lengkap ada di [`MIGRATION_STATUS.md`](MIGRATION_STATUS.md).**

## Struktur Project

```
app/src/main/java/dev/sawitulm/palmannotate/
├── PalmAnnotateApp.kt              ← Hilt @HiltAndroidApp Application
├── MainActivity.kt                 ← Compose entry point (@AndroidEntryPoint)
├── di/
│   └── AppModule.kt                ← Hilt DI module (singleton bindings)
├── domain/
│   ├── model/                      ← Data classes (Bbox, ActiveSession, TreeSide, etc.)
│   ├── dedup/                      ← UnionFind + SuggestionEngine (real algorithm)
│   ├── results/                    ← ResultsComputer (clusters, linkedCount, class counts)
│   ├── quality/                    ← QualityCheck (capture QA validation)
│   ├── usecase/                    ← SessionUseCases (bbox CRUD, link mgmt, mismatch resolve)
│   └── util/                       ← DepthUtil, ColorUtil, OperationQueue
├── data/
│   ├── db/                         ← Room entities + DAOs + PalmAnnotateDatabase
│   ├── storage/                    ← SessionRepository, AndroidStorageManager, SafMirrorStore,
│   │                                 ExportFolderRepository, FolderResumeImporter, InputCache
│   ├── yolo/                       ← YoloParser (parse/serialize YOLO labels)
│   ├── detection/                  ← OnnxDetector (ONNX Runtime inference + NMS)
│   ├── camera/                     ← OrbbecManager (Orbbec USB depth camera)
│   ├── location/                   ← GpsProvider (background GPS)
│   └── export/                     ← ExportManager (Output JSON / YOLO / CSV / Identity)
├── ui/
│   ├── theme/                      ← Material 3 theming (PalmColors, OnMediaColors)
│   ├── navigation/                 ← NavHost + routes
│   ├── home/                       ← HomeScreen + HomeViewModel
│   ├── session/                    ← SessionDetailScreen
│   ├── capture/                    ← CaptureFlowScreen (CameraX + Orbbec toggle)
│   ├── annotation/                 ← AnnotationScreen (canvas + tools + detect + carousel entry)
│   ├── viewer/                     ← DepthViewerScreen (jet colormap + tap-to-read)
│   ├── carousel/                   ← CarouselScreen (fullscreen swipe viewer)
│   ├── dedup/                      ← DeduplicationScreen (two-canvas pair review)
│   ├── results/                    ← ResultsScreen (summary + export + QA gate)
│   └── common/                     ← AnnotationCanvas, AppHeader, Dialogs,
│                                      KeyboardShortcuts, ToastHost
└── app/src/test/                   ← Unit tests (39 tests: DomainTests + FolderResumeTests)
```

## Sudah Dibuat ✅

| Modul | File |
|---|---|
| Gradle build system | `build.gradle.kts`, `libs.versions.toml` |
| Domain models | `AnnotationClass`, `Bbox`, `TreeSide`, `CrossSideLink`, `ActiveSession`, `Results`, `OutputSchema` |
| Union-Find | `UnionFind.kt` (path compression + union by rank) |
| Suggestion engine | `SuggestionEngine.kt` (real algorithm: seam-band, size-ratio, weighted score, mutual-best) |
| YOLO parser | `YoloParser.kt` (parse/serialize round-trip, clamp, 6-dp) |
| Results computer | `ResultsComputer.kt` (clusters, linkedCount, class counts, "other" bucket) |
| Quality check | `QualityCheck.kt` (capture QA validation) |
| Session use cases | `SessionUseCases.kt` (bbox CRUD, link mgmt, mismatch detect/resolve) |
| Operation queue | `OperationQueue.kt` (serialized saves) |
| Room database | `Entities.kt`, `PalmAnnotateDatabase.kt` (v2, cascade delete) |
| Storage layer | `AndroidStorageManager`, `SafMirrorStore`, `ExportFolderRepository`, `FolderResumeImporter`, `InputCache` |
| Session repository | `SessionRepository` (Room + filesystem + SAF mirror) |
| ONNX detector | `OnnxDetector` (letterbox, single-class UNASSIGNED, class-agnostic NMS) |
| GPS provider | `GpsProvider` (background location) |
| Export manager | `ExportManager` (Output JSON v4 / YOLO / CSV / Identity) |
| Orbbec camera | `OrbbecManager` (USB RGB-D, D2C alignment, jet colormap preview) |
| UI: Home | Session list, create, delete, export folder picker |
| UI: Session Detail | Tree list, annotate/carousel entry |
| UI: Capture Flow | CameraX + Orbbec toggle, GPS, QA gate, review-all retake |
| UI: Annotation | Canvas, bbox tools, detect button, carousel entry, OperationQueue saves |
| UI: Carousel | HorizontalPager, review/edit mode, link arm, detect button |
| UI: Dedup | Two-canvas seam-anchored surface, suggestion chips, pair navigation |
| UI: Depth Viewer | Jet colormap, tap-to-read, valueScale from sidecar |
| UI: Results | Summary + export buttons + QA gate dialog |
| Theming | Material 3 dark/light + PalmColors + OnMediaColors |
| Navigation | Compose Navigation with all routes |
| DI | Hilt modules |
| Unit tests | 39 tests (DomainTests + FolderResumeTests) |
| ONNX model | `ffb-detector.onnx` + `detector.config.json` |
| Orbbec SDK | `obsensor_v2.0.6_2026031801_release.aar` |

## Build

```powershell
# Set environment
$env:JAVA_HOME = 'C:\tools\jdk17\jdk-17.0.19+10'
$env:ANDROID_HOME = 'C:\tools\android-sdk'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# Build
.\gradlew.bat :app:assembleDebug --no-daemon --max-workers=4

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Test
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

## Architecture

```
MVVM + Clean Architecture
UI (Compose) → ViewModel → UseCase → Repository → Room + Filesystem + SAF
                                          ↓
                                    CameraX / Orbbec SDK / ONNX Runtime
```
