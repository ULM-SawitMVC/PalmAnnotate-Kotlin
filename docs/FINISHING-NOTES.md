# Catatan finishing PalmAnnotate

Dicatat pada 12 September 2026. Daftar awal berisi empat usulan prioritas; keempatnya sudah
dikerjakan dan diverifikasi pada hari yang sama memakai varian `trace` v0.3.69 di Xiaomi Pad 6.

## Sudah selesai

1. **Identitas tandan.** Setiap kotak bernomor, bukan hanya kotak tertaut. Nomornya berasal dari
   klaster `ResultsComputer`, yaitu sumber yang sama dengan pesan gerbang penyelesaian dan
   `bunch_id` pada ekspor, dan digambar di dalam kotak agar tidak tertutup pegangan ubah ukuran.
   Judul panel pengukuran memakai nomor itu, menggantikan judul umum.
2. **Navigasi foto.** Tombol "Photo 1" dan "Photo 2" setinggi minimal 48 dp menggantikan
   ketergantungan pada titik halaman. Foto aktif memakai warna aksen, bukan warna tombol nonaktif
   yang tidak terbaca di atas foto.
3. **Lepas tautan.** Aksi "Remove link" tersedia ketika kotak terpilih memiliki tautan, dan
   menyimpan langsung seperti aksi sengaja lainnya. Kotak dan pengukurannya tidak ikut terhapus.
4. **Ekspor CSV.** Tombol ekspor pada editor menyimpan sampel lebih dahulu, lalu membuka pemilih
   dokumen Android. Berkas hasil ditarik dari perangkat dan isinya cocok dengan klaster tandan.

Pemangkasan teks antarmuka dikerjakan bersama daftar di atas: subjudul modul pada bilah atas,
keterangan kartu pemilih modul, tagline bilah atas, dan paragraf penjelas pada dialog mulai sesi
dihapus. Peringatan penguncian nama hanya muncul ketika sesi memang sudah terkunci.

## Yang masih terbuka

- Uji satu alur lengkap pada APK `field` yang akan dibagikan, bukan hanya varian `trace`.
- Verifikasi Orbbec pada perangkat: colok ulang kamera, alignment kedalaman, waktu buka kamera,
  latensi Next Tree, dan kelancaran preview.
- Batas modul yang tersisa ada di [`BUNCH-WEIGHT-MODULE.md`](BUNCH-WEIGHT-MODULE.md#batas-yang-diketahui).

Uji unit lulus 318/318 pada tanggal catatan ini. Angka itu hasil pengujian JVM, bukan pengganti
pengujian perangkat keras.
