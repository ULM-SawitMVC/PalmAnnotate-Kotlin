# Modul Dataset Berat Tandan

## Tujuan

Menambahkan beranda pemilih modul dan alur pengumpulan dataset berat tandan tanpa mengubah perilaku dataset multisisi yang sudah ada.

## Alur

1. Pengguna membuka PalmAnnotate dan memilih modul dataset.
2. Modul multisisi membuka alur 4/8 sisi yang sudah ada.
3. Modul berat tandan membuka daftar sesi khusus.
4. Setiap sampel memakai foto pertama wajib dan foto kedua opsional.
5. Pengguna membuat atau memilih kotak pembatas, lalu mengisi atribut tandan tanpa memilih kelas kematangan.
6. Kotak pada foto kedua dapat ditautkan ke kotak foto pertama dan memakai atribut yang sama.
7. Sampel hanya dapat diselesaikan ketika setiap tandan memiliki berat lebih dari nol.

## Kontrak Interaksi Karusel

Bagian ini merekam keputusan yang sudah diverifikasi di perangkat. Mengubahnya berarti
menguji ulang di ponsel, bukan hanya di uji unit.

- **Menaut memakai nilai kotak sumber.** Ketika kedua kotak sudah berisi nilai yang berbeda,
  nilai kotak target dibuang. Aturan ini dipertahankan karena deterministik dan langsung
  memenuhi gerbang penyelesaian, tetapi kejadiannya wajib terlihat: `CarouselViewModel`
  menyalakan `linkReplacedMeasurements` dan layar menampilkan `weight_link_replaced`. Tanpa
  pemberitahuan itu, berat yang sudah diketik operator hilang tanpa jejak.
- **Menaut dan "Apply to bunch" langsung menyimpan.** Keduanya memanggil `autoSave()` sendiri,
  tidak menunggu pergantian foto, pergantian mode, atau keluar. Tandan yang sudah ditimbang
  tidak dapat ditimbang ulang, sehingga menahan nilainya di memori sampai peristiwa lain
  berarti satu kali proses dimatikan menghapus pekerjaan itu. Keduanya adalah satu ketukan
  sengaja, bukan seretan, jadi tidak membanjiri jalur simpan.
- **Panel pengukuran ringkas diangkat ke atas papan ketik, bukan diberi padding di dalam.**
  Panel itu ditambatkan di bawah dengan tinggi tetap, jadi memberi padding pada isinya justru
  memakan seluruh isi dan hanya menyisakan judul. Tumpang tindih papan ketik dihitung dari
  BoxWithConstraints pembungkusnya, bukan dari panel itu sendiri, agar posisi baru panel tidak
  mengumpan balik ke perhitungannya. Panel samping pada layar lebar tetap memakai padding
  dalam karena tidak dapat berpindah.
- **Tinggi panel mengikuti isinya.** Tertutup hanya memuat kolom berat, sakelar rincian
  opsional, dan tombol Apply, sehingga foto memperoleh sisa ruangnya; terbuka baru meninggi.
  Nilainya juga dibatasi oleh sisa ruang setelah papan ketik.
- **Konfirmasi simpan diberi inset bawah setinggi panel.** Pil "Saved" digambar di dasar area
  konten, yaitu tepat di tempat panel berada, dan panel disusun sesudahnya. Tanpa inset itu
  "Apply to bunch" menyimpan tanpa tanda apa pun dan operator tidak dapat memastikan
  ketukannya terbaca. Ini juga yang menjawab pertanyaan apakah tombol itu menyimpan.
- **Pesan galat validasi berada di luar area gulir.** Di dalam area gulir, panel yang tertutup
  mendorong pesan itu ke bawah lipatan dan pesan tampil terpotong separuh baris.

## Kontrak Data

- `weightKg`: wajib dan lebih besar dari nol.
- `heightCm`: opsional; jika diisi, harus lebih besar dari nol.
- `circumferenceCm`: opsional; jika diisi, harus lebih besar dari nol.
- `notes`: opsional dan dipangkas sebelum disimpan.
- Nilai opsional yang kosong disimpan sebagai `null`.
- Atribut kelompok tertaut diteruskan ke setiap kotak anggota agar penyimpanan, pemuatan, dan ekspor tetap konsisten.
- Kelas kematangan B1/B2/B3/B4 tidak digunakan pada modul berat tandan dan tidak ditulis ke Output JSON, CSV, Identity JSON, atau annot-log.
- Label YOLO tetap memakai kelas objek tunggal `0` karena format YOLO memerlukan ID kelas numerik untuk setiap kotak.

## Kriteria Penerimaan

- AC-001: aplikasi dibuka pada pemilih modul dan tombol Kembali Android tetap bekerja.
- AC-002: sesi multisisi lama tetap tampil dan alur 4/8 sisi tetap dapat dibuka.
- AC-003: sesi berat tandan terpisah dari sesi multisisi walaupun varietas dan bloknya sama.
- AC-004: sampel berat tandan dapat disimpan dengan satu atau dua foto.
- AC-005: atribut yang diedit dari salah satu kotak tertaut muncul sama pada kotak pasangannya.
- AC-006: penyelesaian sampel ditolak jika tandan belum memiliki berat yang valid.
- AC-007: ekspor memuat atribut satu kali pada tingkat tandan, mempertahankan daftar kemunculan kotak pembatas, dan tidak memuat kelas kematangan.
- AC-008: migrasi v7 ke v8 mempertahankan seluruh baris lama dan memberi nilai baku dataset multisisi tanpa membuat data pengukuran palsu.
- AC-009: menaut kotak yang sudah berisi nilai berbeda menampilkan pemberitahuan bahwa nilai kotak itu diganti.
- AC-010: menaut dan menerapkan atribut bertahan setelah proses aplikasi dimatikan tanpa berganti foto atau keluar.
- AC-011: seluruh isi panel pengukuran tetap terlihat ketika papan ketik terbuka, termasuk kolom berat dan pesan galat validasi.
- AC-012: menekan "Apply to bunch" memunculkan konfirmasi tersimpan yang terlihat, tidak tertutup panel.

## Batas yang Diketahui

- CSV dan Identity JSON hanya dihasilkan `ResultsScreen`, dan layar itu tidak dapat dijangkau
  dari modul berat tandan. Cabang CSV berat beserta tesnya ada, tetapi belum ada jalan bagi
  operator untuk mengunduhnya. Output JSON sudah memuat seluruh datanya pada tingkat tandan.
- Melepas tautan tidak tersedia sebagai aksi tersendiri. Koreksi dilakukan dengan menaut ulang,
  yang mengganti tautan lama, atau dengan menghapus kotaknya.
- Hanya tandan yang tertaut memperoleh nomor kelompok di kanvas, sehingga pesan gerbang
  penyelesaian yang menyebut "Bunch N" belum dapat ditelusuri ke kotaknya.
- Di mode Edit, geser horizontal ditelan zoom/pan kanvas. Berpindah foto hanya melalui dua
  titik halaman di atas foto.

## Direction contract

THESIS: pemilih modul memberi satu keputusan awal dan menolak pencampuran dua jenis dataset dalam satu daftar. OWN-WORLD: Material 3, hijau PalmAnnotate, permukaan terang, target sentuh besar, dan teks lapangan yang langsung. STORY: pilih dataset, buka sesi, tangkap sampel, anotasi tandan, isi atribut, lalu simpan. FIRST VIEWPORT: identitas PalmAnnotate berada di atas, dua baris modul mengisi pusat layar, dan tiap baris menjelaskan jumlah foto serta jenis data. FORM: perluasan tepat dari dunia visual yang sudah ada; tanpa konsep atau komponen baru yang tidak diperlukan. FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.
