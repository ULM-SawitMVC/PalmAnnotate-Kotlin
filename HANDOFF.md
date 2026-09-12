# PalmAnnotate Native - Session Handoff

> **Diperbarui:** 12 September 2026 (Asia/Makassar)
> Status bukti lapangan sebelumnya ada di [laporan lapangan](docs/FIELD_REPORT_20260727.md#58-addendum-audit-terkini-29-juli-2026).

## Status terkini

- Build perangkat terakhir yang diverifikasi adalah `.field` v0.3.67 pada Motorola moto g45 5G.
- Uji unit lulus **318/318**, dengan 0 kegagalan, 0 error, dan 0 tes dilewati.
- Modul berat tandan diuji ujung ke ujung di perangkat: pemilih modul, dialog sesi, tangkap dua
  foto, anotasi, tautan lintas foto, gerbang penyelesaian, cermin SAF, dan isi ZIP ekspor.
  Kontrak datanya benar. Output JSON memuat `dataset_type`, `weight_kg`, `bunch_id`,
  `appearance_count`, dan tidak memuat satu pun kunci kematangan; label YOLO memakai kelas
  tunggal `0`; berkas hasil cermin SAF identik byte dengan salinan lokal.
- Pembuatan berkas SAF bersarang **tidak lagi rusak**. Sampel baru menulis berkas baru ke
  seluruh direktori bersarang di folder ekspor, termasuk direktori `exports/` yang baru dibuat.
- Hanya varian `field` yang mendeklarasikan filter manifest `USB_DEVICE_ATTACHED`. Varian
  `debug` dan `trace` meminta izin USB saat runtime.

## Riwayat 12 September

Penyelesaian kandidat rilis pada modul berat tandan, diverifikasi memakai varian `trace` v0.3.69
pada Xiaomi Pad 6 lewat ADB nirkabel. Data aplikasi tidak dihapus; APK dipasang di tempat setelah
sidik sertifikat penanda tangan dicocokkan dengan paket yang sudah terpasang.

- Setiap kotak tandan memperoleh nomor, bukan hanya kotak tertaut, dan nomornya berasal dari
  klaster hasil yang sama dengan pesan gerbang penyelesaian serta `bunch_id` pada ekspor. Nomor
  digambar di dalam kotak agar tidak tertutup pegangan ubah ukuran.
- Berpindah foto memakai tombol "Photo 1" dan "Photo 2", karena mode Edit menelan geser
  horizontal untuk zoom dan geser kanvas.
- "Remove link" tersedia sebagai aksi tersendiri dan menyimpan langsung. Kotak beserta
  pengukurannya tidak ikut terhapus.
- Ekspor CSV sampel dapat dijangkau dari editor melalui pemilih dokumen Android. Berkas hasil
  ditarik dari perangkat dan isinya cocok dengan klaster tandan.
- Teks antarmuka dipangkas: subjudul modul pada bilah atas, keterangan kartu pemilih modul, dan
  paragraf penjelas pada dialog mulai sesi dihapus. Peringatan penguncian nama hanya tampil
  ketika sesi memang sudah terkunci.

Verifikasi perangkat mencakup pemasangan ulang, pelepasan tautan, penomoran ulang setelah
tautan dilepas, dan ekspor CSV. Perangkat keras Orbbec tetap belum diuji.

## Riwayat 9 September

Empat cacat modul berat tandan ditemukan lewat pengujian di perangkat, bukan lewat uji unit,
lalu diperbaiki dan diverifikasi ulang di perangkat.

- Papan ketik menutupi seluruh isi panel pengukuran. Panel ringkas kini diangkat ke atas papan
  ketik; tumpang tindihnya dihitung dari kontainer pembungkus agar tidak mengumpan balik.
- Pesan galat validasi tampil terpotong separuh baris. Pesan dipindahkan keluar dari area gulir.
- Hasil "Apply to bunch" dan pembuatan tautan hanya ditahan di memori sampai operator berganti
  foto atau keluar, sehingga satu kali proses dimatikan menghapusnya. Keduanya kini menyimpan
  sendiri.
- Menaut mengganti nilai kotak target tanpa pemberitahuan. Aturan "kotak sumber menang"
  dipertahankan, tetapi kini disertai pemberitahuan.

Empat batas yang tercatat pada tanggal itu ditutup pada 12 September. Batas yang masih terbuka
ada di [`docs/BUNCH-WEIGHT-MODULE.md`](docs/BUNCH-WEIGHT-MODULE.md#batas-yang-diketahui).

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
- [Rencana RGB-D historis](docs/archive/PLAN_RGBD_202606.md): arsip rencana perbaikan sebelumnya.
- [`docs/archive/TODO_20260616.md`](docs/archive/TODO_20260616.md): backlog pengukuran historis.
