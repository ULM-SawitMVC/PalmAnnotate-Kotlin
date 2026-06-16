# PalmAnnotate Native

Rewrite dari PalmAnnotate (Capacitor WebView hybrid) ke **native Kotlin + Jetpack Compose**.

> **Status migrasi ada di [`docs/MIGRATION_STATUS.md`](docs/MIGRATION_STATUS.md).**

## License

[GPL 3.0](LICENSE) — Copyright 2026 ULM DS Lab

## Struktur Project

```
app/src/main/java/dev/sawitulm/palmannotate/
├── PalmAnnotateApp.kt              ← Hilt @HiltAndroidApp Application
├── MainActivity.kt                 ← Compose entry point (@AndroidEntryPoint)
├── di/
│   └── AppModule.kt                ← Hilt DI module (singleton bindings)
├── domain/
│   ├── model/                      ← Data classes (Bbox, ActiveSession, TreeSide, etc.)
│   ├── dedup/                      ← UnionFind + SuggestionEngine
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
│   ├── carousel/                   ← CarouselScreen (primary annotation editor)
│   ├── viewer/                     ← DepthViewerScreen (jet colormap + tap-to-read)
│   ├── dedup/                      ← DeduplicationScreen (two-canvas pair review)
│   ├── results/                    ← ResultsScreen (summary + export + QA gate)
│   └── common/                     ← AnnotationCanvas, AppHeader, Dialogs,
│                                      KeyboardShortcuts, ToastHost
└── app/src/test/                   ← Unit tests (DomainTests + FolderResumeTests)
```

## Fitur Utama

| Fitur | Deskripsi |
|-------|-----------|
| **Carousel Editor** | Primary annotation editor — swipe antar side, bbox draw/select/delete, class assignment (B1–B4), auto-save silent |
| **Cross-Side Linking** | Link bbox antar side (select → Link → swipe → tap matching bunch) |
| **Link Groups** | Badge angka di linked bbox, nomor grup konsisten antar side |
| **ONNX Detection** | Deteksi bbox otomatis dengan ONNX Runtime + NMS |
| **Deduplication** | Two-canvas pair review, suggestion engine (seam-band, size-ratio, weighted score) |
| **Depth Viewer** | Jet colormap, tap-to-read depth, valueScale dari sidecar |
| **Export** | Output JSON v4, YOLO labels, CSV, Identity |
| **CameraX + Orbbec** | Capture foto + depth, D2C alignment, GPS |
| **Auto-Save** | Silent save saat toggle mode, swipe side, atau back |
| **Resume** | Folder scan resume dari Output JSON |

## Dependencies

| Library | License | Source |
|---------|---------|--------|
| AndroidX, Compose, CameraX, Room, Hilt | Apache 2.0 | Google Maven |
| ONNX Runtime Android | MIT | Microsoft |
| Orbbec SDK Android Wrapper | Apache 2.0 | [GitHub](https://github.com/orbbec/OrbbecSDK-Android-Wrapper) |
| Orbbec SDK v2 | MIT | [GitHub](https://github.com/orbbec/OrbbecSDK_v2) |

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

## Dokumentasi

- [`docs/MIGRATION_STATUS.md`](docs/MIGRATION_STATUS.md) — Status migrasi dari web app
- [`docs/PERF_GAIN.md`](docs/PERF_GAIN.md) — Analisis optimasi performa
