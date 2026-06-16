# PalmAnnotate Native — Migrasi

Rewrite dari PalmAnnotate (Capacitor WebView hybrid) ke **native Kotlin + Jetpack Compose**.

> ⚠️ **Status sebenarnya ada di [`MIGRATION_STATUS.md`](MIGRATION_STATUS.md)** (audit
> 2026-06-15). Tabel "Sudah Dibuat ✅" di bawah ini **terlalu optimistis** — beberapa
> modul yang ditandai ✅ ternyata salah/placeholder (mis. `SuggestionEngine` dulu cuma
> IoU, `OnnxDetector` stretch+assign-class, Output JSON belum byte-compatible) dan sudah
> diperbaiki di sesi audit; fitur besar (model sesi multi-tree, Orbbec, carousel, depth
> viewer, wiring save-lifecycle) **belum** selesai. Baca `MIGRATION_STATUS.md` untuk
> daftar Done / Partial / Missing yang jujur dan urutan kerja berikutnya.

## Struktur Project

```
Migrasi/
├── app/src/main/java/dev/sawitulm/palmannotate/
│   ├── PalmAnnotateApp.kt          ← Hilt Application
│   ├── MainActivity.kt             ← Compose entry point
│   ├── di/AppModule.kt             ← Hilt DI module
│   ├── domain/
│   │   ├── model/                   ← Data models (Bbox, ActiveSession, dll)
│   │   ├── dedup/                   ← UnionFind + SuggestionEngine
│   │   ├── results/                 ← ResultsComputer
│   │   └── quality/                 ← (placeholder)
│   ├── data/
│   │   ├── db/                      ← Room entities + DAOs
│   │   ├── storage/                 ← AndroidStorageManager, SafMirror, SessionRepo
│   │   ├── yolo/                    ← YoloParser (parse/serialize)
│   │   ├── detection/               ← OnnxDetector (native inference)
│   │   ├── camera/                  ← (placeholder: CameraX + Orbbec)
│   │   └── export/                  ← (placeholder)
│   └── ui/
│       ├── theme/                   ← Material 3 theming (PalmColors)
│       ├── navigation/              ← NavHost + routes
│       ├── home/                    ← HomeScreen + HomeViewModel
│       ├── session/                 ← SessionDetailScreen
│       ├── capture/                 ← CaptureFlowScreen (CameraX)
│       ├── annotation/              ← AnnotationScreen (canvas + tools)
│       ├── results/                 ← ResultsScreen
│       └── common/                  ← AnnotationCanvas, Dialogs
├── app/src/test/                    ← Unit tests (domain logic)
├── app/src/main/assets/models/      ← ONNX model + config
├── app/libs/                        ← Orbbec SDK AAR (to be copied)
├── gradle/libs.versions.toml        ← Version catalog
└── build.gradle.kts                 ← Root + app build files
```

## Sudah Dibuat ✅

| Modul | Status | File |
|---|---|---|
| Gradle build system | ✅ | `build.gradle.kts`, `libs.versions.toml` |
| Domain models | ✅ | `AnnotationClass`, `Bbox`, `TreeSide`, `CrossSideLink`, `ActiveSession`, `Results` |
| Union-Find | ✅ | `UnionFind.kt` (path compression + union by rank) |
| Suggestion engine | ✅ | `SuggestionEngine.kt` (IoU + cross-side pairs) |
| YOLO parser | ✅ | `YoloParser.kt` (parse/serialize round-trip) |
| Results computer | ✅ | `ResultsComputer.kt` (clusters + counts) |
| Room database | ✅ | Entities, DAOs, Database class |
| Storage layer | ✅ | `AndroidStorageManager`, `SafMirrorStore` |
| Session repository | ✅ | `SessionRepository` (Room + filesystem + SAF) |
| ONNX detector | ✅ | `OnnxDetector` (native inference + NMS) |
| UI: Home | ✅ | Session list, create, delete |
| UI: Session Detail | ✅ | Side list, annotate/results navigation |
| UI: Capture Flow | ✅ | CameraX preview, capture, metadata form |
| UI: Annotation | ✅ | Canvas, bbox tools, class picker, side nav |
| UI: Results | ✅ | Counts, per-class, per-side, export |
| Theming | ✅ | Material 3 dark/light + PalmColors tokens |
| Navigation | ✅ | Compose Navigation with all routes |
| DI | ✅ | Hilt modules |
| Unit tests | ✅ | 20+ tests covering all domain logic |
| ONNX model | ✅ | `ffb-detector.onnx` copied |

## Belum Dibuat / Next Steps

| Modul | Priority | Notes |
|---|---|---|
| Copy Orbbec SDK AAR | HIGH | `cp android/app/libs/*.aar Migrasi/app/libs/` |
| Orbbec native integration | HIGH | Extract from `OrbbecPlugin.kt` → `OrbbecNativeManager` |
| Image dimension loading | MEDIUM | Load actual w/h when loading session sides |
| Autosave | MEDIUM | Debounced auto-save on mutation |
| Dedup UI screen | MEDIUM | Side-by-side canvas for linking |
| Carousel screen | MEDIUM | Swipe-based phone annotation |
| Depth viewer | LOW | Depth sidecar visualization |
| Quality check | LOW | Annotation validation rules |
| CSV export | LOW | Flat bunch export |
| Identity JSON export | LOW | Per-box identity export |
| Sessions index (JSON) | MEDIUM | Portable `sessions.json` for SAF |
| Splash screen | LOW | Brand launch screen |
| ProGuard testing | LOW | Verify R8 + Orbbec keep rules on device |

## Build

```bash
cd Migrasi

# Copy Orbbec AAR (if not yet done)
cp ../android/app/libs/obsensor_*.aar app/libs/

# Set JAVA_HOME — on THIS machine the C:\tools\jdk17 path in ../CLAUDE.md does NOT
# exist; use the Android Studio bundled JBR instead (PowerShell):
#   $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
#   $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
# (Android SDK is the real Studio SDK; see Migrasi/local.properties.)

# Build
./gradlew assembleDebug

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Tests
./gradlew test
```

## Architecture

```
MVVM + Clean Architecture
UI (Compose) → ViewModel → UseCase → Repository → Room + Filesystem + SAF
                                          ↓
                                    CameraX / Orbbec SDK / ONNX Runtime
```
