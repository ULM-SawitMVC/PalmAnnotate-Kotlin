---
version: 1
slug: "sawitulm-palmannotate-ui-navigation-navigation-kt"
primary_target: "app/src/main/java/dev/sawitulm/palmannotate/ui/navigation/Navigation.kt"
related_targets: ["app/src/main/java/dev/sawitulm/palmannotate/ui/home/HomeScreen.kt","app/src/main/java/dev/sawitulm/palmannotate/ui/carousel/CarouselScreen.kt"]
---

# Modul Dataset Berat Tandan

## Tujuan

Menambahkan beranda pemilih modul dan alur pengumpulan dataset berat tandan tanpa mengubah perilaku dataset multisisi yang sudah ada.

## Alur

1. Pengguna membuka PalmAnnotate dan memilih modul dataset.
2. Modul multisisi membuka alur 4/8 sisi yang sudah ada.
3. Modul berat tandan membuka daftar sesi khusus.
4. Setiap sampel memakai foto pertama wajib dan foto kedua opsional.
5. Pengguna membuat atau memilih kotak pembatas, memilih kelas B1–B4, lalu mengisi atribut tandan.
6. Kotak pada foto kedua dapat ditautkan ke kotak foto pertama dan memakai atribut yang sama.
7. Sampel hanya dapat diselesaikan ketika setiap tandan memiliki kelas dan berat lebih dari nol.

## Kontrak Data

- `weightKg`: wajib dan lebih besar dari nol.
- `heightCm`: opsional; jika diisi, harus lebih besar dari nol.
- `circumferenceCm`: opsional; jika diisi, harus lebih besar dari nol.
- `notes`: opsional dan dipangkas sebelum disimpan.
- Nilai opsional yang kosong disimpan sebagai `null`.
- Atribut kelompok tertaut diteruskan ke setiap kotak anggota agar penyimpanan, pemuatan, dan ekspor tetap konsisten.

## Kriteria Penerimaan

- AC-001: aplikasi dibuka pada pemilih modul dan tombol Kembali Android tetap bekerja.
- AC-002: sesi multisisi lama tetap tampil dan alur 4/8 sisi tetap dapat dibuka.
- AC-003: sesi berat tandan terpisah dari sesi multisisi walaupun varietas dan bloknya sama.
- AC-004: sampel berat tandan dapat disimpan dengan satu atau dua foto.
- AC-005: atribut yang diedit dari salah satu kotak tertaut muncul sama pada kotak pasangannya.
- AC-006: penyelesaian sampel ditolak jika kotak belum memiliki kelas atau berat yang valid.
- AC-007: ekspor memuat atribut satu kali pada tingkat tandan serta mempertahankan daftar kemunculan kotak pembatas.
- AC-008: migrasi v7 ke v8 mempertahankan seluruh baris lama dan memberi nilai baku dataset multisisi tanpa membuat data pengukuran palsu.

## Direction contract

THESIS: pemilih modul memberi satu keputusan awal dan menolak pencampuran dua jenis dataset dalam satu daftar. OWN-WORLD: Material 3, hijau PalmAnnotate, permukaan terang, target sentuh besar, dan teks lapangan yang langsung. STORY: pilih dataset, buka sesi, tangkap sampel, anotasi tandan, isi atribut, lalu simpan. FIRST VIEWPORT: identitas PalmAnnotate berada di atas, dua baris modul mengisi pusat layar, dan tiap baris menjelaskan jumlah foto serta jenis data. FORM: perluasan tepat dari dunia visual yang sudah ada; tanpa konsep atau komponen baru yang tidak diperlukan. FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.
