# Performance Gains

> **Arsip pengukuran.** Angka di dokumen ini adalah hasil pengukuran pada putaran sebelumnya,
> bukan status performa saat ini. Batas pengukuran lapangan terbaru ada di
> [`FIELD_REPORT_20260727.md` §5.8](FIELD_REPORT_20260727.md#58-addendum-audit-terkini-29-juli-2026).

> **Verified on:** Xiaomi Pad 8 (Android 16, wireless ADB 192.168.1.7:5555).
> Every number below is from `adb logcat` on the live device, measured from the
> **user's perspective** (button tap → UI responds), not from isolated component
> timings. The lesson from the first optimization: the DB was already 10 ms "on
> paper", yet the user waited 12 s — so the log that matters sits where the user
> waits (the busy overlay), not deep inside the repository.

---

# ⭐ Save (bbox / annotation) — 11,610 ms → 20 ms felt  (2026-06-16, round 2)

## Problem (user-reported)

"Saving bbox and saving dedup are still very slow." Tapping **Save** held the
busy overlay — blocking the user — for the entire write.

## Measurement (before)

Added a `SavePerf` log at the exact point the user waits: inside `save()`, around
the enqueued block whose lifetime is precisely how long the busy overlay shows
(`OperationQueue` toggles `busy` true→false around it). Also split
`writeSideArtifacts` timing per artifact.

```
SessionRepo: saveSession DB transaction took 10ms          ← already fast
SessionRepo: writeSideArtifacts side 0: total=2551ms (labelLocal=2 labelSaf=1965 annot=306 imgMirror=278)
SessionRepo: writeSideArtifacts side 1: total=2979ms (... labelSaf=2367 ...)
SessionRepo: writeSideArtifacts side 2: total=3072ms (... labelSaf=2417 ...)
SessionRepo: writeSideArtifacts side 3: total=3199ms (... labelSaf=2534 ...)
SessionRepo: saveSession writeSideArtifacts took 11594ms    ← the wait
SessionRepo: saveSession END - total=11610ms
```

**The whole 11.6 s was SAF (Storage Access Framework) I/O.** The DB write — the
source of truth — was 10 ms. The local label files were 1–3 ms. Everything else
was `DocumentFile` churn against the export folder.

## Three root causes (all confirmed on device)

1. **SAF mirror was on the blocking save path.** It is explicitly a *best-effort
   mirror* ("the app-external store is ALWAYS the source of truth"), yet the user
   waited for it.
2. **`DocumentFile.findFile()` enumerates the entire directory** (one
   ContentResolver query per call). The `Output TXT/field` dir held **228 files**,
   so each label write re-scanned 228 entries to delete-then-create. Cost *grows
   with every tree added*.
3. **A latent correctness bug fed cause #2.** `writeText()` passed mime
   `application/json` for `.txt` files, so SAF saved them as `name.txt.json`. The
   delete-existing lookup (`findFile("name.txt")`) then missed the renamed file, so
   **every save spawned a `name.txt (N).json` duplicate** — 32 dupes per label,
   228 stale files total. The duplicate growth *was* the reason SAF kept getting
   slower over time.

## Fix

1. **Move the SAF mirror off the save path** (`SessionRepository`). Split into:
   - `writeLocalArtifacts` — YOLO `.txt` + annot-log to internal storage (the
     source of truth). Synchronous, ~4–9 ms for 4 sides.
   - `mirrorSafArtifacts` — label + annot-log + image to SAF. Fired on a
     single-threaded background `safScope` (`Dispatchers.IO.limitedParallelism(1)`),
     **never awaited** by the UI. Serial so mirror writes can't interleave.
2. **Cache SAF directory handles + child listings** (`SafMirrorStore`): resolve
   each directory's `DocumentFile` once, and list its children once into a
   `name → DocumentFile` map, so `findFile`/`exists`/`delete` become O(1) instead
   of a full directory enumeration. `listFiles` force-refreshes (import/resume path
   must see external truth).
3. **Overwrite-in-place + correct mime** (`SafMirrorStore`): if the target exists,
   `openOutputStream(uri, "wt")` truncate-writes it (no delete+create, no `(N)`
   churn). `writeText` infers mime from the extension (`.txt`→`text/plain`,
   `.json`→`application/json`, `.csv`→`text/csv`).

## Results (after — measured)

```
SavePerf:    save(): tap→busy-clear = 20ms (felt)              ← user-felt
SessionRepo: writeLocalArtifacts 4 sides took 4ms             ← source of truth, sync
SessionRepo: mirrorSafArtifacts DAMIMAS_A21B_0001 (4 sides) took 253ms [background]
```

| Metric (4-side tree) | Before | After | Improvement |
|----------------------|--------|-------|-------------|
| **Save felt latency (tap → busy clears)** | **11,610 ms** | **20–33 ms** | **~400–580× faster** |
| SAF mirror work itself | 11,594 ms (blocking) | 253 ms warm / ~3.3 s cold (background) | ~46× (warm), and off the critical path |
| DB transaction | 10 ms | 10 ms | unchanged (already fast) |
| Local label write | 1–3 ms/side | 1 ms/side | unchanged |
| Stale files added per save | +4 (`(N)` dupes) | **0** | duplicate growth stopped |
| Label file on disk | `…_1.txt.json` (wrong) | `…_1.txt` (correct YOLO) | correctness fixed |

**Verification:** dir count held at 232 across repeated saves (no duplicate
growth); `Output TXT/field/DAMIMAS_A21B_0001_1.txt` now exists with valid content
(`0 0.504036 0.502092 …`); no `Failed to create unique file` errors.

## "Saving dedup" and UI smoothness — also measured

Dedup confirm and the normal Save share `SessionRepository`, so they inherit the
same fast DB + background-SAF behaviour. Measured on device:

```
SessionRepo: saveSession END - total=15ms                              ← dedup save+continue
SessionRepo: mirrorSafArtifacts DAMIMAS_A21B_0001 (4 sides) took 3369ms [background]
```

- **Dedup save → Results navigation: 15 ms** (was gated behind the ~11.6 s SAF
  write — `saveAndContinue` calls `onDone()` only after the save returns). The
  Results screen now appears instantly after tapping the green ✓.
- **UI transition jank** (`dumpsys gfxinfo`, over dedup-open → save → results-nav):
  **556 frames, 3 janky (0.54%)**, 90th-percentile **13 ms** (within the 16.6 ms
  60 fps budget). The previous "perpindahan antar UI kurang mulus" was the 11.6 s
  busy overlay blocking interaction during/after a save — removing SAF from the
  critical path is what made navigation feel smooth.
- Back-from-annotation does no blocking save (save is explicit), so leaving the
  screen is immediate.

## Files changed

| File | Change |
|------|--------|
| `data/storage/SafMirrorStore.kt` | Directory + child caches; overwrite-in-place (`wt`); mime-by-extension; `listFiles` force-refresh |
| `data/storage/SessionRepository.kt` | Split `writeSideArtifacts` → sync `writeLocalArtifacts` + background `mirrorSafArtifacts` on a serial `safScope` |
| `ui/annotation/AnnotationScreen.kt` | `SavePerf` log at the user-felt point (tap → busy clear) |

## Follow-up

- 228 stale `Output TXT/field/*.txt (N).json` files remain from the old bug. New
  writes no longer add to them, but a one-time cleanup would also speed up the
  *cold* SAF listing. Not auto-deleted (user's Documents folder) — flagged only.

---

# Performance Gain — Dedup Screen Open/Close (round 1)

> **Date:** 2026-06-16  
> **Optimized by:** AI Agent (pi-coding-agent)  
> **Verified on:** Xiaomi Pad 8 (Android 16, wireless ADB 192.168.1.7:5555)

## Problem

Opening the Deduplication screen from the Annotation screen took **~12 seconds** from button tap to fully rendered screen.

## Root Cause Analysis

### Profiling with `adb logcat`

Added performance logging (`DedupPerf`, `CanvasPerf`, `SessionRepo` tags) to measure:
1. Composable init → session loaded
2. DB query time
3. Image decode time
4. `saveSession()` breakdown (DB transaction vs file artifacts)

### Initial Logs (Before Optimization)

```
14:10:17.453  saveSession START - tree=DAMIMAS_A21B_0007
14:10:17.467  saveSession DB transaction took 12ms      ← FAST
14:10:29.812  saveSession writeSideArtifacts took 12345ms  ← BOTTLENECK!
14:10:29.813  saveSession END - total=12359ms

14:10:29.987  DeduplicationScreen composable START
14:10:30.005  load() DB query took 18ms
14:10:30.074  SESSION LOADED - elapsed=200ms
```

**Key Finding:** The Dedup button called `saveAndAwait()` which waited for the ENTIRE `saveSession()` to complete — including `writeSideArtifacts()` (YOLO label serialization + SAF image mirror). The DB transaction itself only took **12ms**, but `writeSideArtifacts` took **12,345ms** (12 seconds!) due to SAF (Storage Access Framework) I/O for mirroring multi-MB images to the export folder.

## Solution

### 1. Split save into DB-only + deferred artifacts

Created `saveDbOnly()` in `SessionRepository.kt`:
- Runs the atomic DB transaction (sides/bboxes/links) — **~13ms**
- Returns immediately after DB commit
- Does NOT call `writeSideArtifacts()`

### 2. Updated Dedup button in `AnnotationScreen.kt`

**Before:**
```kotlin
IconButton(onClick = {
    scope.launch {
        viewModel.saveAndAwait()  // Waits 12 seconds!
        onOpenDedup()
    }
}) {
    Icon(Icons.Default.Link, "Deduplication")
}
```

**After:**
```kotlin
IconButton(onClick = {
    scope.launch {
        viewModel.saveDbOnly()  // ~13ms
        onOpenDedup()
    }
}) {
    Icon(Icons.Default.Link, "Deduplication")
}
```

### 3. Data consistency preserved

- `db.withTransaction` is atomic — dedup sees either old or new data, never partial
- `writeSideArtifacts` runs OUTSIDE the DB transaction anyway
- YOLO labels/SAF mirror are still written on normal Save button press

## Results

### After Optimization (Logs)

```
14:14:25.447  saveDbOnly START - tree=DAMIMAS_A21B_0007
14:14:25.460  saveDbOnly END - total=13ms              ← 13ms!

14:14:25.522  DeduplicationScreen composable START
14:14:25.533  load() DB query took 11ms
14:14:25.604  SESSION LOADED - elapsed=125ms
14:14:25.604  LOADING COMPLETE - elapsed=125ms
```

### Performance Comparison

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| `saveSession` (with artifacts) | 12,359ms | — | — |
| `saveDbOnly` (DB only) | — | 13ms | — |
| Dedup screen open (total) | **~12,100ms** | **~138ms** | **87x faster** |
| DB transaction | 12ms | 13ms | Same |
| Image decode | 23ms | 13ms | Cache hit |

### Visual Confirmation

- **Before:** User taps Dedup button → waits ~12 seconds → screen appears
- **After:** User taps Dedup button → screen appears instantly (<200ms)

## Files Changed

| File | Change |
|------|--------|
| `data/storage/SessionRepository.kt` | Added `saveDbOnly()` method + performance logging |
| `ui/annotation/AnnotationScreen.kt` | Dedup button uses `saveDbOnly()` instead of `saveAndAwait()` |
| `ui/common/AnnotationCanvas.kt` | Added `CanvasPerf` logging for image load timing |
| `ui/dedup/DeduplicationScreen.kt` | Added `DedupPerf` logging for screen load timing |

## Performance Logging Tags

For future profiling, filter `adb logcat` with:

```bash
adb logcat | grep -E "DedupPerf|CanvasPerf|SessionRepo"
```

| Tag | Component |
|-----|-----------|
| `DedupPerf` | DeduplicationScreen composable + ViewModel |
| `CanvasPerf` | AnnotationCanvas image loading |
| `SessionRepo` | SessionRepository save operations |

## Remaining Optimization Opportunities

1. **`writeSideArtifacts` still runs on normal Save** — Could be made async/background
2. **SAF image mirror** — Only writes if not exists, but `saf.exists()` check itself may be slow
3. **YOLO label serialization** — Runs on every save, even if bboxes unchanged
4. **Dedup close (back button)** — Not yet profiled, may have similar issues

---

# Audit Pass — Correctness + Algorithmic Gains (2026-06-16)

> **Scope:** Autonomous performance/bug/edge-case sweep across the domain + data layers.
> **Verification level:** All changes compile and are covered by the unit suite
> (`./gradlew :app:testDebugUnitTest` → **74 tests, 0 failures**, up from 39).
> Algorithmic/allocation gains below are **code-confirmed**, not yet device-profiled —
> they reduce work/allocations by construction but I have not attached a logcat trace to each.
> See `TODO.md` for the full bug/edge-case tracker.

## 1. UnionFind `getCluster()` — O(n) → O(1)  *(P3)*

`getCluster(x)` previously scanned **every** node in the structure and re-ran `find()` on
each (`parent.keys.filter { find(it) == root }`). It is called per-cluster during dedup
review and once per quality check. Now the structure maintains a `members: Map<root, MutableSet<node>>`
reverse index updated on every `union()`, so lookup is a single map fetch.

| | Before | After |
|---|--------|-------|
| `getCluster()` | O(n) scan + n× `find()` | O(1) map lookup |
| Cost driver | total boxes in tree | size of the one cluster |

**Verified by:** `UnionFindEdgeTest` (6 tests incl. 500-node chain, post-hoc unions, idempotency)
+ `UnionFindTest`. The reverse index stays correct as unions accumulate.

## 2. OnnxDetector — reuse the NCHW input buffer  *(P2, B7)*

Each `detect()` call allocated a fresh `FloatBuffer.allocate(3 * 640 * 640)` ≈ **4.7 MB**,
immediately garbage after inference. During a multi-side batch detect that is one large
short-lived allocation per image → avoidable GC pressure / jank. The buffer is now
pre-allocated and reused across calls (re-grown only if the model input size changes).
Also added a `Log.w` on unexpected output tensor shape (was a silent `emptyList()`).

**Status:** Code-confirmed (allocation removed from the hot path). On-device frame-time
delta not yet measured — would need a `DetectPerf` logcat trace on the Xiaomi Pad.

## 3. SessionRepository — cache the ISO date formatter  *(P4)*

`buildMetadataJson()` constructed a new `SimpleDateFormat(...)` (with a `TimeZone` lookup)
on every tree-metadata write. Hoisted to a `companion object` constant. `SimpleDateFormat`
is not thread-safe, but metadata writes are serialized through `OperationQueue`, so a single
shared instance is safe here. Minor, but removes a per-write object + parse of the pattern string.

## 4. YoloParser — pre-compiled regex + locale-correct output  *(P1, B1-B4)*

- **Correctness (critical, B1):** `f6()` used `String.format("%.6f", …)` with the **default
  locale**. On a comma-decimal locale (German/French/Indonesian) this emits `0,5` instead of
  `0.5`, producing YOLO label files that the parser (and external tooling) cannot read →
  silent dataset corruption. Now pinned to `Locale.US`.
- **Perf (P1):** the whitespace split regex is pre-compiled once (`private val WS`) instead of
  `Regex("\\s+")` per line.
- **Robustness (B2-B4):** zero image dimensions now short-circuit (`parse`/`serialize` return
  empty/`""`) instead of dividing by zero; NaN/Inf coordinates are rejected.

**Verified by:** `YoloParserEdgeTest` — the locale test flips the default `Locale` to
`Locale.GERMANY`, serializes, and asserts the output contains `.` and no `,` and round-trips.

## 5. Coroutine + ID-collision fixes  *(B5, B6)*

- **B5 — `OperationQueue` leak:** the internal `CoroutineScope(SupervisorJob() + …)` was never
  cancelled. Added `dispose()` (cancels in-flight + the scope) for the owning ViewModel/screen
  to call on teardown.
- **B6 — link-ID collision:** `addManualLink` derived the new id from `confirmedLinks.size + 1`,
  so deleting then re-adding a link could regenerate an id that still pointed at stale state.
  Now uses a monotonic source. `OperationQueue.nextLinkId()` (`AtomicInteger`) is provided for
  callers that want a stable monotonic sequence.

**Verified by:** `OperationQueueTest` (FIFO order, busy-flag lifecycle, `nextLinkId` monotonic
over 100 ids, `dispose()` safe after `cancel()`) + `SessionUseCasesBboxTest` (unique link ids,
delete-then-re-add does not collide).

## Net result

| Area | Change | Confirmed by |
|------|--------|--------------|
| Dedup cluster lookup / quality check | O(n) → O(1) per cluster | unit tests |
| ONNX detect | −4.7 MB alloc per call | code review |
| Metadata write | −1 `SimpleDateFormat` alloc per write | code review |
| YOLO export on non-US locales | corrupt → correct | unit test (Germany locale) |
| Coroutine scope on teardown | leak → disposed | unit test |
| Link IDs after delete+re-add | collidable → unique | unit test |
| **Test coverage** | **39 → 74 tests, 0 failures** | gradle |

> **Not yet device-verified:** the ONNX buffer-reuse frame-time win and the UnionFind O(1)
> win on a real large tree. These need `adb logcat` traces on the Xiaomi Pad 8 to quantify —
> the changes are correct and reduce work by construction, but I'm flagging them as
> code-confirmed rather than measured, per the project's "don't overestimate" rule.
