# Fishing Mod Automation Minecraft

Aplikasi automasi berbasis skrip untuk melakukan kegiatan memancing secara otomatis (*AFK Fishing*) pada game Minecraft. Proyek ini mendeteksi indikator gigitan ikan secara cerdas untuk menarik dan melempar kembali joran tanpa interaksi manual dari pemain.

## 📱 Fitur Utama

* **Otomasi Memancing Penuh:** Mendeteksi umpan yang tenggelam atau ditarik ikan, lalu secara otomatis menarik joran (*reel in*) dan melemparkannya kembali (*recast*).
* **Deteksi Gigitan Pintar:** Menggunakan algoritma deteksi berbasis visual (perubahan piksel/gerakan *bobber*) atau pembacaan memori/log status entitas untuk akurasi tinggi.
* **Perlindungan Joran:** Fitur deteksi durabilitas joran untuk menghentikan proses memancing secara otomatis sebelum joran pancing rusak atau hancur.
* **Manajemen Inventaris:** Integrasi opsional untuk memilah hasil pancingan dan membuang sampah (*junk items*) secara otomatis guna menjaga ruang penyimpanan tetap bersih.
* **Ramah Mode Survival:** Berjalan secara efisien di latar belakang dengan konsumsi memori rendah tanpa mengganggu performa permainan.

## 🛠️ Tech Stack & Dependensi

* **Bahasa Pemrograman / Ekstensi:** Python (atau Java dependensi Mod)
* **Pustaka Utama:** * Mod Framework (Fabric / Forge API jika berbasis Mod)
  * OpenCV / PyAutoGUI / Pillow (jika berbasis skrip visual eksternal)

## 🏛️ Arsitektur Proyek

```text
fishing-mod-automation-minecraft/
├── src/ / lib/         # Logika utama otomasi, deteksi, dan kontrol input
├── assets/ / templates/# Gambar sampel *bobber* joran atau konfigurasi visual
├── config/             # Pengaturan ambang batas (*threshold*) delay dan durabilitas
└── README.md           # Dokumentasi proyek
