# Technical contracts

Read the relevant section before changing a shared data or UI path. Current verification status is in [HANDOFF.md](../HANDOFF.md).

## Architecture

```
app/src/main/java/dev/sawitulm/palmannotate/
├── PalmAnnotateApp.kt          ← Hilt @HiltAndroidApp Application
├── MainActivity.kt             ← Compose entry point (@AndroidEntryPoint)
├── di/AppModule.kt             ← Hilt DI module (singleton bindings)
├── domain/
│   ├── model/                   ← Data classes (Bbox, ActiveSession, TreeSide, DatasetType,
│   │                              BunchMeasurements, etc.)
│   ├── dedup/                   ← UnionFind + SuggestionEngine
│   ├── results/                 ← ResultsComputer
│   ├── quality/                 ← QualityCheck (capture QA validation)
│   ├── usecase/                 ← SessionUseCases (bbox CRUD, link mgmt, mismatch resolve),
│   │                              WeightDatasetPolicy (bunch-weight completion gate)
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
│   ├── navigation/              ← NavHost + routes (start destination = ModuleHubScreen)
│   ├── home/                    ← ModuleHubScreen (dataset picker) + HomeScreen/HomeViewModel
│   │                              (one instance per DatasetType)
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

### Dataset modules (module hub + bunch weight)

The app opens on `ModuleHubScreen`, not on the session list. `DatasetType` (`MULTISIDE`,
`BUNCH_WEIGHT`, `MULTISIDE_VIDEO`) routes everything below it; `Routes.HOME` is an alias for
`Routes.MULTISIDE_HOME`, so existing navigation code keeps working.

**Multiside is unchanged.** Every new parameter defaults to `MULTISIDE`, and
`DatasetType.runGroupKey` returns the legacy `VARIETY__BLOCK` key untouched for it - only
`BUNCH_WEIGHT` gets the `BUNCH_WEIGHT__` prefix. That prefix is what keeps the two modules'
runs apart when the same variety+block is collected in both.

Bunch weight: photo 1 required, photo 2 optional (`sideCount = 2`, and
`DatasetType.allowsEarlyFinish` unlocks the "Use 1 photo" button after the first shot). The
QA gate compares against the photos actually taken, not the configured count, so stopping at
one photo is not a warning. Measurements live on the bbox
(`BboxEntity.weightKg/heightCm/circumferenceCm/notes`, all nullable) and are propagated to
every member of a cross-side link cluster, so one physical bunch carries one set of values.
Weight is required and must be > 0; height/circumference are optional but must be > 0 when
present; an empty optional is stored as `null`, never `0`.
Ripeness classes B1/B2/B3/B4 do not apply to Bunch Weight. Its UI and JSON/CSV/identity/annot-log
exports omit them; YOLO uses the required single object class `0`. The internal bbox class stays
unassigned for backward-compatible Room and Output JSON resume without a schema migration.
`WeightDatasetPolicy.completionError` is the single completion gate - a weight sample can only
be marked complete through it. Full contract in `BUNCH-WEIGHT-MODULE.md`.

Multiside video (`MULTISIDE_VIDEO`): the multiside capture (4 or 8 sides, tablet camera only,
no Orbbec, no annotation) plus one mp4 with audio per tree, recorded while the photos are taken.

- Reached through its own route (`capture-video/{runId}`), so the screen knows the mode on the
  first composition and never auto-starts the Orbbec preview.
- `VideoCaptureStage` binds Preview + ImageCapture + VideoCapture once. The per-side review step
  is skipped while recording because leaving the preview unbinds the camera.
- Every saved photo is taken inside the accepted recording: Record clears earlier draft photos,
  the shutter needs a running recording, and Stop needs every side filled. The one exception is
  a retake from the final review.
- A recording is accepted only when the operator pressed Stop and the recorder finalized without
  error. Anything else deletes `video.incoming.mp4`.
- Files: draft `.capture-drafts/{runId}/video.mp4`, canonical `video/{tree}.mp4`, SAF
  `dataset/video/{tree}.mp4`, ZIP `video/{tree}.mp4`. The sidecar gains the additive key
  `artifacts.video {filename, sizeBytes, sha256}`. No DB migration.
- `commitTreePackage` is the single gate: a `requiresVideo` tree is rejected without
  `videoSource`. The video is copied from the draft by stream, never through the staging directory.
- Tree names carry the reserved marker `VID` (`DAMIMAS_A21B_VID_0001`), same position as `BW`.
- After the commit `finalizeCaptureOnlyTree` creates the first revision; the session screen
  retries it for trees that are not complete.
- Known limits: folder resume skips video packages (it does not restore the recording), and the
  mirror and the ZIP check the video by length, not by content hash.

**DB is at version 8.** `MIGRATION_7_8` adds `sessions.datasetType`, `trees.datasetType`
(both `TEXT NOT NULL DEFAULT 'MULTISIDE'`) and the four nullable bbox measurement columns.
Every addition is additive: existing rows read back as multiside with no measurements, and
`ExportManager` only emits the new keys when a value exists, so already-delivered packages
still parse.

### AnnotationCanvas viewport invariants

- **The auto-fit is keyed on the canvas size, not on a one-shot flag.** The measurement panel
  narrows the canvas at runtime; fitting once left the photo positioned for the old width, so
  it looked shifted and ran under the panel. `fittedTo != size` re-centres it.
- **Two fingers on the canvas report `isActiveEdit`.** The carousel pager and `transformable`
  both want a multi-finger drag; without the signal the pager won the arbitration and
  pinch-zoom did nothing on any tree with more than one side.
- **Review mode still installs no zoom/pan, deliberately.** It would consume the horizontal
  drag and block swiping between sides.

### Bunch-weight carousel invariants

Verified on the moto g45 5G (720x1600) with `field` v0.3.67. Changing any of these means
re-testing on a phone, not just in unit tests.

- **The compact measurement sheet is LIFTED above the keyboard, never padded from inside.**
  It is bottom-anchored with a fixed height, so inner padding consumes the sheet itself and
  leaves only the header, hiding the weight field the moment it is tapped. The IME overlap is
  measured on the wrapping `BoxWithConstraints`, not on the sheet, or the sheet's new position
  feeds back into its own calculation. The full-height inspector on a wide screen cannot move,
  so that one keeps the inner padding.
- **`completeLink` and `changeBboxMeasurements` call `autoSave()` themselves.** A weighed bunch
  cannot be re-weighed once the harvest moves on; holding those values in memory until the
  operator happens to swipe means one process kill erases them. Both are a single deliberate
  tap, not a drag, so they cannot spam the save path.
- **A link applies the SOURCE box's measurements and says so when that discards something.**
  `linkReplacedMeasurements` drives the `weight_link_replaced` toast. Silently replacing a
  weight the operator typed is the failure mode this guards.
- **The "Saved" pulse carries the sheet's height as a bottom inset.** It is drawn at the bottom
  of the content area, which is exactly where the sheet sits, and the sheet is composed after
  it. Without the inset, "Apply to bunch" saved with no visible response at all and operators
  could not tell whether the tap had registered. Reported from the field, not caught in tests.
- **The panel's validation error lives outside the scrolling column.** Inside it, the collapsed
  sheet pushes the message below the fold and it renders as a clipped half-line.

### Depth Viewer (Jet Colormap)

The depth viewer uses the **jet colormap** (blue→cyan→green→yellow→red), matching the web app and Orbbec live preview.

**Formula:** `clampUnit(1.5 - |4t - n|)` where `n=1` (Blue), `n=2` (Green), `n=3` (Red)

**Range:** P2-P98 of valid values inside the configured display limits (defaults 250-7000 mm). Values outside the resulting color range render black. The live preview has its own range smoothing; identical colors across viewer and preview are not guaranteed.

**Value scale:** Read from JSON sidecar (`valueScale` field). Applied as `pixelValue * valueScale` before colormap.

### Dedup Performance (saveDbOnly)

The Dedup button originally called `saveAndAwait()` which waited for `writeSideArtifacts()` (YOLO labels + SAF image mirror) - **12 seconds**. Fixed by creating `saveDbOnly()` that only runs the DB transaction (**13ms**).

**See:** `PERF_GAIN.md` for full analysis.

### Capture-set identity (cross-device merge safety)

Two tablets collecting the same variety+block both counted from 1, so both produced
`DAMIMAS_A21B_0001…`. Extracting the two ZIPs into one folder overwrote 168 samples silently
(`FIELD_REPORT_20260727.md` §3.1).

- **`installId`** - a UUID minted once per install in `InputCache`. Private, never exported.
- **`deviceToken`** - 6 chars derived from `installId` (`CaptureSetPolicy.deviceTokenFrom`).
  Public, opaque, no hardware serial. Alphabet excludes I/L/O/U so it cannot be mistyped.
- **`captureSetId`** - a UUID per run, stored on `sessions` and copied onto each `trees` row.
  A **resumed** tree keeps the identity of the device that captured it.
- **`nameToken`** - **opt-in**, off by default. When enabled in the Start Session dialog (which
  shows the token and a live preview of the tree name), tree names become
  `DAMIMAS_A21B_K7Q2M1_0001`. With it off, names are byte-identical to the legacy ones.

**Adoption rules (`SessionRepository.createRun` → `adoptRunProvenanceLocked`).** C-01 folds a
repeated variety+block into the run that already exists, so identity cannot be written only on
INSERT - the collection tablet's `DAMIMAS__A21B` run predates WS-12 (folder resume, or a row
migrated from v6) and would have stayed anonymous forever.

| Field | Adopted onto an existing run? |
|---|---|
| `captureSetId` / `deviceToken` | Only while the run has none. A run that already has an identity keeps it. |
| `operatorName` | Whenever a non-blank name is entered. Committed trees froze their own at commit; only future captures are labelled. |
| `nameToken` | Only when the run has written **nothing** - no committed tree and no capture draft (`CaptureSetPolicy.resolveNameToken`). |

A started run therefore keeps legacy naming, and the Start Session dialog says so: it looks up the
run it will fold into and renders the real next tree name with the token switch disabled. Losing
the filename token does **not** lose the protection - `captureSetId`/`deviceToken` still reach the
sidecar, manifest, Output JSON, `capture_set.json` and the ZIP filename, so a merge tool can still
separate two devices' identical names.

Carried into: the metadata sidecar (`captureSet`), the package manifest (`captureSet` - note
the pre-existing top-level `captureSetId` there is a *content digest*, unrelated), Output JSON
(`capture_set_id`, `device_token`, and a token suffix on `session_id`), the ZIP filename, and a
new root `capture_set.json` in the archive. `CaptureSetMergePolicy` is the merge rule and
fails closed on any ambiguity, including a legacy package with no identity.

**Compatibility:** every addition is additive. No existing file or field was renamed, so the
already-collected 42/90-tree packages and the folder-resume path are unaffected.

### GPS freshness and operator provenance

`getBestLocation()` used to fall back to an unbounded-age last-known fix, which is how 42 trees
shipped one identical coordinate with nothing in the data saying so.

- `GpsProvider.bestProvenance()` returns a `GpsProvenance` record (status, coordinates,
  accuracy, fix timestamp, age, provider, source) - never a bare coordinate.
- A **stale** fix keeps its coordinates and is labelled `STALE`. **Top-level `lat`/`lng` keep
  their historical population rule: any recorded coordinate is written.** Gating them on
  freshness was tried and reverted - the window is 60 s while one tree takes minutes, so the keys
  would have disappeared from ~100% of new packages *and* been stripped from the already-delivered
  42/90-tree sidecars, which folder resume rewrites. The qualifier lives in `gps.status` /
  `gps.ageMs` / `gps.source`, which is what §3 item 3 actually asked for.
- A failed refresh **replaces** the record, clearing the previous tree's coordinates.
- Freshness is re-judged at commit (`GpsFreshnessPolicy.recheckAtCommit`), because eight photos
  can outlast the 60 s window. `CaptureFlowViewModel.rejudgeGps()` is the single place that does
  it: the QA gate, the on-screen GPS line and the committed record all read the same judgement,
  so the screen can never show a coordinate as live while a `STALE` record is written.
  Resume does *not* re-judge - it is not a new measurement.
- Capture is never blocked, and the QA warning keeps its historical meaning - "no coordinate at
  all". Raising it for a merely stale fix would have put a blocking dialog on every one of ~90
  saves without adding anything `gps.status` does not already record.
- Operator is entered in the Start Session dialog, stored on `sessions`/`trees`, and written as
  `UNKNOWN` (not an empty string) when unset.

### Tap-to-Read Depth

`DepthViewerScreen` has a `pointerInput` modifier that converts screen taps to depth pixel coordinates using `ContentScale.Fit` math. Shows depth in mm via a floating popup.

## Performance Logging

Filter `adb logcat` with:

```bash
adb logcat | grep -E "DedupPerf|CanvasPerf|SessionRepo|DepthViewer"
```

| Tag | Component |
|-----|-----------|
| `SavePerf` | **User-felt** save latency (tap → busy-overlay clears). Log lives at the wait the user sees, not inside the repo - the DB was 10ms yet the user waited 12s. |
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
