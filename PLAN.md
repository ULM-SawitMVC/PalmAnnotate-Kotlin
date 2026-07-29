# PLAN — Patch-fixing data-integrity RGB-D + bump 0.2 → 0.3

> **Arsip rencana.** Tahap dalam dokumen ini sudah dijalankan pada snapshot sebelumnya. Gunakan
> [`docs/FIELD_REPORT_20260727.md` §5.8](docs/FIELD_REPORT_20260727.md#58-addendum-audit-terkini-29-juli-2026)
> untuk status lapangan dan batas verifikasi perangkat keras saat ini.

> Sumber temuan: `docs/AUDIT_RGBD_DEPTH.md` (audit read-only). File ini adalah rencana eksekusi
> bertahap untuk menutup temuan prioritas §9. Status per fix ada di tabel paling bawah.

## ⛔ MANDAT: 0% ERROR TOLERANCE — 0% BUG

Hari pertama user membuka app lagi = **hari pengambilan dataset**. Tidak ada kesempatan re-test
dengan kamera Orbbec sebelum itu. Konsekuensi:

- **Tidak boleh introduce bug.** Setiap edit harus additive, minimal, dan isolated.
- **Cek SEMUA related code** (setiap caller, setiap pembaca file/DB, setiap test) sebelum menyatakan
  selesai. Bukan hanya baris yang diubah.
- **Selesaikan SELURUH fixing** sebelum bilang "selesai". Tidak ada penyelesaian parsial.
- **Kejujuran soal batas:** jalur *runtime Orbbec* (D2C aktual, `getCameraParam()` di device) tidak
  bisa diverifikasi tanpa kamera. Untuk jalur ini: setiap panggilan SDK baru dibungkus `try/catch`,
  **fail-safe** (gagal = turun ke perilaku hari ini, tidak pernah crash capture/preview, tidak
  pernah menulis data korup), dan **isolated** (mudah di-rollback). Preview/frame-callback path
  **tidak disentuh sama sekali**.

## Context

Audit menyimpulkan **byte depth mentah aman** (uint16 lossless, `valueScale` per-frame benar,
pairing RGB↔depth aman by-construction). Yang rusak adalah semua yang *mengelilingi* byte itu —
penamaan file tabrakan lintas run, metadata alignment dikarang, dan beberapa jalur gagal-senyap.
Keputusan user: cakupan = seluruh §9; C-01 = satu blok (`groupKey`) unik → memakai blok yang sama
masuk ke run/folder yang sama (bukan run paralel); bump ke `0.3`.

## Urutan Kerja (WAJIB — Phase 0 dulu)

C-01 backstop mengubah **schema DB**. Selama `fallbackToDestructiveMigration()` aktif, kenaikan
schema men-drop semua tabel (skenario H-09). Jadi **H-09 dikerjakan lebih dulu**.

---

## Phase 0 — Fondasi DB safety (H-09) — PREREQUISITE
**File:** `data/db/PalmAnnotateDatabase.kt`, `data/db/Entities.kt`, `app/build.gradle.kts`
1. `build.gradle.kts`: `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`.
2. `PalmAnnotateDatabase.kt`: `exportSchema = true`, `version = 3`, hapus
   `.fallbackToDestructiveMigration()` → `.addMigrations(MIGRATION_2_3)`.
3. `MIGRATION_2_3`: unique index `sessions.groupKey` + `trees.treeName`.
4. `Entities.kt`: tambah `@Index(value=["groupKey"], unique=true)` (SessionEntity) &
   `@Index(value=["treeName"], unique=true)` (TreeEntity) supaya schema JSON KSP cocok dengan migration.

**Regression guard:** unique index gagal kalau DB dev punya duplikat lama →
`adb shell pm clear dev.sawitulm.palmannotate.debug` sebelum install v3. Downgrade kini crash-keras
(jangan side-load build lama).

## Phase 1 — C-01: satu blok = satu run/folder [CRITICAL]
`SessionRepository.createRun()`: cek `runGroupKeyToId()`; groupKey sudah ada → return sessionId
existing (fold ke run yang sama), nextId lanjut → `treeName` selalu unik. Backstop = unique index
(Phase 0). Toast "Melanjutkan run blok X". Re-shoot manual-ID dalam run yang sama tetap boleh
(overwrite disengaja, depth dibersihkan H-02).

## Phase 2 — Kebenaran depth [C/H] (jalur Orbbec, fail-safe)
- **H-02** `CaptureFlowScreen.kt:449` else-branch → hapus `.raw`+`.json` lama saat side tanpa depth.
- **H-01** `OrbbecManager`: flag `depthAlignApplied` di `setAlignMode` → `alignedTo` "color"/"none".
- **ORB-05** `encodeDepthFrame`: Y16 size ≠ w×h×2 → drop depth (RGB-only + WARN), **bukan** throw.
- **H-03** `pipeline.getCameraParam()` (try/catch) → sidecar objek `calibration` (field baru default
  absen). Verifikasi nama field `CameraParam` via `javap` sebelum menulis kode.

## Phase 3 — Permukaan gagal-senyap [H]
- **H-05** `SafMirrorStore.isFolderAccessible()` + cek Boolean di `ResultsScreen.exportGated`.
- **H-04** `FolderResumeImporter.copyDepthToPrimary()` sejajar `copyImageToPrimary()`.
- **H-07** tiga `onClick` menu More (`CarouselScreen`) → save-lalu-navigate.
- **H-08** `detectCurrentSide`: capture `idx` sebelum `detect()`, tulis ke `updatedSides[idx]`.

## Phase 4 — Build + label hygiene
- **ORB-09** `build.gradle.kts` release: `isMinifyEnabled=false`, `isShrinkResources=false`.
- **D4-11** `YoloParser.serialize` `.coerceIn(0f,1f)`.

## Phase 5 — Bump versi
- `build.gradle.kts` `val majorMinor = "0.3"`.

## Verifikasi
1. `assembleDebug` sukses (gate). 2. `testDebugUnitTest` lulus. 3. Static trace tiap caller.
4. Checklist on-device §8 (dijalankan user saat kamera ada — H-01 align log, C-01 collision, H-04 ZIP
   depth, ORB-05 size, H-06 EXIF, H-05 SAF putus).

**Batas kejujuran:** code-level terverifikasi (kompilasi+unit+trace); runtime kamera dibuat
fail-safe & isolated tapi belum terverifikasi runtime → divalidasi checklist §8.

## Di luar cakupan
- H-06 fix conditional pada hasil cek EXIF on-device.
- MED/LOW di luar §9 ditunda.

---

## Status — SEMUA SELESAI (code-level), build v0.3.36 hijau
| Fix | Phase | Status | File utama |
|---|---|---|---|
| H-09 DB safety | 0 | ✅ | PalmAnnotateDatabase.kt, Entities.kt, build.gradle.kts |
| C-01 groupKey unik | 1 | ✅ | SessionRepository.createRun + Entities (SessionDao.getByGroupKey) |
| H-02 hapus depth basi | 2 | ✅ | CaptureFlowScreen (lokal) + SessionRepository.mirrorSafArtifacts (SAF) |
| H-01 alignedTo jujur | 2 | ✅ | OrbbecManager (depthAlignApplied) |
| ORB-05 validasi ukuran | 2 | ✅ | OrbbecManager.encodeDepthFrame (drop depth, RGB tetap) |
| H-03 simpan kalibrasi | 2 | ✅ | OrbbecManager.getCameraParam → sidecar (raw b64 + dump) |
| H-05 SAF fail-loud | 3 | ✅ | ResultsScreen (isFolderAccessible + requireSafWrite) |
| H-04 resume depth | 3 | ✅ | FolderResumeImporter.copyDepthToPrimary |
| H-07 save di More menu | 3 | ✅ | CarouselScreen (saveAndExit) |
| H-08 detect race | 3 | ✅ | CarouselScreen.detectCurrentSide (captured idx) |
| ORB-09 R8 off | 4 | ✅ | build.gradle.kts release |
| D4-11 clamp YOLO | 4 | ✅ | YoloParser.serialize (coerceIn) |
| bump 0.3 | 5 | ✅ | build.gradle.kts (v0.3.36) |

**Verifikasi code-level:** `assembleDebug` SUKSES (2m2s), `testDebugUnitTest` SUKSES, schema v3
ter-export (`app/schemas/.../3.json`, 2 unique index). **Belum diverifikasi runtime kamera** →
checklist §8 saat kamera tersedia.

**Catatan:** `app/schemas/` sekarang berisi schema JSON (exportSchema=true) — sebaiknya di-commit ke
git supaya migration berikutnya bisa divalidasi. `Pipeline.getCameraParam()` ditandai deprecated oleh
AAR (masih berfungsi; dipakai fail-safe di try/catch).
