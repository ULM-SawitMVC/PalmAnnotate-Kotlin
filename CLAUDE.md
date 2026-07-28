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

Without the signing secrets configured the name gains a `-NOKEY` suffix
(`PalmAnnotate-field-v0.3.42-NOKEY.apk`). That build runs, but it cannot update — or be
updated by — an APK signed with the real key. See [Distribution signing](#distribution-signing).

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

### Capture-set identity (cross-device merge safety)

Two tablets collecting the same variety+block both counted from 1, so both produced
`DAMIMAS_A21B_0001…`. Extracting the two ZIPs into one folder overwrote 168 samples silently
(`docs/FIELD_REPORT_20260727.md` §3.1).

- **`installId`** — a UUID minted once per install in `InputCache`. Private, never exported.
- **`deviceToken`** — 6 chars derived from `installId` (`CaptureSetPolicy.deviceTokenFrom`).
  Public, opaque, no hardware serial. Alphabet excludes I/L/O/U so it cannot be mistyped.
- **`captureSetId`** — a UUID per run, stored on `sessions` and copied onto each `trees` row.
  A **resumed** tree keeps the identity of the device that captured it.
- **`nameToken`** — **opt-in**, off by default. When enabled in the Start Session dialog (which
  shows the token and a live preview of the tree name), tree names become
  `DAMIMAS_A21B_K7Q2M1_0001`. With it off, names are byte-identical to the legacy ones.

**Adoption rules (`SessionRepository.createRun` → `adoptRunProvenanceLocked`).** C-01 folds a
repeated variety+block into the run that already exists, so identity cannot be written only on
INSERT — the collection tablet's `DAMIMAS__A21B` run predates WS-12 (folder resume, or a row
migrated from v6) and would have stayed anonymous forever.

| Field | Adopted onto an existing run? |
|---|---|
| `captureSetId` / `deviceToken` | Only while the run has none. A run that already has an identity keeps it. |
| `operatorName` | Whenever a non-blank name is entered. Committed trees froze their own at commit; only future captures are labelled. |
| `nameToken` | Only when the run has written **nothing** — no committed tree and no capture draft (`CaptureSetPolicy.resolveNameToken`). |

A started run therefore keeps legacy naming, and the Start Session dialog says so: it looks up the
run it will fold into and renders the real next tree name with the token switch disabled. Losing
the filename token does **not** lose the protection — `captureSetId`/`deviceToken` still reach the
sidecar, manifest, Output JSON, `capture_set.json` and the ZIP filename, so a merge tool can still
separate two devices' identical names.

Carried into: the metadata sidecar (`captureSet`), the package manifest (`captureSet` — note
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
  accuracy, fix timestamp, age, provider, source) — never a bare coordinate.
- A **stale** fix keeps its coordinates and is labelled `STALE`. **Top-level `lat`/`lng` keep
  their historical population rule: any recorded coordinate is written.** Gating them on
  freshness was tried and reverted — the window is 60 s while one tree takes minutes, so the keys
  would have disappeared from ~100% of new packages *and* been stripped from the already-delivered
  42/90-tree sidecars, which folder resume rewrites. The qualifier lives in `gps.status` /
  `gps.ageMs` / `gps.source`, which is what §3 item 3 actually asked for.
- A failed refresh **replaces** the record, clearing the previous tree's coordinates.
- Freshness is re-judged at commit (`GpsFreshnessPolicy.recheckAtCommit`), because eight photos
  can outlast the 60 s window. `CaptureFlowViewModel.rejudgeGps()` is the single place that does
  it: the QA gate, the on-screen GPS line and the committed record all read the same judgement,
  so the screen can never show a coordinate as live while a `STALE` record is written.
  Resume does *not* re-judge — it is not a new measurement.
- Capture is never blocked, and the QA warning keeps its historical meaning — "no coordinate at
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

Build variants: `field` (collection), `debug` (local development), `trace` (side-by-side
diagnostics on a tablet whose old debug app is signed with a different key). See the
invariants below for which one is safe to hand to an operator.

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
- **CI never builds `release`.** `android-build.yml` builds `field`, `debug` and `trace`;
  `release.yml` publishes `field` + `trace` only. All keep R8 and resource shrinking off, and
  each has its own application id so none can overwrite another's app-private dataset. Only
  `field` is non-debuggable. `field`/`trace` are signed with the persistent distribution key
  when the secrets are present — see [Distribution signing](#distribution-signing).
- **Only the `field` APK may be used for dataset collection.** `trace` is `initWith(debug)`
  and therefore debuggable, which costs roughly the 19 ms median frame measured in
  `docs/FIELD_REPORT_20260727.md` §4.5.
- **The release asset named `PalmAnnotate-debug-v<version>.apk` carries applicationId
  `dev.sawitulm.palmannotate.trace`.** This is deliberate (`release.yml:52-53`): `trace` is the
  side-by-side diagnostic app that replaces `debug` in releases, so it ships under the name
  operators recognise. Don't "fix" the name — but do remember that the *variant* is `trace`
  when reading logs, matching signatures, or issuing `pm uninstall`.
- **A runner-generated debug keystore is not a stable update identity.** `release.yml` now
  **fails before building** when the signing secrets are absent, and Gradle renames an
  unsigned distributable APK `…-NOKEY.apk`. Compare certificate SHA-256 digests with
  `apksigner verify --print-certs`; a mismatch forces uninstall and would remove that
  package's app-private data.
- **Only ONE variant owns `USB_DEVICE_ATTACHED`.** The filter lives in
  `app/src/field/AndroidManifest.xml`, not `src/main`. With all three packages installed, a
  shared filter raised a package chooser on every camera plug-in and bound the USB permission
  to whichever app was tapped. `debug`/`trace` still open the camera through
  `OrbbecManager.requestPermission()` (runtime `UsbManager`), which this does not affect.
  `VariantManifestPolicyTests` fails if a second source set ever claims the filter.
  **Deployment precondition:** this only removes the chooser once *every installed* variant is
  rebuilt from this commit. Tablet `b98cea56` still has the older `.debug` v0.3.41 (which holds
  the 27 Jul data and must not be uninstalled) — its merged manifest still declares the filter,
  so the chooser persists until that package is updated in place. It is debug-signed today, so
  updating it in place is possible. Verify on the device; it cannot be checked from CI.
- **`android:allowBackup` is `false`, deliberately.** The dataset under `getExternalFilesDir`
  is multiple GB — far past Auto Backup's 25 MB quota — and carries field GPS and operator
  names. A restore could not work anyway: SAF grants are not restored and a restored Room DB
  would reference files that were never copied. `backup_rules.xml` and
  `data_extraction_rules.xml` exclude every domain (cloud backup *and* device transfer) so
  re-enabling backup cannot silently expose a domain. The sanctioned recovery path is the
  dataset ZIP / SAF mirror.
- **`release.yml` fails the run if a pushed tag disagrees with the built version.** That
  guard exists so a Release can never carry an APK whose internal version differs from
  its label. Don't relax it.
- **`git push --follow-tags` triggers both workflows** (branch push + tag push) — correct
  output, one wasted build. Push commit and tag separately.

### Distribution signing

Android refuses `install -r` when the signer changed. The only way past it is `pm uninstall`,
which deletes the package's app-private storage — i.e. the collected dataset. So `field` and
`trace` are signed with one persistent key, sourced from repository secrets.

| Secret / Gradle property | Contents |
|---|---|
| `PALMANNOTATE_KEYSTORE_BASE64` | base64 of the `.jks` (used by CI) |
| `PALMANNOTATE_KEYSTORE_PATH` | path to the `.jks` (local alternative to the blob) |
| `PALMANNOTATE_KEYSTORE_PASSWORD` | store password |
| `PALMANNOTATE_KEY_ALIAS` | key alias |
| `PALMANNOTATE_KEY_PASSWORD` | key password |
| `PALMANNOTATE_SIGNING_CERT_SHA256` | **required to publish**; `release.yml` fails if the built cert differs |

> ### ⛔ Read before configuring the secrets
>
> A tablet in the field already has `dev.sawitulm.palmannotate.field` installed **with a dataset
> in it** (`docs/FIELD_REPORT_20260727.md` — tablet `b98cea56`, v0.3.44, 42 committed trees).
> Introducing a *different* signer is not a packaging detail: `install -r` starts failing with
> `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the only way to install the new build is
> `pm uninstall`, which deletes that dataset. Generating a fresh key without checking would
> **cause** the data-loss event this whole workstream exists to prevent.
>
> So the first question is not "which key do I create" but "which key is already on the device".

**Step 1 — identify the installed signer (do this first, on the device).**

```powershell
$adb = 'C:\tools\android-sdk\platform-tools\adb.exe'
$installed = & $adb -s 192.168.1.7:5555 shell pm path dev.sawitulm.palmannotate.field
& $adb -s 192.168.1.7:5555 pull ($installed -replace '^package:','') installed-field.apk
& 'C:\tools\android-sdk\build-tools\35.0.0\apksigner.bat' verify --print-certs installed-field.apk
```

**Step 2 — prefer adopting that signer.** If the digest matches a keystore you still hold (for a
locally built APK that is `~/.android/debug.keystore`, alias `androiddebugkey`, store/key password
`android`), use **that** file as `PALMANNOTATE_KEYSTORE_BASE64`. The identity then does not change,
every installed app updates in place, and nothing has to be uninstalled.

**Step 3 — only if the existing signer is unrecoverable**, generate a new one — and treat it as a
migration, not a config change:

1. Export a dataset ZIP from every device that holds data and **verify it off-device** (open the
   ZIP, count trees) before the first key-change install.
2. Record the pre-change digest from step 1 in the release notes.
3. Then, and only then, `pm uninstall` + install the new build.

```powershell
$env:JAVA_HOME = 'C:\tools\jdk17\jdk-17.0.19+10'
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v `
    -keystore palmannotate-release.jks -alias palmannotate `
    -keyalg RSA -keysize 2048 -validity 10000 `
    -storepass '<store-pw>' -keypass '<key-pw>' `
    -dname "CN=PalmAnnotate, O=SawitULM, C=ID"

# Value for PALMANNOTATE_KEYSTORE_BASE64
[Convert]::ToBase64String([IO.File]::ReadAllBytes('palmannotate-release.jks')) | Set-Clipboard

# Value for PALMANNOTATE_SIGNING_CERT_SHA256 (required) — the SHA256 line
& "$env:JAVA_HOME\bin\keytool.exe" -list -v -keystore palmannotate-release.jks -alias palmannotate
```

Back the `.jks` up off the machine and add it to the repository's Actions secrets
(Settings → Secrets and variables → Actions). **Never commit it.**

The pin is mandatory because `field` and `trace` are signed by the same config, so the
field-vs-trace comparison in `release.yml` can never fail on its own. The pin is the only guard
that can catch a *changed* key.

Behaviour without the secrets:

- **Local builds keep working.** Gradle falls back to the debug keystore and names the output
  `PalmAnnotate-field-v<version>-NOKEY.apk`. `BuildConfig.SIGNING_IDENTITY` becomes
  `EPHEMERAL_DEBUG` and `PalmAnnotateApp` logs a warning naming the data-loss consequence.
- **`release.yml` refuses to run.** The secret check is the first step, before checkout, and a
  `-NOKEY` asset is rejected again just before upload.
- **`android-build.yml` still builds** (a PR from a fork has no secrets); the `-NOKEY` name is
  the marker that the artifact cannot update anything.

`release.yml` additionally rejects a `versionCode` that is not greater than the highest already
published (the rebase/force-push case), except when re-running the same tag, and records the
certificate SHA-256 in the release notes.

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
