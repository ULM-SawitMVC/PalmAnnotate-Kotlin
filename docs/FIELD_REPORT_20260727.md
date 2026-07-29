# Laporan Lapangan — 27 Juli 2026 (koleksi DAMIMAS blok A21B)

Basis: dua ZIP hasil ekspor (`Dataset-27July2026/`), storage aplikasi pada tablet
`25097RP43G` (serial ADB `b98cea56`, Android 16), bytecode AAR Orbbec
`obsensor_v2.0.6_2026031801_release`, dan pengukuran langsung di tablet yang sama
(malam 27 Juli 2026, basis data 42 pohon utuh, kamera Orbbec tidak terpasang).
Versi APK saat koleksi: 0.3.41 (versionCode 41).

## 1. Crash lapangan — SDK Orbbec, bukan I/O

Tiga berkas di `<app-external>/PalmAnnotate/crash/` (09:17:19, 11:33:25, 14:11:12 WITA)
berisi stack trace yang identik.

```
USB_DEVICE_ATTACHED  →  com.orbbec.internal.Enumerator$1.onReceive
                     →  Enumerator.requireUsbPermission()          (Enumerator.java:182)
                     →  Context.registerReceiver(receiver, filter) ← overload 2 argumen
                     →  SecurityException: One of RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED
                        should be specified…
                     →  RuntimeException di dispatch broadcast → proses mati (thread main)
```

**Bukan** masalah I/O, bukan beban tulis/baca, bukan pengaruh kode aplikasi kita.
`OrbbecManager.registerUsbHotplugReceiver` (`:236`) dan `requestPermission` (`:307`)
sudah memakai `RECEIVER_NOT_EXPORTED` dengan benar.

### Syarat pemicu (terverifikasi dari bytecode)

`javap -c 'com.orbbec.internal.Enumerator$1'`, cabang `USB_DEVICE_ATTACHED`:

- `usbManager.hasPermission(device) == true` → kirim pesan 101 ke handler internal. Aman.
- `hasPermission(device) == false` → `requireUsbPermission()` → `registerReceiver` tanpa
  flag → crash.

Sejak Android 14 (API 34) flag receiver wajib untuk aplikasi `targetSdk >= 34`.
`app/build.gradle.kts:31` memakai `targetSdk = 34`, perangkat Android 16 → aturan berlaku.

Jadi crash terjadi bila: **ada event attach perangkat Orbbec, proses aplikasi masih hidup
dengan `OBContext` aktif, dan aplikasi sedang tidak memegang izin USB untuk perangkat itu.**
Android mencabut izin USB setiap kali perangkat dicabut, sehingga setiap replug
(kabel tersenggol, kamera reset, renegosiasi USB host) memenuhi syarat tersebut.

### Biaya waktu

| Crash (WITA) | Posisi di lini masa koleksi |
|---|---|
| 09:17:19 | 23 menit sebelum pohon pertama (0001 @ 09:40) — saat kamera pertama dicolok |
| 11:33:25 | di tengah jeda 31 menit antara pohon 0008 dan 0009 |
| 14:11:12 | di tengah jeda 123 menit antara pohon 0015 dan 0016 |

### Dampak terhadap data

Nihil kerusakan. Seluruh SHA-256 di `manifests/` cocok dengan berkas nyata (528 sisi,
0 mismatch). Publikasi berkas menulis baris Room setelah berkas lengkap
(`AndroidStorageManager.kt:98-100`), jadi crash meninggalkan berkas yatim, bukan pohon
campur. Yang hilang hanya anotasi yang belum tersimpan di carousel saat crash — `autoSave()`
hanya menyala pada ganti sisi dan ganti mode.

### Opsi perbaikan

| Opsi | Isi | Pertimbangan |
|---|---|---|
| **A** | Turunkan `targetSdk` 34 → 33 | Menghapus akar masalah: Android berhenti menegakkan aturan flag di bawah API 34. Satu baris, tetapi perilaku seluruh aplikasi kembali ke mode kompatibilitas API 33 dan perlu diuji ulang |
| **B** | Tutup `OBContext` saat keluar layar capture; percepat penutupan pada DETACH | `Enumerator.close()` memanggil `unregisterReceiver` (terverifikasi di bytecode), jadi tanpa context aktif tidak ada receiver yang meledak. Tidak menutup kasus attach saat kamera sedang dipakai; penutupan DETACH saat ini asinkron di `cameraExec` sehingga masih ada celah balapan |
| **C** | Jaring pengaman main looper yang menelan `SecurityException` dari dispatch broadcast | Aplikasi tetap hidup; menangani gejala, bukan penyebab |
| **D** | Repack `classes.jar` dengan `Enumerator` yang benar, atau minta rilis baru ke Orbbec | Paling bersih, tetapi kita memelihara SDK hasil modifikasi |

Rekomendasi: **A sebagai perbaikan utama, B sebagai pelapis.** Keduanya hanya dapat
dibuktikan dengan mencolok dan mencabut kamera Orbbec pada tablet — tidak dapat
direproduksi tanpa perangkat keras.

## 2. Label kosong — memang tidak ada tandan

Dua belas `.txt` berukuran 0 byte pada paket 42 pohon:

| Pohon | Sisi kosong |
|---|---|
| 0011 | 4 |
| 0017 | 3 |
| 0018 | 2, 3, 4 |
| 0025 | 2, 4 |
| 0028 | 2, 4 |
| 0029 | 2 |
| 0034 | 4 |
| 0036 | 1 |

Bukan akibat crash:

1. Kedua belas fotonya diperiksa satu per satu — tidak ada tandan yang terlihat dari sisi
   tersebut.
2. `annotlog` koheren: `DAMIMAS_A21B_0018_2.json` berisi `"final": []` dengan
   `savedAt` 1785133317804, sedangkan sisi 1 pohon yang sama berisi satu bbox B4 dengan
   `savedAt` 1785133317802 — keempat sisi tersimpan dalam satu operasi.
3. Pohon 0017 dan 0018 justru disimpan pukul 14:19 dan 14:21, yaitu **setelah** crash 14:11.

## 3. Verifikasi paket dataset

| Aspek | Hasil |
|---|---|
| Cakupan depth | 528/528 sisi punya `.raw` + `.json` (42 + 90 pohon) |
| Format | `Y16`, `uint16le`, 848×480, 814.080 byte = 848×480×2 tepat |
| `valueScale` | 1 → nilai piksel langsung milimeter |
| Isi depth | valid pixel 60–89% per frame, median jarak 2,5–2,8 m |
| Integritas | seluruh SHA-256 manifest cocok; 0 file RGB/depth kembar |
| Kalibrasi (H-03) | intrinsic depth & color, distortion, extrinsic (baseline −23,67 mm), plus blob mentah |
| `alignedTo` (H-01) | `"color"`, kini diturunkan dari `getD2CDepthProfileList`, bukan konstanta |

### Masalah yang tersisa

1. **Tabrakan nama antar-perangkat.** Kedua ZIP berbagi 42 nama pohon identik
   (`DAMIMAS_A21B_0001`…`_0042`) dengan isi berbeda (GPS dan timestamp berbeda). Paket
   42 pohon berasal dari tablet `25097RP43G`; paket 90 pohon dari tablet lain. Keduanya
   konsisten secara internal, tetapi ekstraksi ke satu folder menimpa 168 sampel tanpa
   peringatan. Beri prefix run/perangkat sebelum diserahkan ke ML engineer.
2. **Resolusi depth 848×480 ≠ RGB 1280×800**, rasio aspek juga berbeda (1,77 vs 1,60).
   Uji numerik untuk memastikan buffer benar-benar D2C-aligned (korelasi gradien dan
   irisan langit-vs-invalid pada 123 frame) **tidak konklusif**; selisih antara hipotesis
   "peregangan penuh frame" dan "remap pinhole" mencapai ±50 piksel di tepi atas/bawah.
   Kalibrasi tersimpan sehingga dapat diselesaikan offline, tetapi harus diverifikasi
   sekali dengan objek bertepi tegas.
3. **GPS satu titik per sesi.** 42 pohon memakai koordinat identik, demikian pula 90 pohon
   di paket lain (jarak antar-keduanya ±175 m). Perilaku D6-14: last-known fix tanpa batas
   umur. Field `operator` kosong di seluruh metadata.
4. **ZIP tanpa `data.yaml`/`classes.txt`/README** (D4-07) — aturan dekode depth dan pemetaan
   kelas masih tersirat.

## 4. Bug performa dan UX yang dilaporkan operator

Empat keluhan dari pemakaian langsung di lapangan, ditelusuri sampai penyebabnya.
Kolom status memisahkan yang terbukti di perangkat dari yang terbaca di kode.

| Keluhan | Penyebab | Status |
|---|---|---|
| Foto sisi hilang, harus ulang dari sisi 1 | `load()` menghapus seluruh state setiap kali layar capture disusun ulang | **Direproduksi di tablet** |
| Next Tree / Capture Next sangat lag | Navigasi menunggu penulisan ulang ±9,4 MB ke SAF + verifikasi baca-ulang, di dalam satu kunci global | **Terukur: 9,6 detik** |
| Aplikasi "not responding" | Main thread memblokir pada `stateLock` Orbbec yang dipegang panggilan native SDK | **Ada jejak ANR lapangan** |
| Selalu kembali ke kamera tablet | `captureSource` default `PHONE_CAMERA` pada setiap ViewModel baru | **Terlihat di layar uji** |
| Kamera Orbbec berat saat pertama dibuka | Retry berlapis + preview lewat JPEG→Base64→decode per frame | Terbaca di kode |
| Scroll daftar pohon berat | Frame median 19 ms pada anggaran 16,7 ms; APK lapangan adalah build debug | **Terukur di tablet** |

Rotasi layar **bukan** penyebab. Diuji khusus: state capture tetap utuh setelah rotasi,
karena `orientation|screenSize|screenLayout|smallestScreenSize` sudah didaftarkan di
`AndroidManifest.xml`. Hal ini konsisten dengan laporan operator bahwa tablet kedua tetap
lag walaupun orientasinya dikunci.

### 4.1 Foto sisi hilang sebelum tersimpan — direproduksi

`CaptureFlowScreen.kt:687`:

```kotlin
LaunchedEffect(sessionId) {
    viewModel.load(sessionId)     // ← :355, menghapus SEMUANYA
    permLauncher.launch(...)
}
```

`load()` (`:361-371`) menjalankan `capturedImages.clear()`, `capturedDepths.clear()`,
`capturedSources.clear()`, lalu `currentSide = 0` dan `phase = SIDES`.

ViewModel **bertahan** ketika Activity disusun ulang, tetapi composable-nya dibangun ulang,
sehingga `LaunchedEffect` menyala lagi dan `load()` membuang foto yang sudah diambil.

**Reproduksi (tablet `b98cea56`):**

1. Buka Capture, ambil foto sisi 1 → thumbnail muncul, tombol Retake/Continue tampil.
2. Picu penyusunan ulang Activity — dipakai perubahan `font_scale`, karena `fontScale`
   tidak ada di `android:configChanges`.
3. Layar kembali ke "View 1/4", thumbnail hilang, kembali ke mode preview.

Rotasi diuji terpisah dan foto tetap ada, sehingga jalur config-change yang terdaftar bersih.

**Pemicu di lapangan** adalah apa pun yang menyusun ulang Activity atau mematikan proses:

- crash USB-attach pada Bagian 1 (proses mati → seluruh state hilang),
- sistem mematikan proses saat aplikasi di latar belakang (aplikasi memakai ±190 MB PSS,
  ditambah bitmap preview Orbbec),
- perubahan konfigurasi yang tidak didaftarkan.

Yang memperburuk: state ini tidak disimpan di mana pun — tidak ada `SavedStateHandle`,
tidak ada `rememberSaveable`, tidak ada draft di disk. Padahal JPEG-nya sudah ada di
`cacheDir` (berkas `orbbec_20260727_155843_306.jpg` dari sesi lapangan masih tersimpan
di sana). Foto fisiknya selamat; hanya rujukannya yang dibuang.

Arah perbaikan: buat `load()` idempoten (tidak membersihkan bila run yang sama sudah
dimuat) dan simpan draft ringan (path cache + depth per sisi) agar kematian proses pun
dapat dipulihkan.

### 4.2 Next Tree / Capture Next menahan navigasi sampai SAF selesai

Kedua tombol bermuara ke jalur yang sama:

- Carousel → `saveAndExit()` (`CarouselScreen.kt:298`) → `repo.saveSession()` **lalu**
  `repo.saveOutputJson()` → baru `onDone()` yang menavigasi.
- Results → `finishAndThen()` (`ResultsScreen.kt:99`) → `repo.saveOutputJson()` → baru
  `onNavigate()`.

`saveOutputJson` (`SessionRepository.kt:815`) memanggil
`writeAnnotationRevision(..., awaitSaf = safTreeUri != null)`. Karena export folder
dikonfigurasi (`content://com.android.externalstorage.documents/tree/primary:Documents/Dataset`),
`awaitSaf` selalu **true**, sehingga sebelum kamera terbuka aplikasi harus menyelesaikan:

1. `mirrorSafArtifacts(..., forceMediaOverwrite = true)` (`:718`) — menulis ulang **seluruh**
   media pohon itu lewat SAF walaupun sudah ada: 4 JPEG (±1,4 MB) + 4 depth `.raw` (3,2 MB)
   + 4 sidecar + 4 label + 4 annotlog.
2. Menulis metadata, Output JSON, dan manifest.
3. `verifySafPackageReadBack` (`:650`) — membaca kembali semuanya dari SAF dan menghitung
   SHA-256 setiap berkas.

Total sekitar **9,4 MB lalu lintas DocumentsProvider ditambah belasan SHA-256 per pohon**,
seluruhnya di dalam satu kunci global `ArtifactCoordinator` (satu `Mutex` yang juga dipakai
autosave, hapus pohon, dan ekspor ZIP). Navigasi baru terjadi setelah semuanya selesai.

**Pengukuran di tablet `80557c5b` (run 90 pohon, kamera tablet, Orbbec tidak terpasang):**

| Peristiwa (jam perangkat) | Waktu |
|---|---|
| Ketukan Next Tree diterima aplikasi (`MIUIInput ACTION_DOWN`) | 23:23:13.606 |
| Aktivitas CameraX pertama (`QuirkSettings`) | 23:23:23.209 |
| `Camera2CameraImpl: Opening camera` | 23:23:23.330 |
| `CameraDevice.onOpened()` | 23:23:23.540 |

**9,60 detik tanpa satu pun aktivitas kamera** antara ketukan dan mulainya CameraX; kamera
sendiri hanya butuh 0,33 detik untuk terbuka. Seluruh selisih itu adalah `saveAndExit`.
Aplikasi sudah berjalan dan melakukan autosave sebelumnya, sehingga cache direktori
`SafMirrorStore` kemungkinan besar sudah panas — artinya 9,6 detik ini mendekati **biaya
tetap per pohon**, bukan biaya cache dingin. Di lapangan angkanya lebih buruk karena kamera
Orbbec harus dibuka setelahnya (Bagian 4.4) dan antrean SAF latar belakang masih terisi.

**Mengapa makin berat seiring bertambahnya pohon.** `SafMirrorStore` mencatat isi direktori
sekali per proses, tetapi biaya pendataan awalnya sebanding dengan jumlah berkas di direktori
itu. Dengan 42 pohon, `dataset/depth/field` berisi 336 berkas dan `Output TXT/field` berisi
168 berkas. KDoc `SafMirrorStore.kt:16-24` sudah mencatat pengukuran lama: satu label kecil
memakan ±2.000 ms ketika direktori berisi 228 berkas.

**Mengapa force-stop menyembuhkannya.** Menutup paksa membuang antrean SAF latar belakang
yang menumpuk sekaligus melepas pemegang kunci global; setelah restart, aksi pertama tidak
lagi mengantre di belakang pekerjaan pohon sebelumnya.

Ini adalah harga dari pengerasan integritas data (`30dcb86`, `dc1e6a6`): verifikasi
baca-ulang memang menjamin paket di folder ekspor benar, tetapi dipasang tepat di jalur yang
dilewati operator setiap pohon. Perbaikannya tidak harus mengorbankan integritas —
verifikasi dapat dipindahkan ke latar belakang dengan penanda status per pohon, dan
`forceMediaOverwrite` tidak perlu menulis ulang media yang hash-nya sudah cocok.

### 4.3 Sumber kamera selalu kembali ke kamera tablet

Dua mekanisme berbeda, keduanya nyata.

**Yang terjadi setiap pohon.** `captureSource` dideklarasikan
`by mutableStateOf(CaptureSource.PHONE_CAMERA)` (`CaptureFlowScreen.kt:119`) di dalam
ViewModel. Setiap "Next Tree"/"Capture Next" menavigasi ke rute `capture/{runId}` yang baru
(`Navigation.kt:111`, `:153`), sehingga NavBackStackEntry baru membuat ViewModel baru dan
pilihan Orbbec hilang. Tidak ada penyimpanan preferensi sumber kamera sama sekali. Pada uji
di perangkat, chip di pojok kanan atas memang terbaca "Phone" saat layar capture dibuka.
Ini bukan fallback akibat kegagalan, melainkan nilai bawaan.

**Yang lebih parah dan menuntut force-stop.** Pengaman flapping di
`OrbbecManager.kt:182-206`: dua peristiwa detach dalam 20 detik menaikkan `degradeLevel`;
pada level 2 `isAvailable()` mengembalikan false dan Orbbec lenyap dari UI. Pemulihannya,
`maybeResetFlapLadder()`, hanya bekerja apabila attach terjadi **lebih dari 30 detik**
setelah detach terakhir — syarat yang tidak pernah terpenuhi pada colok-ulang cepat atau
reset sesaat pada bus USB. Ladder itu lalu bertahan sampai proses dimatikan. Tombol Reset
yang seharusnya menyembuhkannya hanya dirender pada cabang "no device" (ORB-12).

### 4.4 Pembukaan kamera Orbbec berat

**Jalur pembukaan** (`openSdkLocked`, `OrbbecManager.kt:470`): hingga 3 percobaan
(`OPEN_RETRIES`), masing-masing membuat `OBContext`, lalu `queryDevicesWithRetry` yang
mencoba 8 kali dengan jeda 250 ms (2 detik penuh bila perangkat lambat muncul), menyusun
daftar profil, dan memanggil `pipeline.start`. Di antara percobaan ada jeda 400 ms
(`OPEN_RETRY_SETTLE_MS`). Pada kasus terburuk ini menumpuk menjadi beberapa detik sebelum
preview muncul.

**Jalur preview** — bagian paling boros, warisan arsitektur WebView lama. Per frame RGB
(setiap `PREVIEW_INTERVAL_MS` = 80 ms, ±12,5 fps):

```
frame SDK → kompresi JPEG (maks 720 px, q60) → encode Base64 → String
          → Base64.decode → BitmapFactory.decodeByteArray → ImageBitmap → state Compose
```

(`emitPreview` `:460` → `CaptureFlowViewModel.initOrbbec` `:156-169`.) Aplikasi native tidak
perlu menyeberangkan frame dalam bentuk Base64; bitmap dapat diserahkan langsung. Dengan
bentuk sekarang setiap frame mengalokasikan bitmap ±1,3 MB — sekitar 16 MB per detik sampah
memori yang harus dipungut GC selama kamera menyala — dan decode berjalan di thread pump
lalu memicu rekomposisi Compose 12,5 kali per detik.

### 4.5 Aplikasi terasa berat secara umum (scroll daftar pohon)

Pengukuran pada tablet, daftar 42 pohon, aplikasi baru dijalankan, tanpa kamera:

| Metrik | Nilai |
|---|---|
| Frame median | **19 ms** (anggaran 60 Hz = 16,7 ms) |
| Persentil 90 | 38 ms |
| Persentil 99 | 89 ms |
| High input latency | 796 kejadian |

Daftar itu sendiri sepele: `LazyColumn` dengan key, tanpa gambar, hanya teks dan ikon
(`SessionDetailScreen.kt:118-145`). Jadi biayanya bukan pada data, melainkan overhead runtime.

Faktor struktural terbesar: **APK lapangan adalah build debug**. Paket di kedua tablet
adalah `dev.sawitulm.palmannotate.debug`, dan `app/build.gradle.kts` hanya mendefinisikan
build type `debug` dan `release`, sehingga `isDebuggable = true`. ART mematikan sebagian
optimasi untuk aplikasi debuggable dan Compose berjalan jauh lebih lambat pada mode ini;
pada layar 2136×3200 selisihnya sangat terasa.

Jalan keluar tanpa melanggar larangan R8: tambahkan build type ketiga (misalnya `field`)
dengan `isDebuggable = false`, `isMinifyEnabled = false`, `isShrinkResources = false`,
memakai signing config debug. R8 tetap mati sesuai CLAUDE.md, tetapi aplikasi berjalan pada
kecepatan mendekati release. Penambahan baseline profile memperbaikinya lebih jauh.

**Bukan kebocoran memori.** Enam siklus buka-tutup layar capture menunjukkan PSS stabil di
190 MB, `Views` berhenti di 20, `Activities` tetap 1. Lag yang menumpuk berasal dari antrean
pekerjaan SAF pada 4.2, bukan dari objek yang bocor.

### 4.6 ANR lapangan — main thread menunggu kunci Orbbec

Perangkat `80557c5b` menyimpan satu catatan ANR nyata dari hari koleksi. Diambil dengan
`adb shell dumpsys dropbox --print data_app_anr`:

```
2026-07-27 15:54:03 data_app_anr
Process: dev.sawitulm.palmannotate.debug   PID 28702
Package: dev.sawitulm.palmannotate.debug v41 (0.3.41)
Foreground: Yes   Process-Runtime: 183876 ms
```

Tumpukan thread `main` pada saat ANR:

```
"main" prio=5 tid=1 Blocked
  at OrbbecManager.stopPump(OrbbecManager.kt:408)
  - waiting to lock <0x0434f83d> (a java.lang.Object) held by thread 49
  at OrbbecManager.stopPreview(OrbbecManager.kt:342)
  at CaptureFlowViewModel$stopOrbbecPreview$1.invokeSuspend(CaptureFlowScreen.kt:245)
  ...
  at CaptureFlowViewModel.stopOrbbecPreview(CaptureFlowScreen.kt:244)
  at CaptureFlowViewModel.selectSource(CaptureFlowScreen.kt:176)
  at CaptureFlowScreenKt$CaptureFlowScreen$4$3...(CaptureFlowScreen.kt:730)   ← chip sumber kamera
```

Pemegang kuncinya, thread 49 (`PalmAnnotate-Orbbec`, executor kamera berthread tunggal),
sedang tertahan di kode native SDK:

```
"PalmAnnotate-Orbbec" daemon tid=49 Native
  native: __futex_wait_ex_owner → std::recursive_mutex::lock
  native: libobsensor::AndroidUsbDeviceManager::closeUsbDevice+52
```

**Rantai sebabnya:**

1. Operator menekan chip sumber kamera di pojok kanan atas (`CaptureFlowScreen.kt:730`).
2. `selectSource` (`:176`) memanggil `stopOrbbecPreview()`.
3. `stopOrbbecPreview` (`:244`) memakai `viewModelScope.launch { ... }` **tanpa dispatcher**.
   `viewModelScope` memakai `Dispatchers.Main.immediate`, sehingga badan coroutine mulai
   dieksekusi **di thread UI**.
4. `OrbbecManager.stopPreview` (`:342`) memanggil `stopPump()` lebih dulu, di luar
   `withContext(cameraDispatcher)`. `stopPump` (`:408`) membuka blok
   `synchronized(stateLock)` — monitor JVM yang memblokir, bukan kunci suspend.
5. `stateLock` sedang dipegang oleh executor kamera yang tertahan di dalam
   `closeUsbDevice` milik SDK Orbbec. Thread UI ikut membeku sampai panggilan native itu
   selesai → dialog "not responding".

Ini penyebab yang **berbeda** dari Bagian 4.2. Keduanya nyata: 4.2 membuat navigasi lambat
secara konsisten, sedangkan 4.6 membekukan UI sepenuhnya dan bergantung pada waktu.

Cacatnya ada dua lapis, dan keduanya perlu diperbaiki:

- **Pemanggil**: pekerjaan yang memblokir dijalankan lewat `viewModelScope` tanpa dispatcher.
  Perlu `viewModelScope.launch(Dispatchers.IO)`, atau lebih baik `OrbbecManager.stopPreview`
  membungkus seluruh badannya dalam `withContext(cameraDispatcher)`.
- **`OrbbecManager`**: `stateLock` dipegang melintasi panggilan native yang panjang
  (`closeSdkLocked`, `acquireStreamLocked` termasuk `pipeline.start`). Karena semua akses SDK
  sudah dikurung di `cameraExec` yang berthread tunggal, kunci itu sebagian besar redundan;
  `pumpRunning` cukup dijadikan `@Volatile` dan `streamPump` sebuah `AtomicReference` agar
  `stopPump()` tidak perlu mengambil monitor sama sekali.

Potret CPU pada saat ANR juga memperkuat Bagian 4.2 — konsumen terbesar bukan aplikasi kita:

```
119% com.google.android.providers.media.module   (83% user + 35% kernel), WM.task-4 72%
 39% dev.sawitulm.palmannotate.debug
```

MediaProvider bekerja tiga kali lebih keras daripada aplikasi kita, yaitu beban dari mirror
SAF yang menulis dan membaca ulang paket pohon.

### 4.7 Centang "complete" yang kosong bukan berarti data hilang

Operator melaporkan banyak pohon tanpa tanda centang karena "Next Tree" terlalu berat
sehingga tidak ditekan. Pemeriksaan di perangkat menunjukkan datanya tetap lengkap:

```
metadata 90   Output JSON 90   manifests 90
images/field 360   labels/field 360   depth/field 720
```

Penyebabnya: `saveSession` — yang dipanggil oleh **autosave** setiap ganti sisi — sudah
menjalankan `writeAnnotationRevision(awaitSaf = false)`, sehingga Output JSON dan manifest
tetap ditulis. Yang hanya dilakukan `saveOutputJson` adalah menyetel `isComplete = true`
(`SessionRepository.kt:832`), dan itulah sumber tanda centang. Jadi centang kosong murni
penanda alur kerja, bukan indikasi artefak yang hilang — konsisten dengan ZIP 90 pohon yang
seluruh SHA-256-nya cocok.

Satu hal yang perlu diperhatikan: nama export folder pada perangkat ini terbaca
**"Dataset (1)"**, yang berarti pernah terbentuk folder duplikat. Perlu dipastikan operator
memilih folder yang benar sebelum koleksi berikutnya.

### 4.8 Urutan perbaikan yang disarankan

Berdasarkan rasio dampak terhadap risiko:

1. **Jangan jalankan pekerjaan Orbbec yang memblokir di thread UI** (4.6) — ini satu-satunya
   temuan yang membekukan aplikasi sampai ANR. Perbaikan dispatcher bersifat lokal;
   penghapusan `stateLock` dari `stopPump` menyusul setelahnya.
2. **`load()` idempoten** (4.1) — mencegah hilangnya kerja operator. Perubahan kecil dan
   terisolasi.
3. **Pindahkan verifikasi baca-ulang SAF ke latar belakang** (4.2) — memangkas 9,6 detik per
   pohon tanpa melemahkan integritas, asalkan status verifikasi per pohon tetap ditampilkan.
4. **Simpan pilihan sumber kamera** (4.3) — satu preferensi di `InputCache`/DataStore.
5. **Build type `field`** (4.5) — `isDebuggable = false`, R8 tetap mati.

### 4.9 Catatan pengujian

Pada tablet `b98cea56` (42 pohon): dua foto uji tersimpan di `cacheDir` (tidak menyentuh
dataset); `font_scale` dan pengaturan rotasi dikembalikan ke nilai semula.

Pada tablet `80557c5b` (90 pohon): satu penekanan "Next Tree" pada pohon `DAMIMAS_A21B_0090`
untuk mengukur latensi. Penekanan itu menulis ulang artefak pohon tersebut dengan isi
identik dan menandainya complete; paket 90 pohon sudah diekspor dan diverifikasi hash
sebelumnya. Tidak ada pohon baru yang dibuat (jumlah metadata tetap 90).

Temuan sampingan: beberapa berkas sumber (`CarouselScreen.kt`, `OrbbecManager.kt`)
mengandung byte non-UTF-8 sehingga ripgrep memperlakukannya sebagai berkas biner. Tidak
berdampak pada runtime, tetapi menghambat perkakas pencarian.

## 5. Handoff untuk sesi berikutnya

Bagian ini ditulis agar sesi lain dapat melanjutkan tanpa mengulang investigasi.

### 5.1 Status saat dokumen ini ditulis

**Belum ada satu baris kode pun yang diubah.** Seluruh temuan di atas masih berupa analisis.
Yang berubah di repositori hanyalah dokumen ini. `Dataset-27July2026/` masih untracked.

> **Pemutakhiran 28 Juli 2026 — bagian ini sudah usang.** Perbaikan dikerjakan pada
> `b99a3c5` (v0.3.43) dan `9a43483` (v0.3.44). Status per butir ada di §5.7 di bawah.
> Paragraf di atas sengaja dipertahankan sebagai catatan sejarah investigasi.

Perangkat uji:

| Serial ADB | Isi | Catatan |
|---|---|---|
| `b98cea56` | run 42 pohon | punya 3 berkas crash di `PalmAnnotate/crash/` |
| `80557c5b` | run 90 pohon | punya jejak ANR di dropbox; export folder terbaca "Dataset (1)" |

Keduanya `25097RP43G`, Android 16, paket `dev.sawitulm.palmannotate.debug` v0.3.41 (41).

### 5.2 Antrean pekerjaan, berurutan

Setiap butir menyebut berkas dan baris supaya tidak perlu mencari ulang.

**(1) Hentikan pekerjaan Orbbec yang memblokir di thread UI** — Bagian 4.6

- `CaptureFlowScreen.kt:244` `stopOrbbecPreview()` → beri dispatcher, atau
- `OrbbecManager.kt:342` `stopPreview()` → bungkus seluruh badan dengan
  `withContext(cameraDispatcher)` sehingga `stopPump()` tidak pernah jalan di pemanggil.
- Lanjutan: `OrbbecManager.kt:408` `stopPump()` jangan mengambil `synchronized(stateLock)`.
  `pumpRunning` → `@Volatile`, `streamPump` → `AtomicReference`. Semua akses SDK sudah
  dikurung di `cameraExec` berthread tunggal, jadi kunci itu sebagian besar redundan.
- Periksa juga pemanggil lain yang menyentuh SDK dari thread UI: `initOrbbec` (`:146-172`)
  memanggil `orbbec.start()` dan `orbbec.isAvailable()` langsung di konstruktor ViewModel.
- Cara memverifikasi: colok Orbbec, buka layar capture, tekan chip sumber kamera
  berulang-ulang sambil `adb logcat | Select-String "Long monitor contention"`. Tidak boleh
  ada kontensi pada thread `main`.

**(2) `load()` idempoten** — Bagian 4.1

- `CaptureFlowScreen.kt:355` `load(runId)`: bila `run?.sessionId == runId` dan
  `capturedImages` sudah terisi, jangan bersihkan apa pun.
- Lanjutan: simpan draft (path cache + depth per sisi) agar kematian proses bisa dipulihkan.
- Cara memverifikasi: ulangi reproduksi 4.1 (ambil foto sisi 1 →
  `adb shell settings put system font_scale 1.15` → thumbnail harus tetap ada →
  kembalikan `font_scale` ke 1.0).

**(3) Verifikasi baca-ulang SAF keluar dari jalur navigasi** — Bagian 4.2

- `SessionRepository.kt:815` `saveOutputJson` → `writeAnnotationRevision(awaitSaf = ...)`.
- `verifySafPackageReadBack` (`:650`) dipindah ke `safScope`, hasilnya disimpan sebagai
  status verifikasi per pohon (misalnya kolom baru di `TreeEntity`) dan ditampilkan di daftar
  pohon, sehingga integritas tetap dapat dibuktikan tanpa menahan operator.
- `mirrorSafArtifacts` `forceMediaOverwrite = true` (`:718`) → lewati berkas yang hash-nya
  sudah cocok; media RGB dan depth tidak pernah berubah setelah capture.
- Cara memverifikasi: ulangi pengukuran 4.2 dan bandingkan dengan garis dasar **9,60 detik**.

**(4) Simpan pilihan sumber kamera** — Bagian 4.3

- `CaptureFlowScreen.kt:119` `captureSource` → baca nilai awal dari `InputCache`/DataStore,
  tulis pada `selectSource` (`:174`).
- Sekalian: `maybeResetFlapLadder()` (`OrbbecManager.kt:197`) supaya ladder juga turun
  setelah stream berjalan stabil, bukan hanya setelah 30 detik sunyi; dan tombol Reset
  dirender juga saat `unstableSuppressed`, bukan hanya di cabang "no device".

**(5) Build type `field`** — Bagian 4.5

- `app/build.gradle.kts` blok `buildTypes`: tambahkan `field` dengan `isDebuggable = false`,
  `isMinifyEnabled = false`, `isShrinkResources = false`, `signingConfig` debug,
  `applicationIdSuffix` tersendiri agar tidak menimpa aplikasi berisi data lapangan.
- **R8 tetap mati** sesuai CLAUDE.md; `isDebuggable = false` adalah setelan yang berbeda dan
  tidak menyentuh larangan itu.
- Cara memverifikasi: ukur ulang scroll daftar pohon, bandingkan dengan median **19 ms**.

**(6) Crash USB-attach** — Bagian 1, opsi A + B. Belum tersentuh sama sekali.

### 5.3 Saran operasional untuk koleksi berikutnya

Berlaku bahkan bila belum ada satu pun perbaikan kode.

1. **Pertimbangkan mengosongkan export folder selama pengambilan.** `awaitSaf` bernilai true
   hanya ketika export folder terpasang (`SessionRepository.kt:827`). Tanpa export folder,
   Next Tree tidak lagi menunggu SAF. Konsekuensinya salinan yang tahan uninstall hilang,
   jadi wajib diganti dengan ekspor ZIP di akhir hari **sebelum** aplikasi disentuh lagi.
   Ini keputusan operator, bukan anjuran sepihak — risikonya nyata di kedua arah.
2. **Beri izin USB permanen.** Saat dialog "Open PalmAnnotate when this device is connected"
   muncul, centang opsi "always". Bila `hasPermission` bernilai true saat attach, SDK Orbbec
   mengambil cabang yang aman dan crash Bagian 1 tidak terjadi.
3. **Pakai kabel dan sumber daya yang stabil.** Setiap detach yang tidak disadari menaikkan
   ladder flapping dan memicu jalur crash sekaligus.
4. **Bedakan penomoran antar-tablet.** Dua tablet sama-sama memulai dari `0001` untuk
   variety+blok yang sama. Sebelum koleksi berikutnya, sepakati blok berbeda per tablet, atau
   sisipkan token perangkat ke `treeName` (`CaptureFlowScreen.kt:427`).
5. **Periksa export folder sebelum mulai.** Pada `80557c5b` terbaca "Dataset (1)"; pastikan
   yang terpilih adalah folder yang benar.
6. **Setelah hari koleksi, tarik dulu diagnostiknya** sebelum menjalankan aplikasi lagi:
   berkas `PalmAnnotate/crash/` dan `adb shell dumpsys dropbox --print data_app_anr`. Dropbox
   akan terisi ulang dan jejak lama bisa hilang.

### 5.4 Yang masih harus diverifikasi dengan perangkat keras

Tidak satu pun dapat dibuktikan tanpa kamera Orbbec terpasang:

- Apakah perbaikan Bagian 1 benar-benar menghentikan crash saat colok-ulang.
- Durasi pembukaan kamera Orbbec yang sebenarnya (Bagian 4.4) dan berapa lama `stateLock`
  ditahan panggilan native.
- Apakah buffer depth benar-benar D2C-aligned (Bagian 3, butir 2) — perlu satu pemotretan
  objek bertepi tegas, misalnya papan atau kusen pintu, lalu bandingkan tepi pada depth dan RGB.

### 5.5 Perintah untuk mengulang pengukuran

```powershell
$adb='C:\tools\android-sdk\platform-tools\adb.exe'; $d='<serial>'; $p='dev.sawitulm.palmannotate.debug'

# Latensi Next Tree: cari selisih ACTION_DOWN → "Opening camera"
& $adb -s $d logcat -c
# (tekan Next Tree)
& $adb -s $d logcat -d | Select-String 'MIUIInput.*ACTION_DOWN|Camera2CameraImpl.*Opening camera'

# Jank saat scroll
& $adb -s $d shell "dumpsys gfxinfo $p reset"
# (scroll daftar pohon)
& $adb -s $d shell "dumpsys gfxinfo $p" | Select-String 'Total frames|Janky|percentile'

# ANR dan crash
& $adb -s $d shell "dumpsys dropbox --print data_app_anr"
& $adb -s $d shell "ls -l /storage/emulated/0/Android/data/$p/files/PalmAnnotate/crash"

# Kebocoran memori / kebocoran view
& $adb -s $d shell "dumpsys meminfo $p | grep -E 'TOTAL PSS|Views:|Activities:'"
```

### 5.6 Rambu yang tidak boleh dilanggar

- **R8 tetap mati** (`isMinifyEnabled`/`isShrinkResources`) sampai preview Orbbec diverifikasi
  ulang di perangkat — lihat CLAUDE.md.
- **Toleransi nol terhadap bug**: operator tidak punya kesempatan menguji ulang dengan kamera
  Orbbec sebelum hari koleksi berikutnya. Setiap perubahan harus aditif, minimal, terisolasi,
  dan setiap panggilan SDK baru dibungkus `try/catch` yang fail-safe.
- **Jangan menekan Next Tree pada pohon lapangan hanya untuk uji coba** — aksi itu menulis
  ulang artefak dan menyetel `isComplete`. Gunakan run percobaan tersendiri.
- **Jangan memindahkan mirror SAF kembali ke jalur save yang memblokir** (CLAUDE.md); arah
  perbaikan butir (3) justru kebalikannya.

## 5.7 Status perbaikan per 29 Juli 2026

> **Catatan historis.** Angka 288 uji unit di bagian ini adalah bukti pada saat snapshot
> v0.3.55 dibuat. Angka tersebut bukan status HEAD saat ini; lihat addendum §5.8.

Diverifikasi melalui penelusuran seluruh pemanggil, **288 uji unit tanpa kegagalan**, CI build +
instrumentation hijau, serta tablet `b98cea56`. Commit terkini: `e02ce2e` (capture crash-safe dan
artefak durable), `d986824` (identitas lintas perangkat, GPS, operator), `1c69231` (resume SAF dan
pembuatan berkas bersarang), dan `a69d3bd` (annot-log mirror byte-identik). APK yang diuji adalah
`.field` v0.3.55 dengan sertifikat SHA-256 `2430cb54…a1150b5a`.

| Butir | Status | Bukti |
|---|---|---|
| §1 crash `USB_DEVICE_ATTACHED` | **Selesai di kode; colok-ulang Orbbec tetap perlu uji fisik** | AAR vendor di-repack dan SHA-256 dikunci `OrbbecVendorPatchTests.kt`; jalur frame callback tidak diubah |
| §4.1 `load()` idempoten | **Selesai** | Run yang sama tidak lagi membersihkan state capture yang sudah terisi |
| §4.1 draft persistence | **Selesai** | `capture_drafts` + `capture_draft_sides`, staging durable, recovery saat proses mati, dan commit atomik hadir sejak `e02ce2e` |
| §4.2 SAF menahan navigasi | **Selesai** | DB + artefak lokal tetap sinkron; mirror SAF berjalan di `mirrorWorkScheduler` serial dan tidak ditunggu navigasi. Pengukuran ulang baseline Next Tree 9,60 detik masih perlu dilakukan pada run percobaan |
| §4.2 status verifikasi per pohon | **Selesai** | Tabel `mirror_status` menyimpan `PENDING`/`ATTEMPTING`/`VERIFIED`/`FAILED`; status dan galat ditampilkan pada daftar pohon dan Results |
| Regresi resume SAF >35 menit | **Selesai dan terukur** | `readBytesResult` memakai cache lalu satu refresh saat gagal; resume 151 pohon turun menjadi sekitar 3 menit |
| Pembuatan berkas baru di direktori SAF bersarang | **Selesai dan terbukti di perangkat** | Handle child directory dikonversi dan divalidasi sebagai tree-capable. Manifest `DAMIMAS_A21B_0090.json` yang semula tidak ada berhasil dibuat pada v0.3.55 |
| Konsistensi read-back annot-log | **Selesai dan terbukti di perangkat** | Mirror menyalin annot-log lokal, bukan membangunnya ulang dengan `savedAt` baru. Seluruh **23/23** artefak pohon 0090 memiliki SHA-256 lokal = SAF |
| §4.3 preferensi sumber kamera | **Selesai** | `InputCache.lastCaptureUsesOrbbec` mempertahankan pilihan operator |
| §4.3 flap ladder + tombol Reset | **Sebagian** | Reset setelah stream stabil sudah ada; cakupan tombol Reset belum berubah |
| §4.4 buka Orbbec + preview Base64 | **Tidak tersentuh** | Jalur preview live sengaja tidak diubah tanpa verifikasi kamera fisik |
| §4.5 build type `field` | **Selesai** | `field` non-debuggable, application ID terpisah, R8 dan resource shrinking tetap mati |
| §4.6 ANR `stopPump`/`stateLock` | **Selesai untuk gejala ANR** | Stop/join pump tidak lagi menunggu kunci native di main thread; verifikasi colok-ulang tetap diperlukan |
| §3.3 GPS last-known tanpa batas umur | **Selesai** | `GpsProvenance` mencatat status, usia, timestamp, provider, source; freshness diperiksa ulang saat commit |
| §3.3 field `operator` | **Selesai** | Operator disimpan pada run/tree dan diekspor sebagai `UNKNOWN` ketika tidak diisi |
| §3.1 tabrakan nama antar-tablet | **Selesai dengan identitas durable; suffix nama opsional** | `captureSetId`, `deviceToken`, `CaptureSetMergePolicy`, sidecar/manifest/Output JSON/ZIP identity; `nameToken` opt-in |
| §3.4 `data.yaml`/`classes.txt`/README di ZIP | **Tidak dikerjakan** | Tidak ada implementasi pada `app/src/main` |

### Risiko baru yang lahir dari perbaikan itu sendiri

1. **Probe tabrakan SAF dapat mengunci koleksi.** `commitTreePackage` (`SessionRepository.kt:165-172`)
   melempar bila salah satu artefak pohon sudah ada di folder ekspor. Nama pohon berasal dari
   `run.nextId` yang tidak pernah maju setelah gagal, dan pada run `autoId` tidak ada field
   Tree ID untuk menimpanya secara manual; galat hanya muncul sebagai `capture_save_failed`.
   Pemicunya adalah divergensi Room vs folder ekspor — misalnya satu pohon dilewati saat resume.
   **Sengaja tidak diubah:** menghapus `check()` akan membuat aplikasi menimpa paket kemarin
   secara diam-diam, karena pemeriksaan di `:162` hanya melihat Room. Mitigasi: pakai folder
   ekspor kosong, atau pastikan jumlah pohon hasil resume sama persis dengan isi folder.
2. **`FolderResumeImporter` melempar pada paket gagal.** Karena `HomeScreen.kt` menangkapnya,
   `inputCache.resumedFolderUri` tidak pernah disetel, sehingga pemindaian SAF penuh berulang pada
   setiap cold start. **Sengaja dipertahankan** — impor parsial tidak boleh dianggap sukses — tetapi
   sejak 29 Juli laporan paket ditolak dipindahkan ke **setelah** seluruh paket yang sah selesai
   di-commit, supaya satu entri folder yang rusak tidak lagi memblokir pemulihan pohon lain.
3. **AutoSave carousel tetap tidak berjalan pada ON_STOP.** Menambahkannya tidak aman:
   `autoSave()` menyetel `dirty = false` sinkron lalu meluncurkan coroutine di `viewModelScope`,
   sedangkan `saveSession` menghapus manifest lokal sebelum menulis ulang (`SessionRepository.kt:421`).
   Pada Navigation-Compose, ON_STOP terjadi tepat sebelum `onCleared` membatalkan scope itu;
   pembatalan di tengah meninggalkan pohon tanpa manifest — ditolak resume (`FolderResumeImporter.kt:183`)
   dan ditolak preflight ekspor ZIP. Disiplin operasional lebih aman sampai ada perangkat untuk verifikasi.

### Verifikasi perangkat 29 Juli 2026 (tablet `b98cea56`)

`.field` v0.3.55 lalu v0.3.58–v0.3.60 terpasang melalui `install -r`; signer setiap build cocok
dengan aplikasi terpasang (`2430cb54…`) sehingga tidak ada uninstall dan data privat tetap utuh.
Sebelum pemulihan 0089 aplikasi memuat **151 pohon**, `nextId = 153`.
Pohon 0090 disimpan ulang untuk kasus regresi nyata: manifest baru berhasil dibuat di
`Documents/Dataset/dataset/manifests`, tidak ada `UnsupportedOperationException`, tidak ada log
mirror/read-back gagal, dan **23/23** hash artefak lokal sama dengan salinan SAF. Setelah force-stop
dan start ulang, pekerjaan 0090 tidak diantrikan ulang, konsisten dengan status `VERIFIED`.

**Pemulihan pohon 0089 (selesai, v0.3.60).** 19 artefak 0089 diverifikasi di dalam ZIP
`DAMIMAS_A21B_20260728-224359.zip` terhadap manifestnya sendiri (18 hash isi + manifest, 0 selisih),
disalin ke `Documents/Dataset` hanya pada path yang kosong, lalu diverifikasi ulang di tablet
(**19/19** SHA-256 lokal = perangkat). Resume folder kemudian mengimpor tepat satu pohon. Hasil
akhir pada perangkat: **152 pohon, 608 foto, `nextId = 0153`**, dan 0089 tampil dengan 4 sisi.

Tiga cacat lain terungkap justru oleh pemulihan ini dan sudah diperbaiki:

1. Deteksi tabrakan identitas menolak seluruh folder ketika sisi remote legacy (tanpa `captureSet`)
   dipasangkan dengan baris Room yang sudah memiliki identitas — 151 "tabrakan" terhadap paket yang
   diimpor perangkat itu sendiri. Sekarang hanya tabrakan yang benar-benar terbukti yang memblokir.
2. Penjaga capture draft menolak impor karena draft aktif menunjuk pohon berikutnya (0153).
   `commitTreePackage` kini membedakan commit dari draft dan impor resume.
3. Klaim reservation remote pada jalur hapus dibuat best-effort, karena versi fail-closed-nya
   menggagalkan dua test instrumentation dan dapat memblokir penghapusan lokal saat SAF tidak dapat
   dijangkau.

### Masih harus diverifikasi dengan perangkat keras

Efektivitas tambalan AAR saat colok-ulang Orbbec, durasi pembukaan Orbbec, keselarasan D2C depth,
latensi Next Tree terhadap baseline 9,60 detik pada run percobaan, dan median frame varian `field`
terhadap 19 ms. Jalur live preview Orbbec tidak disentuh dalam rangkaian perbaikan ini.

## 5.8 Addendum audit terkini (29 Juli 2026)

Status di bawah ini menggantikan ringkasan operasional yang lebih lama di dokumen ini. Catatan
sebelumnya tetap dipertahankan sebagai bukti bertanggal.

- **HEAD dan tes:** HEAD `6113074` dihitung Gradle sebagai v0.3.61. Build perangkat terakhir
  yang diverifikasi tetap `.field` v0.3.60. Uji unit dipaksa ulang dan lulus **298/298**, dengan
  0 kegagalan, 0 error, dan 0 tes dilewati.
- **AAR Android 14:** SHA-256 AAR yang diuji tetap
  `F20073409C3A63011E5158E59829EA8522A68374D59547A6AFFD7614E79330B1`.
  Inspeksi bytecode `Enumerator.requireUsbPermission()` memperlihatkan guard API 33, flag
  `RECEIVER_NOT_EXPORTED`, dan overload `registerReceiver` tiga argumen. Pendaftaran dua
  argumen lain di kelas itu hanya menerima siaran sistem dan termasuk pengecualian aturan flag
  Android 14. Prosedur pemeriksaannya ada di
  [`app/libs/patches/obsensor-v2.0.6-android14-receiver.md`](../app/libs/patches/obsensor-v2.0.6-android14-receiver.md).
- **Pemulihan perangkat:** pemulihan 0089 selesai pada v0.3.60. Sebanyak 19 artefak telah
  diverifikasi dari ZIP dan di tablet; hasil perangkat menjadi **152 pohon, 608 foto,
  `nextId = 0153`**.
- **Manifest USB:** hanya varian `field` yang mendeklarasikan filter manifest
  `USB_DEVICE_ATTACHED`. Varian `debug` dan `trace` tetap meminta izin melalui runtime
  `UsbManager`.

Tambalan AAR belum dinyatakan lulus uji perangkat keras. Colok-ulang Orbbec, waktu buka kamera,
keselarasan D2C, latensi Next Tree, dan median frame `field` tetap memerlukan uji fisik.
