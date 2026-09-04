# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Pengguna mengumpulkan dan memeriksa dataset kelapa sawit melalui perangkat Android, termasuk ketika koneksi jaringan tidak tersedia.

## Product Purpose

PalmAnnotate menjadi aplikasi induk untuk beberapa alur pengumpulan dataset kelapa sawit. Pengguna memilih jenis dataset, mengambil foto, memberikan anotasi, dan mengekspor artefak yang dapat diaudit.

## Operating Context

Aplikasi digunakan dalam orientasi lanskap dengan kamera perangkat atau kamera RGB-D yang didukung. Data disimpan secara lokal dan dapat dicerminkan ke folder ekspor yang dipilih pengguna.

## Capabilities and Constraints

- Alur dataset multisisi yang sudah ada mempertahankan pengambilan 4 atau 8 sisi.
- Alur berat tandan memakai satu foto wajib dan satu foto kedua opsional.
- Setiap tandan memakai kelas B1–B4 dan berat wajib.
- Tinggi, keliling, dan catatan tambahan bersifat opsional.
- Kotak pembatas lintas foto yang ditautkan mewakili satu tandan dan berbagi atribut.
- Nilai yang tidak diukur disimpan sebagai nilai kosong, bukan angka nol.
- Data lama harus tetap terbaca setelah peningkatan skema.

## Brand Commitments

Nama PalmAnnotate, palet hijau kelapa sawit, komponen Material 3, dan keterbacaan di lapangan dipertahankan.

## Evidence on Hand

- Implementasi Android berada di `app/src/main/java/dev/sawitulm/palmannotate`.
- Model kotak pembatas dan relasi lintas sisi sudah tersedia.
- Skema Room dan pengujian migrasi tersedia sampai versi 7.

## Product Principles

- Lindungi data lapangan sebelum mengutamakan kenyamanan implementasi.
- Tampilkan konteks dan tindakan utama tanpa menutupi foto.
- Simpan satu fakta tandan untuk seluruh kemunculannya pada beberapa foto.
- Pertahankan kompatibilitas alur dan ekspor dataset lama.

