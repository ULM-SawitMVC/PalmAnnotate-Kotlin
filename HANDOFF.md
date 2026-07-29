# PalmAnnotate Native - Session Handoff

> **Diperbarui:** 29 Juli 2026 (Asia/Makassar)
> Status bukti lengkap ada di [laporan lapangan](docs/FIELD_REPORT_20260727.md#58-addendum-audit-terkini-29-juli-2026).

## Status terkini

- HEAD `6113074` dihitung Gradle sebagai v0.3.61. Build perangkat terakhir yang diverifikasi
  adalah `.field` v0.3.60.
- Uji unit dipaksa ulang dan lulus **298/298**, dengan 0 kegagalan, 0 error, dan 0 tes dilewati.
- Draft persistence, status mirror per pohon, resume SAF, pembuatan berkas SAF bersarang, dan
  pemulihan 0089 sudah selesai. Tablet `b98cea56` berakhir pada **152 pohon, 608 foto,
  `nextId = 0153`**.
- Hanya varian `field` yang mendeklarasikan filter manifest `USB_DEVICE_ATTACHED`. Varian
  `debug` dan `trace` meminta izin USB saat runtime.

## Varian dan distribusi

| Varian | Application ID | Debuggable | Kegunaan |
|---|---|---|---|
| `field` | `dev.sawitulm.palmannotate.field` | tidak | koleksi dataset |
| `debug` | `dev.sawitulm.palmannotate.debug` | ya | pengembangan lokal |
| `trace` | `dev.sawitulm.palmannotate.trace` | ya | diagnostik berdampingan |

Gunakan APK `field` untuk koleksi. `trace` dipublikasikan dengan nama aset
`PalmAnnotate-debug-v<version>.apk`, tetapi package-nya tetap `trace`. Jangan mengganti signer
`field`: `pm uninstall` menghapus data privat aplikasi dan dataset yang tersimpan di dalamnya.

## Uji perangkat keras yang masih terbuka

Tambalan AAR saat colok-ulang Orbbec, waktu buka kamera, alignment D2C, latensi Next Tree, dan
median frame varian `field` belum diuji pada perangkat fisik. Jalur live preview Orbbec tidak
diubah dalam rangkaian perbaikan ini. R8 dan resource shrinking tetap mati sampai preview Orbbec
diverifikasi ulang pada perangkat.

## Riwayat 29 Juli

- `SafMirrorStore` sempat menyimpan direktori bersarang sebagai `SingleDocumentFile`, sehingga
  berkas baru seperti manifest 0090 gagal dibuat. Handle direktori kemudian dipulihkan menjadi
  tree-capable dan manifest 0090 berhasil dibuat pada perangkat.
- Resume SAF pernah melampaui 35 menit karena direktori dibaca ulang untuk setiap berkas. Cache
  dan satu refresh saat gagal menurunkannya menjadi sekitar tiga menit untuk 151 pohon.
- Pohon 0089 dipulihkan dari ZIP setelah 19 artefak diverifikasi terhadap manifest. Resume
  mengimpor satu pohon dan data tablet diverifikasi kembali.

Detail investigasi, hash artefak, dan batas bukti tersimpan di
[`docs/FIELD_REPORT_20260727.md`](docs/FIELD_REPORT_20260727.md).

## Referensi

- [`README.md`](README.md): gambaran proyek dan batas runtime RGB-D.
- [`CLAUDE.md`](CLAUDE.md): build, signing, CI, dan rambu perangkat.
- [`PLAN.md`](PLAN.md): arsip rencana perbaikan sebelumnya.
- [`docs/archive/TODO_20260616.md`](docs/archive/TODO_20260616.md): backlog pengukuran historis.
