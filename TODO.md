# TODO.md — Device-Measured Performance Exploration

> Started: 2026-06-16 (round 2 — real on-device profiling via ADB)
> Goal: REAL, measured performance gains on the live device (Xiaomi Pad 8). Only
> device-verified wins (with logcat numbers) get written to PERF_GAIN.md.
> Logging rule: measure what the USER feels (tap → UI responds), not just isolated
> component times — the DB was already fast "on paper" yet the user waited 12 s.
> Legend: 🔴 critical · 🟠 high · 🟡 med · ✅ done(measured) · ⏳ wip · ❌ skip

---

## Measured bottlenecks (logcat, real device)

| # | Area | Finding | Status |
|---|------|---------|--------|
| M1 | Save (bbox) | `saveSession` blocked the busy overlay for **11,610ms**; DB=10ms, SAF=11,594ms | ✅ fixed → **20ms felt** |
| M2 | SAF writes | `DocumentFile.findFile` enumerates whole dir per call; cost grows with dir size (228 files) | ✅ fixed (dir+child cache) |
| M3 | SAF label bug | `writeText` used `application/json` mime → `.txt` saved as `.txt.json`; delete-miss spawned `(N)` dupes (32 each, 228 stale files) — also the *cause* of M2 growth | ✅ fixed (mime by ext + overwrite-in-place) |
| M4 | Dedup save | confirm/apply path — verify it now inherits the fast save | ✅ done → **15ms** (was 11.6s); dedup→Results nav instant |
| M5 | UI transitions | "perpindahan antar UI kurang mulus" — profile nav | ✅ measured smooth (0.54% jank, p90=13ms); root cause was the save overlay, now fixed |

## Permanent perf log tags (this round)

| Tag | Measures |
|-----|----------|
| `SavePerf` | save(): tap → busy-overlay-clear (user-felt latency) |
| `SessionRepo` | DB txn, writeLocalArtifacts, mirrorSafArtifacts [background] |

## Flow / correctness fixes (round 3)

| # | Area | Finding | Status |
|---|------|---------|--------|
| F1 | Results screen flow | Dead-end: only a back arrow + 5 rarely-used export buttons; no "next capture"/"tree list". Redesigned into a "SELESAI" section: primary **Foto Berikutnya** (save output → capture next) + **Daftar Pohon** (save output → tree list); 4 export formats moved into an **Ekspor lainnya…** bottom sheet. Output JSON now saved automatically on finish. | ✅ verified on device |
| F2 | Stale image after delete+recapture | `BitmapCache` keyed by URI only; reused path (id reset) served the deleted tree's bitmap. Now keyed by URI + file mtime + size (self-invalidating). | ✅ fixed (code) |
| F3 | Delete didn't clean export folder | `SessionDetailScreen.deleteTree` / `HomeScreen.deleteRun` called `repo.delete*` WITHOUT the SAF uri → mirror copies (images/labels/Output) survived; recapture's "mirror once if absent" guard then kept the OLD photo in the export. Both now pass `exportFolder.folderUri`. | ✅ fixed (code) |

## Carousel becomes the primary editor (round 4)

> User prefers the Carousel UI (cleaner, simpler) over the main AnnotationScreen and
> asked to consolidate onto it. All items below verified on device.

| # | Area | Change | Status |
|---|------|--------|--------|
| C1 | Bottom-bar touch targets | B1–B4 / Delete / Link / Eye → 48dp; Save & Exit + Next Tree → full-width 48dp buttons (no tiny text targets → fewer mis-taps) | ✅ verified |
| C2 | Swipe = next side (was dead) | Added `CanvasTool.VIEW` (read-only: no transformable, no drag-edit) used in REVIEW mode → `HorizontalPager` finally receives the swipe. Verified 1/4 → 2/4. | ✅ verified |
| C3 | Review/Edit toggle was a no-op | Now meaningful: REVIEW = read-only + swipe + tap-select; EDIT = move/resize + Draw. | ✅ verified |
| C4 | Could not draw new boxes in carousel | EDIT mode shows a Crop (Draw) toggle → `CanvasTool.DRAW`. Verified box drawn ("1 boxes · 1 unassigned"). | ✅ verified |
| C5 | "Next Tree" button was dead | Resolved runId via `getTreeRunId`; wired to capture next tree (popUpTo session detail). | ✅ wired (nav verified via build) |
| C6 | Make carousel primary | Tree-open AND capture-saved now route to `carousel/{treeKey}` instead of `annotation/{treeKey}`. | ✅ verified (tap tree → carousel) |

- AnnotationScreen + `ANNOTATION` route are now **unused** (kept as fallback, not deleted).
  Remove once the carousel-as-editor is confirmed good in real field use.

## Carousel UX hardening (round 5)

> User feedback: "edit bikin bbox berhasil tapi tidak tersimpan otomatis", "jangan
> kurangi fitur", "link tidak kelihatan tandan mana yang sudah ter-link".

| # | Area | Change | Status |
|---|------|--------|--------|
| A1 | No auto-save → drawn boxes lost | Silent `autoSave()` (DB+local sync, SAF background, no busy overlay) fires on **Edit↔Review toggle**, **side change** (swipe/dots), and **Back/system-back**. Dirty-gated so idle navigation writes nothing. | ✅ verified ("Tersimpan ✓" pulse on toggle) |
| A2 | Drawn box not selected | Freshly drawn box auto-selects (parity w/ old editor) → tap a class immediately. | ✅ verified |
| A3 | Can't tell which bunch is linked | Each linked box shows a green badge with its **link-group number** (same number on the matching bunch on the adjacent side); count overlay adds "🔗N linked". Union-find over confirmedLinks. | ✅ code (needs link created to see live) |
| A4 | Back lost edits | Top-bar Back + system/gesture Back now save-then-leave. | ✅ verified (build/flow) |
| A5 | Save & Exit blended into dark bg | Now an OutlinedButton (visible border); Next Tree stays filled green. | ✅ verified |

## Notes / follow-ups
- 228 stale `Output TXT/field/*.txt (N).json` files remain on the test device from the
  old duplicate bug. New writes no longer add to them, but a one-time cleanup would make
  the *cold* SAF listing faster too. NOT auto-deleting (user's Documents folder) — flag only.
